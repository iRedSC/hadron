package dev.iredsc.hadron.selftest.blocks;

import static dev.iredsc.hadron.selftest.blocks.Fixtures.assertPlayer;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.block.BlockDamageEvent;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneOreBlock;

/** BlockDamageEvent: a player starting to mine, before the block is hit. */
public final class MiningTests {
    private static <E extends BlockEvent> List<E> record(TestContext t, Class<E> kind, boolean cancel) {
        List<E> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockEvent e) {
                if (!kind.isInstance(e)) return;
                seen.add(kind.cast(e));
                if (cancel && e instanceof BlockDamageEvent d) d.setCancelled(true);
            }
        });
        return seen;
    }

    @HadronTest
    @Covers(BlockDamageEvent.class)
    void startingToMineIsThePlayersAndNamesTheBlockAndTool(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos obsidian = t.pos(2, 0, 0);
        t.setBlock(obsidian, Blocks.OBSIDIAN);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        List<BlockDamageEvent> seen = record(t, BlockDamageEvent.class, false);
        alice.startDigging(obsidian);
        t.tick();
        t.assertEquals(1, seen.size(), "the BlockDamageEvents for one click");
        BlockDamageEvent e = seen.get(0);
        assertPlayer(t, e.getActor(), alice);
        t.assertEquals(obsidian, e.getPos(), "the block she started on");
        t.assertTrue(e.getItemInHand().is(Items.IRON_PICKAXE), "the tool should be her pickaxe, but was "
                + e.getItemInHand());
        t.assertEquals(List.of(Place.Spot.at(t.level(), obsidian)), List.copyOf(e.getPlaces()),
                "its places: the block, never where alice stands");
        t.assertBlock(obsidian, Blocks.OBSIDIAN);
    }

    /** Redstone ore lights when hit; refused, the hit never happens. */
    @HadronTest
    @Covers(BlockDamageEvent.class)
    void aRefusedStartLeavesTheBlockUnhit(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos ore = t.pos(2, 0, 0);
        t.setBlock(ore, Blocks.REDSTONE_ORE);
        List<BlockDamageEvent> seen = record(t, BlockDamageEvent.class, true);
        alice.startDigging(ore);
        t.tick();
        t.assertEquals(1, seen.size(), "the BlockDamageEvents asked");
        t.assertFalse(t.getBlock(ore).getValue(RedStoneOreBlock.LIT), "the refused hit shouldn't light the ore");
    }

    @HadronTest
    @Covers(BlockDamageEvent.class)
    void anAllowedStartHitsTheBlock(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos ore = t.pos(2, 0, 0);
        t.setBlock(ore, Blocks.REDSTONE_ORE);
        record(t, BlockDamageEvent.class, false);
        alice.startDigging(ore);
        t.tick();
        t.assertTrue(t.getBlock(ore).getValue(RedStoneOreBlock.LIT), "hitting the ore should light it");
    }

    @HadronTest
    @Covers(BlockDamageEvent.class)
    void aBlockThatBreaksInOneHitStartsThenBreaks(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos grass = t.pos(2, 0, 0);
        t.setBlock(grass, Blocks.SHORT_GRASS);
        List<BlockEvent> seen = record(t, BlockEvent.class, false);
        alice.startDigging(grass);
        t.tick();
        t.assertBlock(grass, Blocks.AIR);
        t.assertEquals(List.of("BlockDamageEvent", "BlockBreakEvent"), seen.stream()
                .filter(e -> e instanceof BlockDamageEvent || e instanceof BlockBreakEvent)
                .map(e -> e.getClass().getSimpleName()).toList(), "the events for an instant break, in order");
    }

    @HadronTest
    @Covers(BlockDamageEvent.class)
    void creativeBreaksWithoutStartingToMine(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.player().setGameMode(GameType.CREATIVE);
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        List<BlockDamageEvent> seen = record(t, BlockDamageEvent.class, false);
        alice.startDigging(stone);
        t.tick();
        t.assertBlock(stone, Blocks.AIR);
        t.assertEquals(0, seen.size(), "the BlockDamageEvents for a creative break");
    }
}
