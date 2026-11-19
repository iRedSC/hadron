package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.api.event.entity.HangingPlaceEvent;
import dev.iredsc.hadron.api.event.entity.ProjectileLaunchEvent;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * {@code ServerLevel.addFreshEntity}: records who caused each new entity, then fires the spawn event. Server thread
 * only.
 */
public final class EntitySpawns {
    /** Above zero inside {@code Mob.convertTo}, whose new mob is a transform, not a spawn. */
    @StaticState(reason = "a server-thread depth count around one conversion, back to zero when it returns")
    private static int converting;

    private EntitySpawns() {}

    /** True to let the entity in. */
    public static boolean add(ServerLevel level, Entity entity) {
        if (entity instanceof Player) return true;
        rememberCause(entity);
        // What these carry was already taken from somewhere (a broken block, a dead mob's inventory, a falling
        // block's space), so refusing their arrival would destroy it rather than undo anything. Items have their
        // own events in the item family.
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb || entity instanceof FallingBlockEntity) {
            return true;
        }
        EntityState state = (EntityState) entity;
        if (converting > 0 || state.hadron$converted()) {
            state.hadron$setConverted(false);
            return true;
        }
        if (entity instanceof FishingHook hook && EntityFishing.cancelCast(hook)) return refuseHook(hook);
        Class<? extends Event> type = entity instanceof Projectile ? ProjectileLaunchEvent.class
                : entity instanceof HangingEntity ? HangingPlaceEvent.class : EntitySpawnEvent.class;
        if (!Hooks.listens(type)) return true;
        long build = BlockHooks.enterOutsidersBuild(entity);
        EntitySpawnEvent event;
        try {
            event = switch (entity) {
                case Projectile p -> new ProjectileLaunchEvent(p, p.getOwner() != null
                        ? EntityActors.of(p.getOwner()) : EntityActors.fromStack(p, Actor.Unknown.INSTANCE));
                case HangingEntity h -> new HangingPlaceEvent(h, EntityActors.fromStack(h, Actor.Unknown.INSTANCE));
                default -> {
                    MobSpawnType reason = entity instanceof Mob mob ? mob.getSpawnType() : null;
                    yield new EntitySpawnEvent(entity, cause(itemUses > 0, reason), reason,
                            EntityActors.fromStack(entity, fallback(entity)));
                }
            };
        } finally {
            if (build != 0) Actors.Port.exit(PortKey.KEY, build);
        }
        boolean cancelled = Hooks.post(event).isCancelled();
        tried(cancelled);
        if (cancelled && entity instanceof FishingHook hook) return refuseHook(hook);
        return !cancelled;
    }

    /**
     * How an entity that is neither a projectile nor hanging came to be: placed when an item's own use or a dispenser's
     * shot is running ({@link #itemUses}), or when Minecraft's own reason says an item made it (a spawn egg used on a
     * mob for its baby is no item use of its own); spawned otherwise. No entity class is asked, so a mod's furniture
     * placed by its item is placed.
     */
    static EntitySpawnEvent.Cause cause(boolean inItemUse, @Nullable MobSpawnType reason) {
        boolean byItem = inItemUse || reason == MobSpawnType.SPAWN_EGG || reason == MobSpawnType.BUCKET
                || reason == MobSpawnType.DISPENSER;
        return byItem ? EntitySpawnEvent.Cause.PLACED : EntitySpawnEvent.Cause.SPAWNED;
    }

    /**
     * Above zero while an item's own use runs on the server ({@code ItemStack.use}, {@code useOn},
     * {@code onItemUseFirst}) or a dispenser dispenses an item: what enters the world meanwhile was put there by it.
     * Not a block's reaction to the item in hand (flint and steel lighting TNT), which never passes those. Server
     * thread only.
     */
    @StaticState(reason = "a server-thread depth count around one item use, back to what it was when the use returns")
    private static int itemUses;

    /** An item's own use begins on the server thread; pair with {@link #endItemUse} in a {@code finally}. */
    public static void beginItemUse() {
        itemUses++;
    }

    public static void endItemUse() {
        itemUses--;
    }

    /** Runs a dispenser's shot as an item use. */
    static <T> T placing(Supplier<T> dispense) {
        itemUses++;
        try {
            return dispense.get();
        } finally {
            itemUses--;
        }
    }

    /**
     * Sets the item use aside while an event's handlers run ({@link HandlerScope}): what a handler spawns is its own
     * spawn, not the placement the event is about. Returns what to hand {@link #resumeItemUse}.
     */
    static int pauseItemUse() {
        int outer = itemUses;
        itemUses = 0;
        return outer;
    }

    static void resumeItemUse(int outer) {
        itemUses = outer;
    }

    /**
     * A bobber made itself its player's line when built; refused, it must let go, or the next click would reel in a
     * bobber that was never in the world.
     */
    private static boolean refuseHook(FishingHook hook) {
        hook.discard();
        return false;
    }

    /**
     * Counts one thing a dispense or a use tried that a listener could refuse: a spawn, or an equip
     * ({@link ItemHooks#cancelEquip}). Server thread only.
     */
    static void tried(boolean refusedNow) {
        attempts++;
        if (refusedNow) refused++;
    }

    /**
     * What dispenses and uses tried (spawns, equips) and how many were refused, so far on the server thread;
     * {@link EntitySpawnRefunds} compares them.
     */
    @StaticState(reason = "a running count; EntitySpawnRefunds reads only its change across one dispense")
    static int attempts;
    @StaticState(reason = "a running count; EntitySpawnRefunds reads only its change across one dispense")
    static int refused;

    /** With nothing on the stack: the world spawned it (natural mobs, weather), or a mod did and didn't say. */
    private static Actor fallback(Entity entity) {
        if (entity instanceof LightningBolt) return Actor.Nature.INSTANCE;
        if (entity instanceof Mob mob && mob.getSpawnType() != null) {
            return switch (mob.getSpawnType()) {
                case NATURAL, CHUNK_GENERATION, STRUCTURE, PATROL, EVENT, JOCKEY, REINFORCEMENT, MOB_SUMMONED,
                        BREEDING -> Actor.Nature.INSTANCE;
                default -> Actor.Unknown.INSTANCE;
            };
        }
        return Actor.Unknown.INSTANCE;
    }

    /** Projectiles and primed TNT name their owner, tamed mobs theirs; otherwise whoever is acting now. */
    private static void rememberCause(Entity entity) {
        if (!Actors.remembered(entity).isEmpty()) return;
        Attribution cause = Attribution.none();
        if (entity instanceof TraceableEntity traceable && traceable.getOwner() != null
                && traceable.getOwner() != entity) {
            cause = EntityActors.attributionOf(traceable.getOwner());
        } else if (entity instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null) {
            cause = Attribution.of(ownable.getOwnerUUID());
        }
        if (cause.isEmpty()) {
            Optional<Actor> current = Actors.current();
            if (current.isPresent() && !EntityActors.isSelf(current.get(), entity)) {
                cause = current.get().attribution();
            }
        }
        if (!cause.isEmpty()) Actors.remember(entity, cause);
    }

    /** Runs a conversion, whose new mob is not announced as a spawn, and passes on who was behind the old one. */
    public static <T extends Entity> T converting(Entity from, Supplier<T> conversion) {
        converting++;
        T result;
        try {
            result = conversion.get();
        } finally {
            converting--;
        }
        if (result != null) inherit(from, result);
        return result;
    }

    /** The new entity of a conversion carries the old one's attribution. */
    static void inherit(Entity from, Entity to) {
        Attribution attribution = Actors.remembered(from);
        if (!attribution.isEmpty() && Actors.remembered(to).isEmpty()) Actors.remember(to, attribution);
    }
}
