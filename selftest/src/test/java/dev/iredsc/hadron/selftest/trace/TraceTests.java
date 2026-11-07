package dev.iredsc.hadron.selftest.trace;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.player.PlayerChatEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** {@code /hadron trace}: an op sees each event as it fires, with its actor, operation and outcome, until it stops. */
public final class TraceTests {
    /** An op tracing {@code what}, with the start line already checked and the chat cleared. */
    private static TestPlayer tracing(TestContext t, String what) {
        TestPlayer op = t.player("op");
        op.setOp(true);
        op.command("hadron trace " + what);
        t.assertChat(op, "Tracing ");
        t.assertChat(op, "for 5 min");
        op.clearReceived();
        return op;
    }

    private static List<String> lines(TestPlayer viewer, String event) {
        return viewer.chat().stream().filter(l -> l.startsWith(event + " · ")).toList();
    }

    private static BlockPos dirt(TestContext t, int x) {
        BlockPos pos = t.pos(x, 0, 0);
        t.setBlock(pos, Blocks.DIRT);
        return pos;
    }

    @HadronTest
    void anOpSeesABreakWithTheBreakerAndTheOutcome(TestContext t) {
        TestPlayer op = tracing(t, "BlockBreakEvent");
        TestPlayer alice = t.player("alice");
        BlockPos dirt = dirt(t, 1);
        alice.breakBlock(dirt);
        t.assertBlock(dirt, Blocks.AIR);
        List<String> seen = lines(op, "BlockBreakEvent");
        t.assertEquals(1, seen.size(), "trace lines for one break (got " + op.chat() + ")");
        String line = seen.get(0);
        t.assertTrue(line.contains(" · Player alice · op#"), "The line should name alice as a Player actor and "
                + "the operation, but was: " + line);
        t.assertTrue(line.contains(" " + dirt.getX() + " " + dirt.getY() + " " + dirt.getZ() + " · "),
                "The line should give the block's position, but was: " + line);
        t.assertTrue(line.endsWith(" · allowed"), "An uncancelled break should end 'allowed', but was: " + line);
    }

    @HadronTest
    void aCancelNamesThePluginThatCancelled(TestContext t) {
        TestPlayer op = tracing(t, "block");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockBreakEvent e) {
                e.setCancelled(true);
            }
        });
        TestPlayer alice = t.player("alice");
        BlockPos dirt = dirt(t, 1);
        alice.breakBlock(dirt);
        t.assertBlock(dirt, Blocks.DIRT);
        List<String> seen = lines(op, "BlockBreakEvent");
        t.assertFalse(seen.isEmpty(), "The op should see the cancelled break; got " + op.chat());
        t.assertTrue(seen.get(0).endsWith(" · cancelled by " + t.plugin().getName()),
                "The line should say which plugin cancelled, but was: " + seen.get(0));
    }

    @HadronTest
    void aPluginsChangeShowsWhatItChanged(TestContext t) {
        TestPlayer op = tracing(t, "PlayerChatEvent");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerChatEvent e) {
                e.setMessage(Component.literal("HI"));
            }
        });
        TestPlayer alice = t.player("alice");
        alice.chat("hi");
        t.await("the chat trace line", () -> !lines(op, "PlayerChatEvent").isEmpty(), 5);
        String line = lines(op, "PlayerChatEvent").get(0);
        t.assertTrue(line.contains(" · Player alice"), "The chat line should name alice, but was: " + line);
        t.assertTrue(line.contains("allowed, message ") && line.endsWith("→\"HI\""),
                "The line should show the message the plugin set, but was: " + line);
    }

    @HadronTest
    void traceOffStopsIt(TestContext t) {
        TestPlayer op = tracing(t, "BlockBreakEvent");
        TestPlayer alice = t.player("alice");
        op.command("hadron trace");
        t.assertChat(op, "Tracing BlockBreakEvent, ");
        op.command("hadron trace off");
        t.assertChat(op, "Trace of BlockBreakEvent off");
        op.clearReceived();
        alice.breakBlock(dirt(t, 1));
        t.tick(2);
        t.assertNoChat(op, "BlockBreakEvent · ");
        op.command("hadron trace");
        t.assertChat(op, "Not tracing");
    }

    @HadronTest
    void aFilterForSomeoneElseHidesTheBreak(TestContext t) {
        TestPlayer op = tracing(t, "BlockBreakEvent by op");
        TestPlayer alice = t.player("alice");
        alice.breakBlock(dirt(t, 1));
        t.tick(2);
        t.assertNoChat(op, "BlockBreakEvent · ");
    }

    @HadronTest
    void aFloodIsSummarisedNotSent(TestContext t) {
        TestPlayer op = tracing(t, "BlockBreakEvent");
        CommandSourceStack console = t.server().createCommandSourceStack().withLevel(t.level()).withSuppressedOutput();
        for (int x = -7; x <= 7; x++) {
            BlockPos stone = t.pos(x, 0, 3);
            t.setBlock(stone, Blocks.STONE);
            t.server().getCommands().performPrefixedCommand(console.withPosition(Vec3.atBottomCenterOf(stone)),
                    "setblock ~ ~ ~ minecraft:air");
        }
        t.tick(21);
        List<String> seen = lines(op, "BlockBreakEvent");
        t.assertEquals(10, seen.size(), "trace lines in the first second of 15 breaks");
        t.assertTrue(seen.get(0).contains(" · Server "), "A console /setblock should show as Server: " + seen.get(0));
        t.assertChat(op, "+5 more in the last second (BlockBreakEvent 5)");
    }
}
