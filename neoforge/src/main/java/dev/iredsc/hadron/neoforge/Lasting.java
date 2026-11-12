package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.event.Place;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * Effects and fire acting on an entity: while one acts (its damage, its healing, what it spawns), its cause is on the
 * actor stack as an explicit cause, kept even when it is the entity itself, and the entity remembers what is acting
 * on it so the damage and healing events can say where it was applied.
 *
 * <pre>{@code
 * Lasting.Acting outer = Lasting.acting(subject);
 * long scope = Lasting.enter(subject, cause);
 * try { ... } finally { Lasting.exit(subject, outer, scope); }
 * }</pre>
 */
public final class Lasting {
    private Lasting() {}

    /** What is acting on an entity right now: a recorded cause, or {@link Unrecorded} for one saved without it. */
    public sealed interface Acting permits LastingCause, Unrecorded {}

    /** An effect or fire acting whose cause nothing recorded: saved before Hadron kept it, or added around it. */
    public enum Unrecorded implements Acting {
        INSTANCE
    }

    /** What the entity mixin adds to every entity for this; cast any entity to it. */
    public interface State {
        @Nullable Acting hadron$acting();

        void hadron$setActing(@Nullable Acting acting);

        /** How to undo the last ignition that took the fire's credit, within the tick it happened; see {@link #fireSet}. */
        @Nullable FireUndo hadron$fireUndo();

        void hadron$setFireUndo(@Nullable FireUndo undo);

        /** True while the entity's own tick burns its fire down, so that is never read as an ignition taken back. */
        boolean hadron$burningDown();

        void hadron$setBurningDown(boolean burningDown);
    }

    /**
     * What an ignition replaced: the fire's cause before it ({@code null} for none), its burning time before it, and
     * the game tick it happened in. Not saved: only a rollback in the same tick uses it.
     */
    public record FireUndo(@Nullable LastingCause cause, int ticks, long gameTime) {}

    /**
     * What the effect mixin adds to every {@link MobEffectInstance}: who applied it and where. Hidden effects keep
     * their own, and a hidden effect resuming brings its own back. Cast any instance to it.
     */
    public interface EffectState {
        @Nullable LastingCause hadron$cause();

        void hadron$setCause(@Nullable LastingCause cause);

        /** The weaker or shorter effect this one hides, which resumes when this one runs out. */
        @Nullable MobEffectInstance hadron$hidden();

        /** The entity this instance was last active on, or null when none is known. */
        @Nullable LivingEntity hadron$activeOn();

        void hadron$setActiveOn(LivingEntity entity);
    }

    /** Who applied an effect, or null when nothing recorded it. */
    public static @Nullable LastingCause cause(MobEffectInstance effect) {
        return ((EffectState) effect).hadron$cause();
    }

    /**
     * Runs {@code apply}, vanilla applying an effect to {@code subject}, with the credit going to {@code source} when
     * there is one (which may be the subject itself, a player's own splash potion), otherwise to whoever is on the
     * stack ({@link Actor.Nature} when nothing is), with where the subject stands. Returns what {@code apply} returns.
     *
     * <p>The credit lands only where vanilla really applies it: on the instance when it becomes the subject's active
     * effect, on the active one when {@code update} replaces it (a higher amplifier, or the same for longer), and on a
     * hidden copy when it waits underneath. An instance that ends up nowhere new (refused, weaker and shorter, or
     * already the active one) keeps the cause it had. An instance already active on another entity (a mod passing one
     * entity's effect to the next) is applied as a copy, so each entity's effect has its own cause.
     */
    public static <T> T applying(LivingEntity subject, MobEffectInstance effect, @Nullable Entity source,
            Function<MobEffectInstance, T> apply) {
        LivingEntity elsewhere = ((EffectState) effect).hadron$activeOn();
        MobEffectInstance applied = elsewhere != null && elsewhere != subject
                && elsewhere.getEffect(effect.getEffect()) == effect ? new MobEffectInstance(effect) : effect;
        MobEffectInstance before = subject.getEffect(applied.getEffect());
        EffectState state = (EffectState) applied;
        LastingCause own = state.hadron$cause();
        Actor actor = source != null ? EntityActors.of(source) : EntityActors.current();
        // Carried on the instance while vanilla runs, so update() and the hidden copies it makes can take it.
        state.hadron$setCause(LastingCause.of(actor, subject));
        try {
            return apply.apply(applied);
        } finally {
            if (applied != before && subject.getEffect(applied.getEffect()) == applied) {
                state.hadron$setActiveOn(subject);
            } else {
                state.hadron$setCause(own);
            }
        }
    }

    /** What is acting on the entity now, or null; pass it back to {@link #exit}. */
    public static @Nullable Acting acting(Entity subject) {
        return ((State) subject).hadron$acting();
    }

