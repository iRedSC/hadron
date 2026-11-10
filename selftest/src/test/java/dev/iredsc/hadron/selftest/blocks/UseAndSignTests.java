package dev.iredsc.hadron.selftest.blocks;

import static dev.iredsc.hadron.selftest.blocks.Fixtures.assertPlayer;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockChangeEvent;
import dev.iredsc.hadron.api.event.block.BlockUseEvent;
import dev.iredsc.hadron.api.event.block.SignChangeEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.SideEffects;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

/** Right-clicking blocks, and writing signs. */
public final class UseAndSignTests {
    private static BlockState lever() {
        return Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR);
    }

    private static List<BlockUseEvent> recordUses(TestContext t) {
        List<BlockUseEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockUseEvent e) {
                seen.add(e);
            }
        });
        return seen;
    }

    @HadronTest
    @Covers(BlockUseEvent.class)
    void usingABlockEmptyHandedIsOneUseByThePlayer(TestContext t) {
        // Vanilla asks the block twice (with the item, then without); a plugin must hear it once.
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(1, 0, 0);
        t.setBlock(at, lever());
        List<BlockUseEvent> seen = recordUses(t);
        alice.useBlock(at);
        t.assertEquals(1, seen.size(), "the number of BlockUseEvents for one empty-handed click");
        assertPlayer(t, seen.get(0).getActor(), alice);
        t.assertEquals(at, seen.get(0).getPos(), "the used block's position");
    }

    @HadronTest
    @Covers(BlockUseEvent.class)
    void usingABlockWithAnItemIsOneUse(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(1, 0, 0);
        t.setBlock(at, lever());
        alice.hold(new ItemStack(Items.STICK));
        List<BlockUseEvent> seen = recordUses(t);
        alice.useBlock(at);
        t.assertEquals(1, seen.size(), "the number of BlockUseEvents for one click holding a stick");
        t.assertTrue(seen.get(0).getItem().is(Items.STICK), "the use should carry the stick, but carried "
                + seen.get(0).getItem());
        t.assertTrue(t.getBlock(at).getValue(LeverBlock.POWERED), "a stick in hand still flips the lever");
    }

    @HadronTest
    @Covers(BlockUseEvent.class)
    void cancelledUseLeavesTheLeverOffAndChangesNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(1, 0, 0);
        t.setBlock(at, lever());
        List<BlockChangeEvent> changes = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockUseEvent e) {
                e.setCancelled(true);
            }

            @EventHandler
            void on(BlockChangeEvent e) {
                changes.add(e);
            }
        });
        SideEffects effects = t.watchSideEffects();
        alice.useBlock(at);
        t.tick();
        t.assertBlock(at, lever());
        t.assertEquals(0, changes.size(), "the block changes after a cancelled use");
        effects.assertNone("a refused lever use");
    }

    // ---- Signs. The harness has no sign-editing packet yet, so the edit is handed to the server's own handler for
    // ---- that packet, as if it had arrived from alice's client.

    /** Alice places an oak sign at {@code at}, which opens its editor for her, as for a real player. */
    private static SignBlockEntity placeSign(TestContext t, TestPlayer alice, BlockPos at) {
        alice.hold(new ItemStack(Items.OAK_SIGN));
        alice.placeBlock(at);
        t.assertBlock(at, Blocks.OAK_SIGN);
        return (SignBlockEntity) t.level().getBlockEntity(at);
    }

    private static void write(TestContext t, TestPlayer alice, BlockPos at, String... lines) {
        alice.player().connection.handleSignUpdate(new ServerboundSignUpdatePacket(at, true, lines[0], lines[1],
                lines[2], lines[3]));
        t.tick(); // the server writes the text in a task after filtering it
    }

    private static String line(SignBlockEntity sign, int i) {
        return sign.getFrontText().getMessage(i, false).getString();
    }

    @HadronTest
    @Covers(SignChangeEvent.class)
    void writingASignIsASignChangeByThePlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(1, 0, 0);
        SignBlockEntity sign = placeSign(t, alice, at);
        List<SignChangeEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(SignChangeEvent e) {
                seen.add(e);
            }
        });
        write(t, alice, at, "hello", "world", "", "");
        t.assertEquals(1, seen.size(), "the number of SignChangeEvents for one edit");
        assertPlayer(t, seen.get(0).getActor(), alice);
        t.assertEquals("hello", seen.get(0).getLine(0), "the event's first line");
        t.assertTrue(seen.get(0).isFrontText(), "the edit was to the front");
        t.assertEquals("hello", line(sign, 0), "the sign's first line");
    }

    @HadronTest
    @Covers(SignChangeEvent.class)
    void changedSignLinesAreWhatTheSignShows(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(1, 0, 0);
        SignBlockEntity sign = placeSign(t, alice, at);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(SignChangeEvent e) {
                e.setLine(0, "rewritten");
            }
        });
        write(t, alice, at, "as typed", "second", "", "");
        t.assertEquals("rewritten", line(sign, 0), "the sign's first line");
        t.assertEquals("second", line(sign, 1), "the sign's second line");
    }

    @HadronTest
    @Covers(SignChangeEvent.class)
    void cancelledSignChangeLeavesTheSignBlank(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(1, 0, 0);
        SignBlockEntity sign = placeSign(t, alice, at);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(SignChangeEvent e) {
                e.setCancelled(true);
            }
        });
        SideEffects effects = t.watchSideEffects();
        write(t, alice, at, "never", "written", "", "");
        t.assertEquals("", line(sign, 0), "the sign's first line");
        t.assertEquals("", line(sign, 1), "the sign's second line");
        effects.assertNone("a refused sign edit");
    }
}
