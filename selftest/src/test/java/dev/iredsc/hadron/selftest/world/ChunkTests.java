package dev.iredsc.hadron.selftest.world;

import static dev.iredsc.hadron.selftest.world.Console.assertServer;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.world.ChunkForceLoadEvent;
import dev.iredsc.hadron.api.event.world.ChunkForceUnloadEvent;
import dev.iredsc.hadron.api.event.world.ChunkLoadEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** /forceload from the console, and the chunk it forces loading and unloading. */
public final class ChunkTests {
    /** A block far from the test's centre, in a chunk nothing else loads. */
    private static BlockPos far(TestContext t) {
        return t.pos(400, 0, 0);
    }

    private static String forceload(String op, BlockPos at) {
        return "forceload " + op + " " + at.getX() + " " + at.getZ();
    }

    @HadronTest
    @Covers(ChunkForceLoadEvent.class)
    void forceloadIsOneForceLoadByTheServer(TestContext t) {
        BlockPos at = far(t);
        ChunkPos chunk = new ChunkPos(at);
        List<ChunkForceLoadEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ChunkForceLoadEvent e) {
                seen.add(e);
            }
        });
        Console.run(t, forceload("add", at));
        t.assertTrue(t.level().getForcedChunks().contains(chunk.toLong()), "the chunk should be forced");
        t.assertEquals(1, seen.size(), "the number of ChunkForceLoadEvents for one /forceload add");
        assertServer(t, seen.get(0).getActor());
        t.assertEquals(chunk, seen.get(0).getChunkPos(), "the forced chunk");
        Console.run(t, forceload("remove", at));
    }

    @HadronTest
    @Covers(ChunkForceLoadEvent.class)
    void cancelledForceloadForcesNothing(TestContext t) {
        BlockPos at = far(t);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ChunkForceLoadEvent e) {
                e.setCancelled(true);
            }
        });
        Console.run(t, forceload("add", at));
        t.assertFalse(t.level().getForcedChunks().contains(new ChunkPos(at).toLong()),
                "a cancelled /forceload add should force nothing");
    }

    @HadronTest
    @Covers(ChunkForceUnloadEvent.class)
    void forceloadRemoveIsAForceUnloadByTheServer(TestContext t) {
        BlockPos at = far(t);
        Console.run(t, forceload("add", at));
        List<ChunkForceUnloadEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ChunkForceUnloadEvent e) {
                seen.add(e);
            }
        });
        Console.run(t, forceload("remove", at));
        t.assertEquals(1, seen.size(), "the number of ChunkForceUnloadEvents for one /forceload remove");
        assertServer(t, seen.get(0).getActor());
        t.assertEquals(new ChunkPos(at), seen.get(0).getChunkPos(), "the released chunk");
    }

    // ChunkUnloadEvent isn't tested: the harness runs each tick with no time to spare, and vanilla finishes unloading
    // chunks only in a tick's spare time, so no chunk ever unloads in a test.
    @HadronTest
    @Covers(ChunkLoadEvent.class)
    void aForcedChunkLoadsAsNew(TestContext t) {
        BlockPos at = far(t);
        ChunkPos chunk = new ChunkPos(at);
        List<ChunkLoadEvent> loads = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ChunkLoadEvent e) {
                if (e.getChunkPos().equals(chunk) && e.getLevel() == t.level()) loads.add(e);
            }
        });
        Console.run(t, forceload("add", at));
        Console.awaitSlowly(t, "the forced chunk to load", () -> !loads.isEmpty(), 300);
        t.assertEquals(1, loads.size(), "the number of ChunkLoadEvents for the forced chunk");
        t.assertTrue(loads.get(0).isNewChunk(), "a chunk never generated before should load as new");
        Console.run(t, forceload("remove", at));
    }
}
