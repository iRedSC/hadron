package dev.iredsc.hadron.selftest.blocks;

import static dev.iredsc.hadron.selftest.blocks.Fixtures.console;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockChangeEvent;
import dev.iredsc.hadron.api.event.block.BlockTransformEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * /setblock, /fill and /clone empty a container before replacing it, so its contents don't spill. A refused replace
 * must keep the contents too, and a listener must see them while it decides.
 */
public final class CommandReplaceTests {
    /** A chest at {@code pos} holding one diamond. */
    private static void chestWithDiamond(TestContext t, BlockPos pos) {
        t.setBlock(pos, Blocks.CHEST);
        ((ChestBlockEntity) t.level().getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND));
    }

    private static void assertChestWithDiamond(TestContext t, BlockPos pos, String after) {
        t.assertBlock(pos, Blocks.CHEST);
        ItemStack held = ((ChestBlockEntity) t.level().getBlockEntity(pos)).getItem(0);
        t.assertTrue(held.is(Items.DIAMOND), "after a refused " + after + " the chest should still hold its diamond, "
                + "but holds " + held);
    }

    /** Refuses every change at {@code pos}; returns whether each refused change's data before held the diamond. */
    private static List<Boolean> refuseAt(TestContext t, BlockPos pos) {
        List<Boolean> sawDiamond = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockChangeEvent e) {
                if (!e.getPos().equals(pos)) return;
                sawDiamond.add(e.getBlocks().get(0).getBeforeData()
                        .map(tag -> tag.toString().contains("minecraft:diamond")).orElse(false));
                e.setCancelled(true);
            }
        });
        return sawDiamond;
    }

    @HadronTest
    @Covers(BlockTransformEvent.class)
    void refusedSetblockKeepsTheChestsContents(TestContext t) {
        BlockPos chest = t.pos(2, 0, 0);
        chestWithDiamond(t, chest);
        List<Boolean> sawDiamond = refuseAt(t, chest);
        console(t, chest, "setblock ~ ~ ~ minecraft:stone");
        assertChestWithDiamond(t, chest, "/setblock");
        t.assertEquals(List.of(true), sawDiamond, "whether the refused change's data before showed the diamond");
    }

    @HadronTest
    @Covers(BlockTransformEvent.class)
    void refusedFillKeepsTheChestsContents(TestContext t) {
        BlockPos chest = t.pos(2, 0, 0);
        chestWithDiamond(t, chest);
        List<Boolean> sawDiamond = refuseAt(t, chest);
        console(t, chest, "fill ~ ~ ~ ~ ~ ~ minecraft:stone");
        assertChestWithDiamond(t, chest, "/fill");
        t.assertEquals(List.of(true), sawDiamond, "whether the refused change's data before showed the diamond");
    }

    @HadronTest
    @Covers(BlockTransformEvent.class)
    void refusedCloneOntoAChestKeepsItsContents(TestContext t) {
        BlockPos source = t.pos(2, 0, 2);
        BlockPos chest = t.pos(2, 0, 0);
        t.setBlock(source, Blocks.STONE);
        chestWithDiamond(t, chest);
        List<Boolean> sawDiamond = refuseAt(t, chest);
        console(t, chest, "clone ~ ~ ~2 ~ ~ ~2 ~ ~ ~");
        assertChestWithDiamond(t, chest, "/clone");
        t.assertTrue(!sawDiamond.isEmpty() && sawDiamond.get(0), "the first refused change's data before should "
                + "show the diamond; per refused change: " + sawDiamond);
    }

    @HadronTest
    @Covers(BlockTransformEvent.class)
    void allowedSetblockStillEmptiesTheChestSoNothingSpills(TestContext t) {
        BlockPos chest = t.pos(2, 0, 0);
        chestWithDiamond(t, chest);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockChangeEvent e) {}
        });
        console(t, chest, "setblock ~ ~ ~ minecraft:stone");
        t.tick(2);
        t.assertBlock(chest, Blocks.STONE);
        t.assertEquals(0, Fixtures.entities(t, net.minecraft.world.entity.item.ItemEntity.class, chest, 3),
                "items spilled by replacing the chest");
    }
}
