package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.entity.ArmorStandManipulateEvent;
import dev.iredsc.hadron.api.event.entity.EntityLeashEvent;
import dev.iredsc.hadron.api.event.entity.EntityShearEvent;
import dev.iredsc.hadron.api.event.entity.EntityUnleashEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.LeadItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Handling an entity: leads, shears and armor stands. Each is asked with the same test vanilla uses just before it
 * acts, so a click that would do nothing fires nothing, and a refusal leaves everything as it was.
 *
 * <p>Leads are asked where a player acts, not in {@code Leashable}'s own methods: those also run when a lead
 * breaks on its own or comes back from a save, where refusing would leave an entity tied to nothing.
 */
public final class EntityHandling {
    private EntityHandling() {}

    /**
     * A player right-clicking an entity, which {@code Entity.interact} turns into taking their lead off it or putting
     * a lead on it; true refuses it, and the click then does nothing else either (no mounting, no shearing).
     */
    public static boolean refuseLeashClick(Entity entity, Player player, InteractionHand hand) {
        if (!(entity.level() instanceof ServerLevel) || !entity.isAlive() || !(entity instanceof Leashable leashable)) {
            return false;
        }
        if (leashable.getLeashHolder() == player) {
            return Hooks.listens(EntityUnleashEvent.class)
                    && Hooks.post(new EntityUnleashEvent(entity, player, EntityActors.of(player))).isCancelled();
        }
        if (!player.getItemInHand(hand).is(Items.LEAD) || !leashable.canHaveALeashAttachedToIt()
                || !Hooks.listens(EntityLeashEvent.class)) {
            return false;
        }
        boolean refused = Hooks.post(new EntityLeashEvent(entity, player, EntityActors.of(player))).isCancelled();
        if (refused) ItemHooks.resync(player); // the client used up its lead already
        return refused;
    }

    /** A leashed entity the player holds is about to be tied to the knot; true leaves it with the player. */
    public static boolean refuseTie(Leashable leashable, Entity knot, Player player) {
        if (!(leashable instanceof Entity entity) || !(entity.level() instanceof ServerLevel)
                || !Hooks.listens(EntityLeashEvent.class)) {
            return false;
        }
        return Hooks.post(new EntityLeashEvent(entity, knot, EntityActors.of(player))).isCancelled();
    }

    /** Whether a tie was refused: the player still holds an entity the click would have tied to the fence. */
    public static boolean refusedTie(Level level, BlockPos fence, Player player) {
        return Hooks.listens(EntityLeashEvent.class)
                && !LeadItem.leashableInArea(level, fence, l -> l.getLeashHolder() == player).isEmpty();
    }

    /**
     * After a lead click on a fence: a knot made by this click that ended up holding nothing, because every tie was
     * refused, is taken down again rather than left on the fence.
     */
    public static void dropUnusedKnot(Level level, BlockPos fence, Player player) {
        if (level.isClientSide || !refusedTie(level, fence, player)) return;
        AABB around = new AABB(fence).inflate(1);
        for (LeashFenceKnotEntity knot : level.getEntitiesOfClass(LeashFenceKnotEntity.class, around,
                k -> k.getPos().equals(fence))) {
            // Packets are handled before entities tick, so a knot this click made has not ticked yet.
            if (knot.tickCount == 0 && LeadItem.leashableInArea(level, fence, l -> l.getLeashHolder() == knot)
                    .isEmpty()) {
                knot.discard();
            }
        }
    }

    /**
     * Shears about to cut an entity vanilla says is ready; true leaves it uncut. The player is null for a dispenser,
     * which is on the actor stack.
     */
    public static boolean refuseShear(Object target, ItemStack shears, @Nullable Player player) {
        if (!(target instanceof Entity entity) || !(entity.level() instanceof ServerLevel)
                || !Hooks.listens(EntityShearEvent.class)) {
            return false;
        }
        Actor actor = player != null ? EntityActors.of(player) : EntityActors.fromStack(entity, Actor.Unknown.INSTANCE);
        return Hooks.post(new EntityShearEvent(entity, shears, actor)).isCancelled();
    }

    /** A player about to swap their hand with an armor stand's slot; true leaves both as they were. */
    public static boolean refuseStandSwap(ArmorStand stand, int disabledSlots, Player player, EquipmentSlot slot,
            ItemStack hand, InteractionHand which) {
        if (!(stand.level() instanceof ServerLevel) || !Hooks.listens(ArmorStandManipulateEvent.class)) return false;
        ItemStack worn = stand.getItemBySlot(slot);
        if (!swaps(disabledSlots, slot, worn, hand, player.hasInfiniteMaterials())) return false;
        return Hooks.post(new ArmorStandManipulateEvent(stand, slot, which,
                hand.isEmpty() ? ItemStack.EMPTY : hand.copyWithCount(1), worn,
                EntityActors.of(player))).isCancelled();
    }

    /**
     * Whether {@code ArmorStand.swapItem} will change anything, by its own test: a locked slot refuses taking or
     * putting, and a stack of more than one goes on only into an empty slot.
     */
    static boolean swaps(int disabledSlots, EquipmentSlot slot, ItemStack worn, ItemStack hand, boolean creative) {
        if (!worn.isEmpty() && (disabledSlots & 1 << slot.getFilterFlag() + 8) != 0) return false;
        if (worn.isEmpty() && (disabledSlots & 1 << slot.getFilterFlag() + 16) != 0) return false;
        if (creative && worn.isEmpty() && !hand.isEmpty()) return true;
        if (hand.isEmpty() || hand.getCount() <= 1) return true;
        return worn.isEmpty();
    }
}
