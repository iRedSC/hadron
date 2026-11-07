package dev.iredsc.hadron.selftest.blocks;

import static dev.iredsc.hadron.selftest.blocks.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.blocks.Fixtures.experienceAround;
import static dev.iredsc.hadron.selftest.blocks.Fixtures.itemsAround;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockDropItemEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** A broken block's drops: who caused them, refusing them, and replacing them. */
public final class DropTests {
    /** Alice, holding an iron pickaxe, next to stone at pos(1, 0, 0). */
    private static BlockPos stone(TestContext t, TestPlayer alice) {
        BlockPos stone = t.pos(1, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        return stone;
    }

    @HadronTest
    @Covers(BlockDropItemEvent.class)
    void aPlayersBreakDropsOnceByThatPlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = stone(t, alice);
        List<BlockDropItemEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockDropItemEvent e) {
                seen.add(e);
            }
        });
        alice.breakBlock(stone);
        t.assertEquals(1, seen.size(), "the number of BlockDropItemEvents for one break");
        BlockDropItemEvent drop = seen.get(0);
        assertPlayer(t, drop.getActor(), alice);
        t.assertTrue(drop.getBlockState().is(Blocks.STONE), "the event should be about the stone, but was "
                + drop.getBlockState());
        t.assertTrue(drop.getTool().is(Items.IRON_PICKAXE), "the event's tool should be the pickaxe, but was "
                + drop.getTool());
        t.assertEquals(1, itemsAround(t, alice, Items.COBBLESTONE, stone), "the cobblestone dropped");
    }

    @HadronTest
    @Covers(BlockDropItemEvent.class)
    void cancelledDropsDropNothingButTheBlockStillBreaks(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = stone(t, alice);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockDropItemEvent e) {
                e.setExperience(5);
                e.setCancelled(true);
            }
        });
        alice.breakBlock(stone);
        t.tick(2);
        t.assertBlock(stone, Blocks.AIR);
        t.assertEquals(0, itemsAround(t, alice, Items.COBBLESTONE, stone), "the cobblestone dropped");
        t.assertEquals(0, experienceAround(t, alice, stone), "the experience dropped");
    }

    @HadronTest
    @Covers(BlockDropItemEvent.class)
    void changedDropsAndExperienceAreWhatDrops(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = stone(t, alice);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockDropItemEvent e) {
                e.getItems().clear();
                e.addItem(new ItemStack(Items.DIAMOND, 3));
                e.setExperience(7);
            }
        });
        alice.breakBlock(stone);
        t.tick(2);
        t.assertEquals(0, itemsAround(t, alice, Items.COBBLESTONE, stone), "the cobblestone dropped");
        t.assertEquals(3, itemsAround(t, alice, Items.DIAMOND, stone), "the diamonds dropped");
        t.assertEquals(7, experienceAround(t, alice, stone), "the experience dropped");
    }
}
