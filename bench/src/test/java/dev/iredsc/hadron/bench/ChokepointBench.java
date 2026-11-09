package dev.iredsc.hadron.bench;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.checks.Nondeterministic;
import dev.iredsc.hadron.neoforge.BlockHooks;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What Hadron adds to {@code Level.setBlock}, which runs millions of times a second on a busy modded server
 * (docs/bridges.md, "The chokepoint cost"). Times Hadron's hook alone and whole setBlocks, with nobody listening, with a
 * protection-style listener on every {@link BlockEvent} but no actor (an unbridged mod's queued write: today it fires
 * nothing), and with an actor on the stack (a bridged machine: one event per write).
 *
 * <p>The blocks swap between two stained glasses, which light and heightmaps treat alike, so no light work piles up
 * and a write costs what the block change itself costs. Each case counts what its listener heard and fails unless
 * that is exactly what the case means, so a timing never comes from a path that returned early.
 */
public final class ChokepointBench {
    private static final BlockState WHITE = Blocks.WHITE_STAINED_GLASS.defaultBlockState();
    private static final BlockState RED = Blocks.RED_STAINED_GLASS.defaultBlockState();
    private static final int SIDE = 16;
    private static final int WARMUP_ROUNDS = 3;
    private static final int ROUNDS = 7;
    private static final int HOOK_CALLS = 1_000_000;
    private static final int HOOK_EVENT_CALLS = 200_000;
    /** Whole passes over the grid, an even number of them, so every case starts and ends with the grid white. */
    private static final int WRITES = 200 * 2 * SIDE * SIDE;

    private final Actor machine = new Actor.Server("bench machine");
    private int heard;

    private final class Protection implements Listener {
        @EventHandler
        private void any(BlockEvent event) {
            heard++;
        }
    }

    @HadronTest
    void setBlockChokepoint(TestContext t) {
        ServerLevel level = t.level();
        BlockPos[] grid = new BlockPos[SIDE * SIDE];
        for (int i = 0; i < grid.length; i++) grid[i] = t.pos(i % SIDE - SIDE / 2, 4, i / SIDE - SIDE / 2);
        for (BlockPos pos : grid) level.setBlock(pos, WHITE, Block.UPDATE_ALL);

        Map<String, long[]> nanos = new LinkedHashMap<>();
        Map<String, Integer> calls = new LinkedHashMap<>();
        List<Case> quiet = List.of(
                new Case("hook alone, nobody listening", HOOK_CALLS, false, () -> hook(level, grid, HOOK_CALLS)),
                new Case("setBlock, nobody listening", WRITES, false, () -> writes(level, grid, WRITES)));
        List<Case> listening = List.of(
                new Case("hook alone, listening, no actor", HOOK_CALLS, false, () -> hook(level, grid, HOOK_CALLS)),
                new Case("hook alone, listening, actor", HOOK_EVENT_CALLS, true,
                        () -> acting(() -> hook(level, grid, HOOK_EVENT_CALLS))),
                new Case("setBlock, listening, no actor", WRITES, false, () -> writes(level, grid, WRITES)),
                new Case("setBlock, listening, actor", WRITES, true, () -> acting(() -> writes(level, grid, WRITES))));

        run(quiet, nanos, calls);
        t.registerEvents(new Protection());
        run(listening, nanos, calls);

        String table = table(nanos, calls);
        write(t, table);
        t.plugin().getLogger().info("\n" + table);
    }

    private record Case(String name, int calls, boolean fires, Runnable body) {}

    /** Rounds take turns between cases, so a slow patch of the machine slows them all alike. */
    @Nondeterministic(reason = "times the chokepoint for people to read; asserts only on what events fired")
    private void run(List<Case> cases, Map<String, long[]> nanos, Map<String, Integer> calls) {
        for (Case c : cases) {
            nanos.put(c.name, new long[ROUNDS]);
            calls.put(c.name, c.calls);
        }
        for (int round = 0; round < WARMUP_ROUNDS + ROUNDS; round++) {
            for (Case c : cases) {
                heard = 0;
                long start = System.nanoTime();
                c.body.run();
                long spent = System.nanoTime() - start;
                int expected = c.fires ? c.calls : 0;
                if (heard != expected) {
                    throw new AssertionError("\"" + c.name + "\" should have fired " + expected + " block events but "
                            + "fired " + heard + ", so it timed a different path than it names. Check where "
                            + "BlockHooks.decideSetBlock returns before building an event.");
                }
                if (round >= WARMUP_ROUNDS) nanos.get(c.name)[round - WARMUP_ROUNDS] = spent;
            }
        }
    }

    /** Asks the hook about writes that would change each block, without making them. */
    private static void hook(ServerLevel level, BlockPos[] grid, int n) {
        int refused = 0;
        for (int i = 0; i < n; i++) {
            if (BlockHooks.cancelSetBlock(level, grid[i % grid.length], RED)) refused++;
        }
        if (refused != 0) throw new AssertionError("Nothing listens to refuse, yet the hook refused " + refused + ".");
    }

    /** Real writes: each pass over the grid turns every block to the other glass. */
    private static void writes(ServerLevel level, BlockPos[] grid, int n) {
        for (int i = 0; i < n; i++) {
            BlockPos pos = grid[i % grid.length];
            BlockState next = level.getBlockState(pos) == WHITE ? RED : WHITE;
            if (!level.setBlock(pos, next, Block.UPDATE_ALL)) {
                throw new AssertionError("setBlock at " + pos + " was refused, so the bench timed a refusal.");
            }
        }
    }

    private void acting(Runnable work) {
        try (Actors.Scope ignored = Actors.push(machine)) {
            work.run();
        }
    }

    private static String table(Map<String, long[]> nanos, Map<String, Integer> calls) {
        double base = perCall(nanos.get("setBlock, nobody listening"), calls.get("setBlock, nobody listening"));
        StringBuilder out = new StringBuilder();
        out.append("setBlock chokepoint, median of ").append(ROUNDS).append(" rounds after ").append(WARMUP_ROUNDS)
                .append(" warm-up\n\n");
        out.append(String.format(Locale.ROOT, "%-34s %12s %14s%n", "case", "ns per call", "of a setBlock"));
        List<String> names = new ArrayList<>(nanos.keySet());
        for (String name : names) {
            double ns = perCall(nanos.get(name), calls.get(name));
            out.append(String.format(Locale.ROOT, "%-34s %12.1f %13.1f%%%n", name, ns, 100 * ns / base));
        }
        out.append("\n\"of a setBlock\" compares each case with a whole setBlock nobody listens to.\n");
        return out.toString();
    }

    private static double perCall(long[] rounds, int calls) {
        long[] sorted = rounds.clone();
        Arrays.sort(sorted);
        return (double) sorted[sorted.length / 2] / calls;
    }

    private static void write(TestContext t, String table) {
        Path dir = t.server().getServerDirectory().resolve("bench");
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("chokepoint.txt"), table);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't write the bench table to " + dir, e);
        }
    }
}
