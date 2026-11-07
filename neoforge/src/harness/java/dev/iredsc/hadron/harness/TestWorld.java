package dev.iredsc.hadron.harness;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.economy.Currency;
import dev.iredsc.hadron.api.economy.Economy;
import dev.iredsc.hadron.api.economy.EconomyResult;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.net.Visibility;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.world.WorldCreator;
import dev.iredsc.hadron.api.world.WorldGenerator;
import dev.iredsc.hadron.api.world.Worlds;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestModPacket;
import dev.iredsc.hadron.test.TestPlayer;
import io.netty.channel.Channel;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/**
 * One test's {@link TestContext}: its world, its players, its listeners, and the clock. Everything runs on the server
 * thread inside one task of the server's loop, so a {@link #tick} here is a whole server tick run from the test, and
 * the loop itself waits until the test returns.
 */
final class TestWorld implements TestContext {
    /** Where {@code pos(0, 0, 0)} is in the test's world. */
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    private static final int FLOOR_RADIUS = 8;
    private static final int JOIN_TICKS = 200;
    /** Rounds of passing packets back and forth before settling gives up on traffic stopping. */
    /** Rounds of packets or server tasks settling lets through before it fails the test. */
    private static final int SETTLE_ROUNDS = 100;
    /** Rounds that only load chunks, after which settling stops waiting for them. */
    private static final int CHUNK_ROUNDS = 1_000;
    /** How long settling waits for a connection's network thread before it fails the test. */
    private static final int FENCE_SECONDS = 10;

    private final MinecraftServer server;
    private final JavaPlugin plugin;
    private final @Nullable SocketAddress address;
    private final String test;
    private final int timeout;
    private final ResourceLocation key;
    private final ServerLevel level;
    private final Map<String, TestClient> players = new LinkedHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private int spent;
    private boolean ticking;

