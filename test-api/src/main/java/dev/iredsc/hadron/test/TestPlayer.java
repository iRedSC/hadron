package dev.iredsc.hadron.test;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/**
 * A real player in a test: a {@link ServerPlayer} that logged in through the server's own login path over an
 * in-memory connection, so Hadron sees them as {@code Actor.Player}, and every plugin and mod sees an ordinary player.
 * Get one with {@link TestContext#player}.
 *
 * <p><b>Actions</b> are the packets a client sends, handled by the server's own packet code, so they fire the same
 * events, checks and listeners a real player's would. Each returns once the server has handled it and everything it
 * sent back has arrived, so the next line can check the result.
 *
 * <p><b>Observations</b> are what the server sent this player: every packet, the chat lines, which entities and tab
 * list entries their client has.
 */
public interface TestPlayer {
    /** The server's player, for anything not covered here: inventory, health, position, game mode. */
    ServerPlayer player();

    String name();

    UUID uuid();

    // ---- Setting up ----

    /** Adds the stack to their inventory, as a plugin's {@code getInventory().add} would. */
    void give(ItemStack stack);

    /** Puts the stack in their selected hotbar slot, replacing what was there. */
    void hold(ItemStack stack);

    /** Makes them a server operator (level 4) or not, for commands that need it. */
    void setOp(boolean op);

    // ---- Actions, as the packets a client sends ----

    /**
     * Breaks the block the way a client does: starts digging, holds for as many ticks as the block takes with what
     * they hold (instant in creative), then stops. Spends those ticks.
     *
     * @throws AssertionError if the block is out of their reach; move them closer first
     */
    void breakBlock(BlockPos pos);

    /** Starts digging the block: the left click going down. */
    void startDigging(BlockPos pos);

    /** Finishes digging the block: the client saying it's broken. The server refuses it if too few ticks passed. */
    void stopDigging(BlockPos pos);

    /** Lets go of the left click before the block breaks. */
    void abortDigging(BlockPos pos);

    /** Right-clicks the top of the block with what they hold: opens a chest, presses a button, uses an item on it. */
    void useBlock(BlockPos pos);

    /** Right-clicks that face of the block. */
    void useBlock(BlockPos pos, Direction face);

    /**
     * Places the block they hold at {@code pos}, by right-clicking the face of a solid neighbour that touches it, as a
     * player does.
     *
     * @throws AssertionError if no neighbour of {@code pos} is a block to place against
     */
    void placeBlock(BlockPos pos);

    /** Right-clicks the air with what they hold: eats, throws, drinks, draws a bow. */
    void useItem();

    /** Left-clicks the entity: a hit with what they hold. */
    void attack(Entity target);

    /** Right-clicks the entity: trades with a villager, mounts a horse, leashes a mob. */
    void interact(Entity target);

    /**
     * Clicks a slot of the menu they have open (their own inventory if none), as the client's
     * container-click packet: {@code button} 0 is left and 1 is right for {@link ClickType#PICKUP}; for
     * {@link ClickType#SWAP} it is the hotbar key 0 to 8.
     */
    void clickSlot(int slot, int button, ClickType type);

    /** Left-clicks a slot: picks up or puts down. */
    void clickSlot(int slot);

    /** Drops one item from their hand (the Q key). */
    void drop();

    /** Drops the whole stack in their hand (Ctrl+Q). */
    void dropStack();

    /** Says something in chat. Unsigned, as from an offline-mode client. */
    void chat(String message);

    /** Runs a command as they would type it; the leading {@code /} is optional. */
    void command(String command);

    /** Moves them with the client's move packet; the server checks it as it would a real client's. */
    void moveTo(double x, double y, double z);

    /** Moves them to stand on top of the block at {@code pos}'s position (its bottom centre). */
    void moveTo(BlockPos pos);

    /** Teleports them on the server, as a plugin or command does; their client confirms it, as a real one would. */
    void teleport(double x, double y, double z);

    /** Picks a hotbar slot, 0 to 8. */
    void selectSlot(int slot);

    /**
     * Sends a mod's network message to the server, as the mod's client side would: the server decodes it and hands it
     * to the mod's handler, firing {@code PlayerModPacketEvent} first. Use {@link TestModPacket} for a mod of your
     * own, or a real mod's payload record.
     *
     * @throws AssertionError if they joined as an unmodded client (join them with {@code t.moddedPlayer}), or no mod
     *     on the server registered that payload as one a client sends in play
     */
    void sendModPacket(CustomPacketPayload payload);

    /** Leaves the server, as closing the game does. The test ends any player still online for you. */
    void disconnect();

    // ---- Observations: what the server sent them ----

    /** Every packet the server sent them since they joined (or since {@link #clearReceived}), in order. */
    List<Packet<?>> received();

    /** The packets of one type, such as {@code ClientboundSoundPacket.class}. */
    <P> List<P> received(Class<P> type);

    /** Every chat line they saw (player chat, system messages, command feedback), as plain text, in order. */
    List<String> chat();

    /** Whether the server ever sent them this entity (its add-entity packet) since they joined or were cleared. */
    boolean wasSent(Entity entity);

    /** Whether their client has the entity now: sent, and not removed since. */
    boolean isShown(Entity entity);

    /** Whether their tab list has that player now. */
    boolean inTabList(UUID player);

    /** Forgets every packet and chat line received so far, so what comes next is easy to check. */
    void clearReceived();

    /** False after they left or were kicked. */
    boolean isConnected();

    /** Why they were disconnected, or empty while still online. */
    String disconnectReason();
}
