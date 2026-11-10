package dev.iredsc.hadron.test;

import dev.iredsc.hadron.api.economy.Currency;
import dev.iredsc.hadron.api.economy.Economy;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a {@link HadronTest} gets: its own world, players, time, and assertions. Everything runs on the server thread,
 * so the test can touch any Minecraft state directly; the server only moves on when the test spends ticks.
 *
 * <p><b>The world</b> ({@link #level()}) is a void world made for this test and deleted after it. A bedrock floor
 * fills {@code pos(-8, -1, -8)} to {@code pos(8, -1, 8)}, so players have something to stand on: {@code pos(0, 0, 0)} is
 * the air just above its centre, where players arrive.
 *
 * <p><b>Time</b> moves only when asked: {@link #tick} runs server ticks, {@link #await} runs them until something
 * happens. Player actions return once the server has handled them, without spending a tick, as packets are handled
 * between ticks.
 *
 * <p><b>Assertions</b> throw {@link AssertionError} with what was expected, what was there, and what usually causes the
 * difference; the report adds the line in the test.
 */
public interface TestContext {
    /** The plugin under test, enabled, as its {@code onEnable} left it. */
    JavaPlugin plugin();

    MinecraftServer server();

    /** This test's own void world. */
    ServerLevel level();

    /** A position in this test's world, relative to its centre: {@code pos(0, 0, 0)} is where players arrive. */
    BlockPos pos(int x, int y, int z);

    /**
     * The player with this name, joining them first if they aren't online yet: a real login, configuration and join,
     * firing {@code PlayerLoginEvent} and {@code PlayerJoinEvent} as for anyone, then a teleport to {@code pos(0, 0, 0)}.
     * The same name returns the same player for the rest of the test. They start fresh: an empty inventory, no
     * balance, survival mode, not op.
     *
     * @throws AssertionError if the server refused them (a full server, a ban, a cancelled PlayerLoginEvent): the
     *     message has the reason they were given
     */
    TestPlayer player(String name);

    /**
     * The player with this name, joining them first as a NeoForge client with the same mods as the server: in the
     * configuration phase their client claims every network channel the server's mods registered, as a client with
     * the same mod list does, so they can {@link TestPlayer#sendModPacket send mod packets}. Otherwise as
     * {@link #player}; {@code player} joins an unmodded client, as most tests want.
     *
     * @throws AssertionError if they already joined this test as an unmodded client, or the server refused them
     */
    TestPlayer moddedPlayer(String name);

    /**
     * Every {@link TestModPacket} the test mod's handler ran for during this test, oldest first. A packet a listener
     * cancelled never reaches it.
     */
    List<TestModPacket.Handled> modPacketsHandled();

    /** Every player this test joined, in the order they joined. */
    List<TestPlayer> players();

    /**
     * Registers the listener's {@code @EventHandler} methods for this test only. An anonymous class works:
     * {@code t.registerEvents(new Listener() { @EventHandler void on(BlockBreakEvent e) { e.setCancelled(true); } });}
     */
    void registerEvents(Listener listener);

    /**
     * A bridge's claim, held by the test harness on its own empty mod {@code hadron_test_target}, for firing events
     * as a bridge does: {@code t.bridge().fire(new EntityDataChangeEvent(...))}, with data kinds in
     * {@code hadron_test_target}'s namespace. Lets a plugin's tests check how it handles a bridge's events.
     */
    dev.iredsc.hadron.api.bridge.Bridge bridge();

    /**
     * Protects, logs and records the box in this test's world, both corners included, for the bridge acceptance test
     * ({@link Acceptance}). It starts trusting nobody, recorded as it is now.
     */
    Acceptance acceptance(net.minecraft.world.level.levelgen.structure.BoundingBox box);

    /** Runs {@code ticks} server ticks: worlds, entities, scheduled tasks, plugins' timers. */
    void tick(int ticks);

    /** How many ticks this test has spent so far. */
    int ticksSpent();

    /** One server tick. */
    default void tick() {
        tick(1);
    }

    /**
     * Ticks until the condition holds, checking it before each tick; returns at once if it already holds.
     *
     * @throws AssertionError if it still doesn't hold after {@code ticks} ticks
     */
    default void await(BooleanSupplier condition, int ticks) {
        await("the condition", condition, ticks);
    }

    /** {@link #await(BooleanSupplier, int)}, naming what it waits for in the failure: {@code "the door to open"}. */
    default void await(String what, BooleanSupplier condition, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (condition.getAsBoolean()) return;
            tick(1);
        }
        if (condition.getAsBoolean()) return;
        throw new AssertionError("Waited " + ticks + " ticks for " + what + ", and it never happened. If it needs "
                + "longer, raise the ticks given to await; if it should have happened, check what triggers it ran.");
    }

    /**
     * {@link #await(String, BooleanSupplier, int)} for work done on another thread: an async task, a chunk loading on
     * the chunk workers, a network thread. Ticks alone run faster than that work, so between ticks this also gives
     * other threads up to {@value #ASYNC_YIELD_MILLIS} ms, returning as soon as the condition holds. The limit is
     * still {@code ticks}, so a test can't hang. When only ticks move the condition, use {@code await}: it spends no
     * wall time.
     *
     * <pre>{@code
     * scheduler.runTaskAsynchronously(t.plugin(), () -> done.set(true));
     * t.awaitAsync("the async task to finish", done::get, 100);
     * }</pre>
     *
     * @throws AssertionError if it still doesn't hold after {@code ticks} ticks
     */
    void awaitAsync(String what, BooleanSupplier condition, int ticks);

    /** How long {@link #awaitAsync} gives other threads between two ticks, at most. */
    int ASYNC_YIELD_MILLIS = 10;

    // ---- Setting up ----

    /** Sets a block, as {@code level.setBlock} with block updates. */
    default void setBlock(BlockPos pos, BlockState state) {
        level().setBlock(pos, state, Block.UPDATE_ALL);
    }

    default void setBlock(BlockPos pos, Block block) {
        setBlock(pos, block.defaultBlockState());
    }

    default BlockState getBlock(BlockPos pos) {
        return level().getBlockState(pos);
    }

    /** {@code pos(1, 0, 2)} for a position in this test's world; plain coordinates otherwise. */
    String describe(BlockPos pos);

    // ---- Assertions ----

    default void assertBlock(BlockPos pos, Block expected) {
        Block actual = getBlock(pos).getBlock();
        if (actual != expected) {
            throw new AssertionError("Expected " + BuiltInRegistries.BLOCK.getKey(expected) + " at " + describe(pos)
                    + ", found " + BuiltInRegistries.BLOCK.getKey(actual) + ". If something should have stopped or "
                    + "made this change, check that its listener is registered and that the action reached it.");
        }
    }

    default void assertBlock(BlockPos pos, BlockState expected) {
        BlockState actual = getBlock(pos);
        if (!actual.equals(expected)) {
            throw new AssertionError("Expected " + expected + " at " + describe(pos) + ", found " + actual
                    + ". Compare the properties in brackets; assertBlock(pos, Block) ignores them.");
        }
    }

    /** That the selected hotbar slot holds exactly {@code count} of the item; count 0 means empty-handed. */
    default void assertHolding(TestPlayer player, Item item, int count) {
        ItemStack held = player.player().getMainHandItem();
        int actual = held.is(item) ? held.getCount() : 0;
        if (actual != count || (count == 0 && !held.isEmpty())) {
            throw new AssertionError(player.name() + " should hold " + count + " " + BuiltInRegistries.ITEM.getKey(item)
                    + ", but holds " + describe(held) + ". Something took, gave or swapped the item; selectSlot "
                    + "changes which slot counts as held.");
        }
    }

    /** That their whole inventory (hotbar, main, armour, offhand) holds {@code count} of the item in total. */
    default void assertInventory(TestPlayer player, Item item, int count) {
        int actual = player.player().getInventory().countItem(item);
        if (actual != count) {
            throw new AssertionError(player.name() + " should have " + count + " " + BuiltInRegistries.ITEM.getKey(item)
                    + " in their inventory, but has " + actual + ". If it should have been picked up, tick a few "
                    + "times first: item entities take ticks to reach a player.");
        }
    }

    /** That the player's balance in the default currency is exactly {@code amount}, such as {@code "12.50"}. */
    default void assertBalance(TestPlayer player, String amount) {
        assertBalance(player, Economy.get().getDefaultCurrency().name(), amount);
    }

    /** That the player's balance in the named currency ({@code "money"}, {@code "gems"}) is exactly {@code amount}. */
    default void assertBalance(TestPlayer player, String currency, String amount) {
        Economy economy = Economy.get();
        Currency c = economy.getCurrency(currency).orElseThrow(() -> new AssertionError("No currency named "
                + currency + " is registered; registered: " + economy.getCurrencies().stream().map(Currency::name)
                .toList() + ". A plugin registers its currency in onLoad with Economy.registerCurrency."));
        BigDecimal expected = new BigDecimal(amount);
        BigDecimal actual = economy.getBalance(player.uuid(), c);
        if (actual.compareTo(expected) != 0) {
            throw new AssertionError(player.name() + " should have " + expected.toPlainString() + " " + c.name()
                    + ", but has " + actual.toPlainString() + ". A refused change (a cancelled resource event, too "
                    + "little money) leaves the balance as it was; check the EconomyResult the change returned.");
        }
    }

    /** That some chat line they received contains {@code text}. */
    default void assertChat(TestPlayer player, String text) {
        List<String> lines = player.chat();
        for (String line : lines) if (line.contains(text)) return;
        throw new AssertionError(player.name() + " should have received a chat line containing \"" + text + "\", but "
                + (lines.isEmpty() ? "received no chat at all" : "received only " + quoted(lines)) + ". A cancelled "
                + "chat event or a message sent to someone else would do this.");
    }

    /** That no chat line they received contains {@code text}. */
    default void assertNoChat(TestPlayer player, String text) {
        for (String line : player.chat()) {
            if (line.contains(text)) {
                throw new AssertionError(player.name() + " should not have received \"" + text + "\", but got the "
                        + "line \"" + line + "\". If a listener should have cancelled or changed it, check that it "
                        + "is registered and matches this message.");
            }
        }
    }

    /** That the server sent {@code viewer} this entity (its add-entity packet) since they joined or were cleared. */
    default void assertSent(TestPlayer viewer, Entity entity) {
        if (!viewer.wasSent(entity)) {
            throw new AssertionError(viewer.name() + " should have been sent " + entity.getName().getString() + " ("
                    + entity.getType().builtInRegistryHolder().key().location() + "), but never was. An entity is sent "
                    + "once it is in the viewer's tracking range and visible to them; tick once after moving either.");
        }
    }

    /** That the server never sent {@code viewer} this entity since they joined or were cleared. */
    default void assertNotSent(TestPlayer viewer, Entity entity) {
        if (viewer.wasSent(entity)) {
            throw new AssertionError(viewer.name() + " should never have been sent " + entity.getName().getString()
                    + ", but was. If it should be hidden from them, hide it before it comes into their range, and "
                    + "clearReceived() after a hide to check only what comes next.");
        }
    }

    /** That {@code viewer}'s tab list has {@code player} now. */
    default void assertInTabList(TestPlayer viewer, ServerPlayer player) {
        if (!viewer.inTabList(player.getUUID())) {
            throw new AssertionError(viewer.name() + "'s tab list should have " + player.getGameProfile().getName()
                    + ", but doesn't. Hidden players are taken out of the tab list.");
        }
    }

    default void assertNotInTabList(TestPlayer viewer, ServerPlayer player) {
        if (viewer.inTabList(player.getUUID())) {
            throw new AssertionError(viewer.name() + "'s tab list should not have "
                    + player.getGameProfile().getName() + ", but does.");
        }
    }

    default void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    default void assertFalse(boolean condition, String message) {
        if (condition) throw new AssertionError(message);
    }

    /** That {@code actual} equals {@code expected}; {@code what} names it in the failure: {@code "pickaxe damage"}. */
    default void assertEquals(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + what + " to be " + expected + ", but it was " + actual + ".");
        }
    }

    default void fail(String message) {
        throw new AssertionError(message);
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty() ? "nothing" : stack.getCount() + " " + BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private static String quoted(List<String> lines) {
        int from = Math.max(0, lines.size() - 10);
        StringBuilder out = new StringBuilder();
        if (from > 0) out.append("(last 10 of ").append(lines.size()).append(") ");
        for (int i = from; i < lines.size(); i++) {
            if (i > from) out.append(", ");
            out.append('"').append(lines.get(i)).append('"');
        }
        return out.toString();
    }
}
