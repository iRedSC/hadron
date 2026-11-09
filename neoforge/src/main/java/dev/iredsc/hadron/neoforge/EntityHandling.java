package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.entity.ArmorStandManipulateEvent;
import dev.iredsc.hadron.api.event.entity.EntityLeashBreakEvent;
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

    /**
     * Runs {@code Leashable}'s one method that takes a lead off, and reports it as broken when it came off for a reason
     * nobody chose. The reason is what the caller said ({@link LeashDrop}), otherwise judged from the state before the
     * drop, while the holder and the entity are still as they were. A holder there means vanilla takes that lead off, so
     * it is reported even if something puts a new lead on during the drop (a listener on the dropped lead's spawn).
     * Never for an entity already removed (see {@link #reportsBreaks}). The caller's cause is taken here, so it covers
     * this lead only: one put on during the drop and dropped again is judged afresh.
     */
    public static void dropLeash(Entity entity, Runnable drop) {
        EntityState state = (EntityState) entity;
        LeashDrop cause = state.hadron$leashDrop();
        state.hadron$setLeashDrop(LeashDrop.UNSAID);
        if (!(entity instanceof Leashable leashable) || !reportsBreaks(entity)) {
            drop.run();
            return;
        }
        Entity holder = leashable.getLeashHolder();
        EntityLeashBreakEvent.@Nullable Reason reason = holder == null ? null
                : switch (cause) {
                    case CHOSEN -> null;
                    case LEAVING -> EntityLeashBreakEvent.Reason.ENTITY_GONE;
                    case UNSAID -> breakReason(entity, holder);
                };
        drop.run();
        if (reason != null) {
            Hooks.post(new EntityLeashBreakEvent(entity, holder, reason,
                    EntityActors.fromStack(entity, Actor.Nature.INSTANCE)));
        }
    }

    /** Runs a drop whose cause the caller knows better than the entity's state can show. */
    public static void dropLeashBecause(Entity entity, LeashDrop cause, Runnable drop) {
        EntityState state = (EntityState) entity;
        state.hadron$setLeashDrop(cause);
        try {
            drop.run();
        } finally {
            state.hadron$setLeashDrop(LeashDrop.UNSAID);
        }
    }

    /**
     * An entity back from a save or another dimension gave up waiting for its holder and dropped its lead; the holder
     * was never loaded, so there is none to name.
     */
    public static void leashHolderNotFound(Entity entity) {
        if (!reportsBreaks(entity)) return;
        Hooks.post(new EntityLeashBreakEvent(entity, null, EntityLeashBreakEvent.Reason.HOLDER_NOT_FOUND,
                EntityActors.fromStack(entity, Actor.Nature.INSTANCE)));
    }

    /**
     * Whether a lead coming off this entity is reported. Never once the entity is removed: a destroyed entity's lead was
     * reported as it was removed ({@link #leashedEntityRemoved}), even if a conversion ticks it again or its saved holder
     * turns up, and a portal's copy carries the lead on. Each lead is then reported at most once.
     */
    private static boolean reportsBreaks(Entity entity) {
        return entity.level() instanceof ServerLevel && !entity.isRemoved() && Hooks.listens(EntityLeashBreakEvent.class);
    }

    /** A lead on an entity about to be destroyed: who held it, if the holder ever loaded. */
    public record LostLead(@Nullable Entity holder) {}

    /**
     * The lead on an entity about to be destroyed for the first time, or null when there is nothing to report: not on
     * a lead, already removed, nobody listening, or a removal that keeps the lead (unloading saves it, a portal hands
     * it to the entity's copy). A lead still waiting for a saved holder counts, with no holder: the entity won't tick
     * again to give up on it.
     */
    public static @Nullable LostLead leadIfDestroyed(Entity entity, Entity.RemovalReason reason) {
        if (!reason.shouldDestroy() || entity.isRemoved() || !(entity instanceof Leashable leashable)
                || leashable.getLeashData() == null || !(entity.level() instanceof ServerLevel)
                || !Hooks.listens(EntityLeashBreakEvent.class)) {
            return null;
        }
        return new LostLead(leashable.getLeashHolder());
    }

    /**
     * A leashed entity was destroyed without dropping its lead first (a conversion, a discard); vanilla leaves the
     * leash data on the removed entity, so the entity being gone is what says the lead is.
     */
    public static void leashedEntityRemoved(Entity entity, LostLead lead) {
        Hooks.post(new EntityLeashBreakEvent(entity, lead.holder(), EntityLeashBreakEvent.Reason.ENTITY_GONE,
                EntityActors.fromStack(entity, Actor.Nature.INSTANCE)));
    }

    /**
     * Why a lead is coming off a live entity, judged from the state alone; null when none of the reasons holds: a
     * player or a mod taking it off on purpose. The order matters: a dead entity mounted on nothing is gone, not mounted.
     */
    static EntityLeashBreakEvent.@Nullable Reason breakReason(Entity entity, Entity holder) {
        if (!entity.isAlive()) return EntityLeashBreakEvent.Reason.ENTITY_GONE;
        if (holder.isRemoved() || !holder.isAlive()) return EntityLeashBreakEvent.Reason.HOLDER_GONE;
        if (entity.isPassenger()) return EntityLeashBreakEvent.Reason.MOUNTED;
        if (holder.level() == entity.level() && entity.distanceTo(holder) > Leashable.LEASH_TOO_FAR_DIST) {
            return EntityLeashBreakEvent.Reason.DISTANCE;
        }
        return null;
    }

    /** A leashed entity the player holds is about to be tied to the knot; true leaves it with the player. */
    public static boolean refuseTie(Leashable leashable, Entity knot, Player player) {
        if (!(leashable instanceof Entity entity) || !(entity.level() instanceof ServerLevel)
                || !Hooks.listens(EntityLeashEvent.class)) {
            return false;
        }
        return Hooks.post(new EntityLeashEvent(entity, knot, EntityActors.of(player))).isCancelled();
    }

    /** Whether any mob is tied to the knot, looked for the way the knot itself looks for them. */
    public static boolean holdsAny(LeashFenceKnotEntity knot) {
        return !LeadItem.leashableInArea(knot.level(), knot.getPos(), l -> l.getLeashHolder() == knot).isEmpty();
    }

    /** Whether a knot is already on the fence, found the way {@code LeashFenceKnotEntity.getOrCreateKnot} looks. */
    public static boolean hasKnot(Level level, BlockPos fence) {
        return !level.getEntitiesOfClass(LeashFenceKnotEntity.class, new AABB(fence).inflate(1),
                k -> k.getPos().equals(fence)).isEmpty();
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
