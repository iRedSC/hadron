package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.event.Place;
import java.util.Optional;
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
    }

    /**
     * What the effect mixin adds to every {@link MobEffectInstance}: who applied it and where. Hidden effects keep
     * their own, and a hidden effect resuming brings its own back. Cast any instance to it.
     */
    public interface EffectState {
        @Nullable LastingCause hadron$cause();

        void hadron$setCause(@Nullable LastingCause cause);

        /** The weaker or shorter effect this one hides, which resumes when this one runs out. */
        @Nullable MobEffectInstance hadron$hidden();
    }

    /** Who applied an effect, or null when nothing recorded it. */
    public static @Nullable LastingCause cause(MobEffectInstance effect) {
        return ((EffectState) effect).hadron$cause();
    }

    /**
     * An effect is being applied to {@code subject}: it takes the credit, given to {@code source} when there is one
     * (which may be the subject itself, a player's own splash potion), otherwise to whoever is on the stack, with
     * where the subject stands. Whether it then replaces the effect already there is {@code update}'s to decide.
     */
    public static void effectApplied(LivingEntity subject, MobEffectInstance effect, @Nullable Entity source) {
        Actor actor = source != null ? EntityActors.of(source) : EntityActors.current();
        ((EffectState) effect).hadron$setCause(LastingCause.of(actor, subject));
    }

    /** What is acting on the entity now, or null; pass it back to {@link #exit}. */
    public static @Nullable Acting acting(Entity subject) {
        return ((State) subject).hadron$acting();
    }

    /**
     * Marks {@code cause} as acting on {@code subject} and pushes who it is, if anyone. Returns the stack handle for
     * {@link #exit}, or 0 when nothing was pushed. Allocates nothing once the cause has been looked up.
     */
    public static long enter(Entity subject, @Nullable LastingCause cause) {
        ((State) subject).hadron$setActing(cause != null ? cause : Unrecorded.INSTANCE);
        MinecraftServer server = subject.getServer();
        Actor actor = cause == null || server == null ? null : cause.actor(server);
        return actor == null ? 0 : Actors.Port.enter(PortKey.KEY, actor);
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
     */
    public static void fireSet(Entity entity, int before, int after) {
        if (after <= 0) {
            if (before > 0 && entity.hasData(AttributionMemory.FIRE_CAUSE)) {
                entity.removeData(AttributionMemory.FIRE_CAUSE);
            }
            return;
        }
        if (after <= Math.max(before, 0)) return;
        Actor actor = EntityActors.fromStack(entity, Actor.Nature.INSTANCE);
        entity.setData(AttributionMemory.FIRE_CAUSE, Optional.of(LastingCause.of(actor, entity)));
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
        Acting acting = ((State) subject).hadron$acting();
        MinecraftServer server = subject.getServer();
        return acting instanceof LastingCause c && server != null && c.actor(server) == top;
    }
}
