package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.entity.EntityCaptureEvent;
import dev.iredsc.hadron.api.event.entity.EntityRemoveEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Bucketable;
import net.minecraft.world.entity.decoration.ArmorStand;
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
     * {@code /kill}'s targets, without those a plugin refused to have removed. Living entities other than armor
     * stands are kept without asking: {@code kill} hurts them, so they die through the damage and death events.
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
        if (!(entity.level() instanceof ServerLevel) || diesWhenKilled(entity)) return false;
        Actor actor = EntityActors.fromStack(entity, Actor.Unknown.INSTANCE);
        return Hooks.post(new EntityRemoveEvent(entity, EntityRemoveEvent.Cause.KILLED, actor)).isCancelled();
    }

    /** Whether {@code kill} hurts the entity to death instead of removing it: every living entity but armor stands. */
    static boolean diesWhenKilled(Entity entity) {
        return entity instanceof LivingEntity && !(entity instanceof ArmorStand);
    }
}
