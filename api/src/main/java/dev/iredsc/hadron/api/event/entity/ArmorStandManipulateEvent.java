package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * A player is about to take an item off an armor stand, put one on, or swap the two. Bukkit's
 * {@code PlayerArmorStandManipulateEvent}. Fired only when the swap would happen, so a click on a locked slot fires
 * nothing. Cancel it and both the stand and the player's hand stay as they were.
 *
 * <p><b>Core fires it for right-clicks</b>, by players and by fake players (a deployer). A dispenser putting armor on
 * a stand is an {@code EquipEvent}. A mod that edits a stand's items in its own code needs its bridge to fire this.
 */
@FiredBy(core = PARTIAL,
        coreSees = "right-clicking a stand, by players and fake players",
        bridges = "the mod moving items on or off a stand in its own code (an editing screen)")
public class ArmorStandManipulateEvent extends EntityEvent implements Cancellable {
    private final EquipmentSlot slot;
    private final InteractionHand hand;
    private final ItemStack playerItem;
    private final ItemStack standItem;
    private boolean cancelled;

    public ArmorStandManipulateEvent(ArmorStand stand, EquipmentSlot slot, InteractionHand hand, ItemStack playerItem,
            ItemStack standItem, Actor actor) {
        super(stand, actor);
        this.slot = Objects.requireNonNull(slot, "slot");
        this.hand = Objects.requireNonNull(hand, "hand");
        this.playerItem = Objects.requireNonNull(playerItem, "playerItem; use ItemStack.EMPTY for an empty hand")
                .copy();
        this.standItem = Objects.requireNonNull(standItem, "standItem; use ItemStack.EMPTY for an empty slot").copy();
    }

    @Override
    public ArmorStand getEntity() {
        return (ArmorStand) super.getEntity();
    }

    /** The stand's slot that changes. */
    public EquipmentSlot getSlot() {
        return slot;
    }

    public InteractionHand getHand() {
        return hand;
    }

    /** What the player holds and will put on the stand; empty when they only take. A copy. */
    public ItemStack getPlayerItem() {
        return playerItem.copy();
    }

    /** What the stand has in the slot and the player will take; empty when they only put. A copy. */
    public ItemStack getArmorStandItem() {
        return standItem.copy();
    }

    /**
     * The player behind it, if online: directly, or the owner of the fake player that did. Same as
     * {@link #getActingPlayer()}.
     */
    public Optional<ServerPlayer> getPlayer() {
        return getActingPlayer();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