    TestWorld(MinecraftServer server, JavaPlugin plugin, @Nullable SocketAddress address, String test, int index, int timeout) {
        this.server = server;
        this.plugin = plugin;
        this.address = address;
        this.test = test;
        this.timeout = timeout;
        this.key = ResourceLocation.fromNamespaceAndPath("hadrontest", "test" + index);
        TestMod.forget();
        this.level = Worlds.create(new WorldCreator(key).generator(WorldGenerator.voidWorld()).spawn(ORIGIN)
                .keepSpawnLoaded(true));
        for (int x = -FLOOR_RADIUS; x <= FLOOR_RADIUS; x++) {
            for (int z = -FLOOR_RADIUS; z <= FLOOR_RADIUS; z++) {
                level.setBlock(ORIGIN.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        // The test's area stays loaded and ticking with or without a player in it, as a forceloaded area does, so
        // machines, item entities and mobs run in a test that never joins anyone.
        int cx = ORIGIN.getX() >> 4, cz = ORIGIN.getZ() >> 4;
        for (int x = -FORCED_RADIUS; x <= FORCED_RADIUS; x++) {
            for (int z = -FORCED_RADIUS; z <= FORCED_RADIUS; z++) level.setChunkForced(cx + x, cz + z, true);
        }
    }

    /** Chunks around the origin kept loaded and entity-ticking for the whole test: a 5 by 5 chunk area. */
    private static final int FORCED_RADIUS = 2;

    /**
     * Before the test's first line: waits, outside its tick limit, until the test's area ticks entities and its
     * entities are loaded. Chunk loading runs on worker threads that server ticks alone don't wait for, so without
     * this a test acting at once on a busy machine could find nothing there, and pass or fail by luck.
     */
    void awaitReady() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        long chunk = net.minecraft.world.level.ChunkPos.asLong(ORIGIN.getX() >> 4, ORIGIN.getZ() >> 4);
        while (!(level.isPositionEntityTicking(ORIGIN) && level.areEntitiesLoaded(chunk))) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The test world's area didn't start ticking entities within 30 s; "
                        + "the server is too busy or its chunk loading is stuck. Check the server log.");
            }
            startBudget();
            server.tickServer(TestWorld::shortBudget);
            // Chunk loading finishes in tasks the server runs while it waits between ticks; run them here.
            int ran = 0;
            while (ran < 100_000 && server.pollTask()) ran++;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Each harness tick lets the server spend a moment on the work it does when it has time to spare (unloading
     * chunks, saving), as a real server's idle part of the tick would, so chunks unload in tests too.
     */
    @StaticState(reason = "the end of the current harness tick's spare time; each tick sets it afresh")
    private static long budgetEnd;

    private static boolean shortBudget() {
        return System.nanoTime() < budgetEnd;
    }

    /** Starts a tick's 2 ms of spare time. */
    private static void startBudget() {
        budgetEnd = System.nanoTime() + 2_000_000L;
    }

    // ---- TestContext ----

    @Override
    public JavaPlugin plugin() {
        return plugin;
    }

    @Override
    public MinecraftServer server() {
        return server;
    }

    @Override
    public ServerLevel level() {
        return level;
    }

    @Override
    public BlockPos pos(int x, int y, int z) {
        return ORIGIN.offset(x, y, z);
    }

    @Override
    public String describe(BlockPos pos) {
        BlockPos rel = pos.subtract(ORIGIN);
        return "pos(" + rel.getX() + ", " + rel.getY() + ", " + rel.getZ() + ")";
    }

    @Override
    public TestPlayer player(String name) {
        return join(name, false);
    }

    @Override
    public TestPlayer moddedPlayer(String name) {
        return join(name, true);
    }

    @Override
    public List<TestModPacket.Handled> modPacketsHandled() {
        return TestMod.handled();
    }

    private TestPlayer join(String name, boolean modded) {
        TestClient existing = players.get(name);
        if (existing != null) {
            if (existing.modded() == modded) return existing;
            throw new AssertionError(name + " already joined this test as " + (existing.modded()
                    ? "a modded client, with t.moddedPlayer" : "an unmodded client, with t.player")
                    + "; a client can't change its mods while online. Use t." + (existing.modded() ? "moddedPlayer"
                    : "player") + "(\"" + name + "\") throughout the test, or another name.");
        }
        requireIdle("join a player");
        if (!net.minecraft.util.StringUtil.isValidPlayerName(name)) {
            throw new AssertionError("\"" + name + "\" isn't a valid player name: use 3 to 16 letters, digits or _, "
                    + "like \"alice\".");
        }
        TestClient client = TestClient.connect(server, this, address, name, modded);
        players.put(name, client);
        ServerPlayer joined = null;
        for (int waited = 0; joined == null; waited++) {
            settle();
            ServerPlayer online = server.getPlayerList().getPlayer(client.uuid());
            if (online != null && client.phase() == TestClient.Phase.PLAY) joined = online;
            else if (client.phase() == TestClient.Phase.GONE) {
                players.remove(name);
                throw new AssertionError(name + " couldn't join: the server disconnected them with \""
                        + client.disconnectReason() + "\". A listener cancelling PlayerLoginEvent, a ban or a full "
                        + "server does this.");
            } else if (waited >= JOIN_TICKS) {
                throw new AssertionError(name + " was still in the " + client.phase().name().toLowerCase()
                        + " phase of joining after " + JOIN_TICKS + " ticks; a mod's configuration task may be "
                        + "waiting for an answer only a modded client gives. Last packets: " + lastPackets(client));
            } else {
                tick(1);
            }
        }
        client.joined(joined);
        joined.teleportTo(level, ORIGIN.getX() + 0.5, ORIGIN.getY(), ORIGIN.getZ() + 0.5, 0, 0);
        settle();
        // The server pairs a player with the entities around them when their client next reports its position
        // after the chunks arrive (a still client does every 20 ticks). Report it as soon as they're in, so
        // everyone nearby is sent to them before the test's first line.
        for (int i = 0; i < JOIN_TICKS && joined.connection.chunkSender.isPending(joined.chunkPosition().toLong()); i++) {
            tick(1);
        }
        if (joined.connection.chunkSender.isPending(joined.chunkPosition().toLong())) {
            throw new AssertionError(name + "'s own chunk still hadn't been sent " + JOIN_TICKS + " ticks after they "
                    + "joined, so the entities around them can't have been sent either, and a test of what they see "
                    + "would read too early. Something holds back chunk sending: a listener cancelling chunk "
                    + "packets, or a very slow chunk load. Last packets: " + lastPackets(client));
        }
        client.reportPosition();
        settle();
        return client;
    }

    @Override
    public List<TestPlayer> players() {
        return List.copyOf(players.values());
    }

    @Override
    public void registerEvents(Listener listener) {
        requireIdle("register listeners");
        Hadron.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    @Override
    public void tick(int ticks) {
        requireIdle("tick");
        for (int i = 0; i < ticks; i++) {
            if (spent >= timeout) throw new TestTimeout(test, timeout);
            spent++;
            ticking = true;
            try {
                startBudget();
                server.tickServer(TestWorld::shortBudget);
            } finally {
                ticking = false;
            }
            for (TestClient c : players.values()) c.clientTick();
            settle();
        }
    }

    @Override
    public int ticksSpent() {
        return spent;
    }

    // The wall clock lives here, in the harness, so a plugin's test can wait on another thread without reading it.
    @Override
    public void awaitAsync(String what, BooleanSupplier condition, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (holdsWithin(condition, TimeUnit.MILLISECONDS.toNanos(ASYNC_YIELD_MILLIS))) return;
            tick(1);
        }
        if (holdsWithin(condition, TimeUnit.MILLISECONDS.toNanos(ASYNC_YIELD_MILLIS))) return;
        throw new AssertionError("Waited " + ticks + " ticks, with up to " + ASYNC_YIELD_MILLIS + " ms between each "
                + "for other threads, for " + what + ", and it never happened. Check that the work was started (the "
                + "task scheduled, the chunk forced) and that it didn't throw: an async task's exception is in the "
                + "server log. If it needs longer, raise the ticks given to awaitAsync.");
    }

    /** Whether the condition holds now or within {@code nanos}, checked about every quarter millisecond. */
    private static boolean holdsWithin(BooleanSupplier condition, long nanos) {
        long until = System.nanoTime() + nanos;
        while (true) {
            if (condition.getAsBoolean()) return true;
            if (System.nanoTime() - until >= 0) return false;
            LockSupport.parkNanos(250_000);
        }
    }

    // ---- For the harness ----

    /**
     * Refuses a step taken from inside a tick (a listener, a scheduled task): ticking or handling packets there would
     * run the server inside itself.
     */
    void requireIdle(String what) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Tests " + what + " on the server thread, where the test method runs; "
                    + "this was called from " + Thread.currentThread().getName());
        }
        if (ticking) {
            throw new IllegalStateException("Can't " + what + " from inside a server tick (a listener or scheduled "
                    + "task the test set up); do it from the test method itself, after the tick that triggers it");
        }
    }

    /**
     * Lets every packet in flight arrive and be handled, both ways, without running a tick: what the server would do
     * between two ticks. Clients answer on their connection threads and the server handles packets as queued tasks,
     * so this passes the turn back and forth until neither side has anything left.
     */
    void settle() {
        int busy = 0;
        for (int round = 0; ; round++) {
            long before = activity();
            for (TestClient c : players.values()) c.connection().tick();
            fence();
            // The server runs its own queue (packets, tasks) before any chunk loading, so a task that ran while that
            // queue held one was the queue's. Chunk loading finishes by itself and is no effect of the test's.
            int ran = 0;
            int queued = 0;
            while (ran < 100_000) {
                boolean fromQueue = server.getPendingTasksCount() > 0;
                if (!server.pollTask()) break;
                ran++;
                if (fromQueue) queued++;
            }
            fence();
            for (TestClient c : players.values()) {
                Throwable failure = c.failure();
                if (failure != null) {
                    c.clearFailure();
                    throw new IllegalStateException(failure.getMessage(), failure.getCause());
                }
            }
            boolean moved = activity() != before;
            if (ran == 0 && !moved) return;
            if (!moved && queued == 0) {
                // Only chunk loading, as between two real ticks: let it run, but it never holds the test up for long.
                if (round >= CHUNK_ROUNDS) return;
                continue;
            }
            if (++busy >= SETTLE_ROUNDS) {
                // Returning now would let the next line (assertNoChat, assertNotSent) pass before what's pending
                // arrives.
                throw new IllegalStateException("Packets or server tasks were still arriving after " + SETTLE_ROUNDS
                        + " rounds of letting them through without a tick, so the harness can't tell when this "
                        + "action's effects have all arrived. Something keeps making work without the server ticking: "
                        + "a task that re-queues itself, or a listener that answers every packet with another. Make it "
                        + "wait for the next tick (a scheduled task), or stop it before the test acts.");
            }
        }
    }

    /** Waits until every connection's thread has done what was queued on it before now. */
    private void fence() {
        List<Channel> channels = new ArrayList<>();
        List<Connection> serverSide = server.getConnection().getConnections();
        synchronized (serverSide) {
            for (Connection c : serverSide) if (c.channel() != null) channels.add(c.channel());
        }
        for (TestClient c : players.values()) if (c.connection().channel() != null) channels.add(c.connection().channel());
        for (int pass = 0; pass < 2; pass++) {
            for (Channel channel : channels) {
                if (!channel.eventLoop().submit(() -> {}).awaitUninterruptibly(FENCE_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("A connection's network thread didn't answer within "
                            + FENCE_SECONDS + " s, so packets it carries may still be on their way and this action's "
                            + "effects can't be known to have arrived. Something blocks it: a listener or task "
                            + "sleeping or waiting on a network thread. Do that work on the server thread instead.");
                }
            }
        }
    }

    private long activity() {
        long sum = 0;
        for (TestClient c : players.values()) sum += c.activity.get();
        return sum;
    }

    /**
     * Ends the test: its listeners off, its players gone with nothing of theirs left for the next test (inventory,
     * balance, op, hidden state), its world deleted. Every step runs even if one fails; the failures come back as one.
     */
    void close() {
        List<String> problems = new ArrayList<>();
        for (Listener l : listeners) {
            try {
                Hadron.getPluginManager().unregisterEvents(l);
            } catch (RuntimeException e) {
                problems.add("removing a listener: " + e);
            }
        }
        listeners.clear();
        for (TestClient c : players.values()) {
            ServerPlayer p = server.getPlayerList().getPlayer(c.uuid());
            if (p == null) continue;
            try {
                // Hidden state is kept by UUID, so it would follow the name into the next test.
                Visibility.showToAll(plugin, p);
                server.getPlayerList().deop(p.getGameProfile());
                c.kick("The test ended");
            } catch (RuntimeException e) {
                problems.add("disconnecting " + c.name() + ": " + e);
            }
        }
        try {
            settle();
            // A kicked player leaves when the server next ticks its connections and finds theirs closed; do just
            // that, so the test's last tick count stays the test's own.
            server.getConnection().tick();
            settle();
        } catch (RuntimeException e) {
            problems.add("letting the last packets arrive: " + e);
        }
        for (TestClient c : players.values()) {
            if (server.getPlayerList().getPlayer(c.uuid()) != null) {
                problems.add(c.name() + " is still online after being disconnected");
            }
            resetBalances(c, problems);
            forget(c.uuid(), problems);
        }
        TestMod.forget();
        try {
            Worlds.delete(key);
        } catch (RuntimeException e) {
            problems.add("deleting its world " + key + ": " + e);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Cleaning up after the test failed, so later tests may see its leftovers: "
                    + String.join("; ", problems));
        }
    }

    /** Players' balances outlive them, so the next test's "alice" would start rich. */
    private static void resetBalances(TestClient c, List<String> problems) {
        try {
            Economy economy = Economy.get();
            for (Currency currency : economy.getCurrencies()) {
                BigDecimal balance = economy.getBalance(c.uuid(), currency);
                if (balance.signum() <= 0) continue;
                EconomyResult result = economy.withdraw(c.uuid(), currency, balance);
                if (!(result instanceof EconomyResult.Done)) {
                    problems.add("resetting " + c.name() + "'s " + currency.name() + ": " + result.message());
                }
            }
        } catch (RuntimeException e) {
            problems.add("resetting " + c.name() + "'s balances: " + e);
        }
    }

    /** Their saved inventory, stats and advancements, so the next test's player of that name starts fresh. */
    private void forget(UUID id, List<String> problems) {
        for (LevelResource dir : List.of(LevelResource.PLAYER_DATA_DIR, LevelResource.PLAYER_STATS_DIR,
                LevelResource.PLAYER_ADVANCEMENTS_DIR)) {
            Path folder = server.getWorldPath(dir);
            for (String file : List.of(id + ".dat", id + ".dat_old", id + ".json")) {
                try {
                    Files.deleteIfExists(folder.resolve(file));
                } catch (IOException e) {
                    problems.add("deleting " + folder.resolve(file) + ": " + e);
                }
            }
        }
    }

    private static String lastPackets(TestClient c) {
        List<String> names = new ArrayList<>();
        var packets = c.received();
        for (int i = Math.max(0, packets.size() - 5); i < packets.size(); i++) {
            names.add(packets.get(i).getClass().getSimpleName());
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }
}
