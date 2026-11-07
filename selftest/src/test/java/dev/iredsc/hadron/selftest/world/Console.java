package dev.iredsc.hadron.selftest.world;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.test.TestContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Runs commands as the console would, in the test's world. */
final class Console {
    private Console() {}

    static void run(TestContext t, String command) {
        CommandSourceStack source = t.server().createCommandSourceStack().withLevel(t.level())
                .withPosition(Vec3.atBottomCenterOf(t.pos(0, 0, 0))).withSuppressedOutput();
        t.server().getCommands().performPrefixedCommand(source, command);
    }

    /**
     * Waits until the test world's chunk at {@code pos} runs block and entity ticks. A fresh world's entities load off
     * the server thread while the harness runs ticks as fast as it can, so without a player to wait on, a test can
     * otherwise spend dozens of ticks before anything ticks. The chunk is also forced: a world with no players and no
     * forced chunks stops ticking entities 300 ticks after it was last occupied.
     */
    static void awaitTicking(TestContext t, BlockPos pos) {
        t.level().setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        t.awaitAsync("the test world's chunk to start ticking", () -> t.level().isPositionEntityTicking(pos), 300);
    }

    static void assertServer(TestContext t, Actor actor) {
        t.assertTrue(actor instanceof Actor.Server, "A console command should be Actor.Server, but was " + actor);
    }
}