    /**
     * Marks {@code cause} as acting on {@code subject} and pushes who it is, {@link Actor.Nature} included, so what the
     * effect or fire does is never credited to whoever else is on the stack. Returns the stack handle for
     * {@link #exit}, or 0 when nothing was pushed: a cause nothing recorded. Allocates nothing once the cause has been
     * looked up.
     */
    public static long enter(Entity subject, @Nullable LastingCause cause) {
        ((State) subject).hadron$setActing(cause != null ? cause : Unrecorded.INSTANCE);
        MinecraftServer server = subject.getServer();
        if (cause == null || server == null) return 0;
        return Actors.Port.enter(PortKey.KEY, cause.actor(server));
    }

    /** Undoes {@link #enter}: pops what it pushed, and puts back what was acting before. */
    public static void exit(Entity subject, @Nullable Acting outer, long scope) {
        if (scope != 0) Actors.Port.exit(PortKey.KEY, scope);
        ((State) subject).hadron$setActing(outer);
    }

    /**
     * Where what is acting on {@code subject} now was applied, or null when nothing is: so damage and healing that an
     * effect or fire deals to the entity it is on say where it came from.
     */
    public static @Nullable Place appliedAt(Entity subject) {
        Acting acting = ((State) subject).hadron$acting();
        if (acting == null) return null;
        MinecraftServer server = subject.getServer();
        return switch (acting) {
            case LastingCause c when server != null -> c.appliedAt(server);
            case LastingCause c -> Place.Unknown.anywhere(Place.Unknown.Reason.UNSEEN_ORIGIN);
            case Unrecorded u -> Place.Unknown.anywhere(Place.Unknown.Reason.UNSEEN_ORIGIN);
        };
    }

    /**
     * The fire on {@code entity} changed from {@code before} to {@code after} ticks: an ignition that leaves more
     * burning time than before takes the credit, with who is on the stack (not the entity itself: walking into fire
     * or lava is nature's, since a fire block or lava has nowhere to keep who placed it) and where the entity stands;
     * going out drops it. Anything else (burning down, a shorter ignition) leaves the credit where it was.
     *
     * <p>An extension that is taken back gives the credit back: vanilla sets a target alight before a flaming arrow's
     * hit and, when the hit is refused, puts its burning time back exactly as it was. So an explicit set back down to
     * the burning time the last ignition replaced, in the tick it happened and before the fire has burned at all
     * since, restores that ignition's cause and where it was applied. The entity's own burning down
     * ({@code baseTick}, marked by {@link State#hadron$burningDown()}) is never that: it drops the undo instead, so
     * an extension that has burned for a tick has stuck.
     */
    public static void fireSet(Entity entity, int before, int after) {
        State state = (State) entity;
        if (state.hadron$burningDown()) {
            // The fire burning by itself: whatever lit it last has stuck, and nothing here is an ignition.
            state.hadron$setFireUndo(null);
            if (after <= 0 && before > 0) setFireCause(entity, null);
            return;
        }
        if (after <= 0) {
            state.hadron$setFireUndo(null);
            if (before > 0) setFireCause(entity, null);
            return;
        }
        long now = entity.level().getGameTime();
        FireUndo undo = state.hadron$fireUndo();
        if (undo != null && after < before && after == undo.ticks() && undo.gameTime() == now) {
            state.hadron$setFireUndo(null);
            setFireCause(entity, undo.cause());
            return;
        }
        if (after <= Math.max(before, 0)) return;
        state.hadron$setFireUndo(new FireUndo(fireCause(entity), before, now));
        setFireCause(entity, LastingCause.of(EntityActors.fromStack(entity, Actor.Nature.INSTANCE), entity));
    }

    private static void setFireCause(Entity entity, @Nullable LastingCause cause) {
        if (cause != null) {
            entity.setData(AttributionMemory.FIRE_CAUSE, Optional.of(cause));
        } else if (entity.hasData(AttributionMemory.FIRE_CAUSE)) {
            entity.removeData(AttributionMemory.FIRE_CAUSE);
        }
    }

    /** Who set the entity alight and where, or null when nothing recorded it. */
    public static @Nullable LastingCause fireCause(Entity entity) {
        return entity.hasData(AttributionMemory.FIRE_CAUSE)
                ? entity.getData(AttributionMemory.FIRE_CAUSE).orElse(null) : null;
    }

    /**
     * Whether {@code top}, the subject itself, was pushed as the cause of what is acting on it (its own poison), so
     * it is kept rather than read as the entity hurting itself.
     */
    static boolean isExplicitCause(Entity subject, Actor top) {
        return EntityActors.isSelf(top, subject) && ((State) subject).hadron$acting() instanceof LastingCause c
                && c.names(subject);
    }
}
