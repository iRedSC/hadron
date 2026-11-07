package dev.iredsc.hadron.selftest.world;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.world.ChunkLoadEvent;
import dev.iredsc.hadron.api.event.world.ChunkUnloadEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * A chunk unloading. Vanilla finishes unloads only in a tick's spare time, and the harness runs ticks with none, so
 * this test gives the chunk system the spare time a quiet server would have: it runs the world's chunk upkeep (not its
 * block or entity ticks) with time to spare until the chunk is gone.
 */
public final class ChunkUnloadTests {
    @HadronTest(timeout = 900)
    @Covers(ChunkUnloadEvent.class)
    void aReleasedChunkUnloadsAndSaysWhichChunk(TestContext t) {
        BlockPos at = t.pos(400, 0, 0);
        ChunkPos chunk = new ChunkPos(at);
        List<String> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ChunkLoadEvent e) {
                if (e.getChunkPos().equals(chunk) && e.getLevel() == t.level()) seen.add("load");
            }

            @EventHandler
            void on(ChunkUnloadEvent e) {
                if (e.getChunkPos().equals(chunk) && e.getLevel() == t.level()) {
                    seen.add("unload " + (e.getChunk().getPos().equals(chunk)));
                }
            }
        });
        Console.run(t, "forceload add " + at.getX() + " " + at.getZ());
        t.awaitAsync("the forced chunk to load", () -> seen.contains("load"), 300);
        Console.run(t, "forceload remove " + at.getX() + " " + at.getZ());
        t.awaitAsync("the released chunk to unload", () -> {
            t.level().getChunkSource().tick(() -> true, false);
            return seen.stream().anyMatch(s -> s.startsWith("unload"));
        }, 600);
        t.assertEquals(List.of("load", "unload true"), seen, "the chunk's load and unload, with the chunk itself");
        t.assertFalse(t.level().hasChunk(chunk.x, chunk.z), "the chunk should no longer be loaded");
    }
}
