package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.entity.EntityCaptureEvent;
import dev.iredsc.hadron.api.event.entity.EntityRemoveEvent;
import dev.iredsc.hadron.checks.StaticState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Bucketable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Entities leaving the world other than by dying: the two vanilla paths core can refuse without leaving anything
 * behind. Refusing at {@code Entity.remove} itself would duplicate: an item pickup or an arrow pickup has already
 * given the item when it removes the entity.
 */
public final class EntityRemovals {
    private EntityRemovals() {}

    /**
     * A water bucket about to scoop up a fish, axolotl or tadpole; true refuses it. Asked before anything changes,
     * with the same test vanilla uses, so a click that wouldn't scoop fires nothing.
     */
    public static <T extends LivingEntity & Bucketable> boolean refuseBucket(Player player, InteractionHand hand,
            T entity) {
        if (!(entity.level() instanceof ServerLevel) || !Hooks.listens(EntityCaptureEvent.class)) return false;
        if (!player.getItemInHand(hand).is(Items.WATER_BUCKET) || !entity.isAlive()) return false;
        ItemStack bucket = entity.getBucketItemStack();
        entity.saveToBucketTag(bucket);
        boolean refused = Hooks.post(new EntityCaptureEvent(entity, new EntityCaptureEvent.Storage.Item(bucket),
                EntityActors.of(player))).isCancelled();
        if (refused && player instanceof ServerPlayer viewer && !(player instanceof FakePlayer)) {
            // The client scooped it already: give back its water bucket and show it the fish again.
            ItemHooks.resync(viewer);
            NetPort.untrack(viewer, entity);
            NetPort.retrack(viewer, entity);
        }
        return refused;
    }

    /**
     * {@code /kill}'s targets, without those a plugin refused to have removed. Entities whose {@code kill} hurts them
     * are kept without asking: they die through the damage and death events.
     */
    public static <E extends Entity> Collection<E> killable(Collection<E> targets) {
        if (!Hooks.listens(EntityRemoveEvent.class)) return targets;
        List<E> kept = new ArrayList<>(targets.size());
        for (E entity : targets) {
            if (!refuseKill(entity)) kept.add(entity);
        }
        return kept.size() == targets.size() ? targets : kept;
    }

    private static boolean refuseKill(Entity entity) {
        if (!(entity.level() instanceof ServerLevel) || HURT_BY_KILL.get(entity.getClass())) return false;
        Actor actor = EntityActors.fromStack(entity, Actor.Unknown.INSTANCE);
        return Hooks.post(new EntityRemoveEvent(entity, EntityRemoveEvent.Cause.KILLED, actor)).isCancelled();
    }

    /**
     * Per entity class, whether {@code kill} hurts it to death: only when it runs {@link LivingEntity}'s own
     * {@code kill}. Any override is asked about as a removal, whatever it does: armor stands and the ender dragon
     * remove themselves without dying, and a mod's override may too. One that ends up hurting the entity after all is
     * asked twice (a removal, then its damage), never missed.
     */
    @StaticState(reason = "which kill() a class runs, derived from the class alone and stored on it, so an entry never goes stale and goes with its class loader")
    private static final ClassValue<Boolean> HURT_BY_KILL = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("kill").getDeclaringClass() == LivingEntity.class;
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Entity.kill() is missing from " + type.getName() + ": this Hadron "
                        + "doesn't match the running Minecraft; install the Hadron built for this version", e);
            }
        }
    };
}
