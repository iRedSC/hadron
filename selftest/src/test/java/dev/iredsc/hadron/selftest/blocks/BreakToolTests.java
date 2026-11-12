package dev.iredsc.hadron.selftest.blocks;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.block.BlockPlaceEvent;
import dev.iredsc.hadron.api.event.block.BlockUseEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a block is broken with: a player's main hand, a machine's own harvesting tool, nothing, or unknown, and never
 * a guess. Plus a refused block use letting the held item act on its own.
 */
public final class BreakToolTests {
    private static List<BlockBreakEvent> recordBreaks(TestContext t) {
        List<BlockBreakEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockBreakEvent e) {
                seen.add(e);
            }
        });
        return seen;
    }

    private static BlockBreakEvent one(TestContext t, List<BlockBreakEvent> seen) {
        t.assertEquals(1, seen.size(), "BlockBreakEvents fired");
        return seen.get(0);
    }

    private static void assertTool(TestContext t, BlockBreakEvent e, net.minecraft.world.item.Item expected) {
        if (!(e.getTool() instanceof BlockBreakEvent.Tool.Item item)) {
            throw new AssertionError("the break should name " + expected + " as its tool, but it says " + e.getTool());
        }
        t.assertTrue(item.getItem().is(expected), "the tool should be " + expected + ", but is " + item.getItem());
    }

    private static Actor.Machine machine(TestContext t) {
        return new Actor.Machine(t.level(), t.pos(3, 0, 0), Attribution.none());
    }

    @HadronTest
    @Covers(value = BlockBreakEvent.class, method = "getTool")
    @Covers(value = BlockBreakEvent.Tool.Item.class, method = "getItem")
    void aPlayerMinesWithTheItemInTheirMainHand(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos dirt = t.pos(1, 0, 0);
        t.setBlock(dirt, Blocks.DIRT);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        List<BlockBreakEvent> seen = recordBreaks(t);
        alice.breakBlock(dirt);
        assertTool(t, one(t, seen), Items.IRON_PICKAXE);
    }

    @HadronTest
    @Covers(value = BlockBreakEvent.class, method = "getTool")
    void noDirtWithAnIronPickaxe(TestContext t) {
        // The rule a plugin author asked for, written the way an agent would.
        TestPlayer alice = t.player("alice");
        BlockPos dirt = t.pos(1, 0, 0);
        t.setBlock(dirt, Blocks.DIRT);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockBreakEvent e) {
                if (e.getState().is(Blocks.DIRT) && e.getTool() instanceof BlockBreakEvent.Tool.Item tool
                        && tool.getItem().is(Items.IRON_PICKAXE)) {
                    e.setCancelled(true);
                }
            }
        });
        alice.breakBlock(dirt);
        t.assertBlock(dirt, Blocks.DIRT);
        t.assertInventory(alice, Items.DIRT, 0);
    }

    @HadronTest
    @Covers(value = BlockBreakEvent.class, method = "getTool")
    void aBareHandIsNoTool(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos dirt = t.pos(1, 0, 0);
        t.setBlock(dirt, Blocks.DIRT);
        alice.hold(ItemStack.EMPTY);
        List<BlockBreakEvent> seen = recordBreaks(t);
        alice.breakBlock(dirt);
        t.assertEquals(BlockBreakEvent.Tool.None.INSTANCE, one(t, seen).getTool(), "a bare hand's tool");
    }

    @HadronTest
    @Covers(value = BlockBreakEvent.class, method = "getTool")
    void aMachineThatWorksOutItsDropsWithAToolNamesThatTool(TestContext t) {
        // AE2's annihilation plane and Mekanism's digital miner: drops with their own tool, then the removal.
        BlockPos stone = t.pos(1, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        List<BlockBreakEvent> seen = recordBreaks(t);
        try (Actors.Scope s = Actors.push(machine(t))) {
            BlockState state = t.level().getBlockState(stone);
            Block.getDrops(state, t.level(), stone, null, null, new ItemStack(Items.DIAMOND_PICKAXE));
            t.level().setBlock(stone, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        assertTool(t, one(t, seen), Items.DIAMOND_PICKAXE);
    }

    @HadronTest
    @Covers(value = BlockBreakEvent.class, method = "getTool")
    void aMachineThatWorksOutItsDropsWithNothingBreaksWithNothing(TestContext t) {
        // Create's drill: drops with ItemStack.EMPTY.
        BlockPos stone = t.pos(1, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        List<BlockBreakEvent> seen = recordBreaks(t);
        try (Actors.Scope s = Actors.push(machine(t))) {
            Block.getDrops(t.level().getBlockState(stone), t.level(), stone, null, null, ItemStack.EMPTY);
            t.level().setBlock(stone, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        t.assertEquals(BlockBreakEvent.Tool.None.INSTANCE, one(t, seen).getTool(), "a drill's tool");
    }

    @HadronTest
    @Covers(value = BlockBreakEvent.class, method = "getTool")
    void aRemovalWithNoDropsWorkedOutIsUnknownNotNone(TestContext t) {
        BlockPos stone = t.pos(1, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        List<BlockBreakEvent> seen = recordBreaks(t);
        try (Actors.Scope s = Actors.push(machine(t))) {
            t.level().setBlock(stone, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        t.assertEquals(new BlockBreakEvent.Tool.Unknown(BlockBreakEvent.Tool.Reason.REMOVED_DIRECTLY),
                one(t, seen).getTool(), "a mod's direct removal's tool");
    }

    @HadronTest
    @Covers(value = BlockBreakEvent.class, method = "getTool")
    void dropsWorkedOutForAnotherBlockDontNameThisOnesTool(TestContext t) {
        BlockPos stone = t.pos(1, 0, 0);
        BlockPos other = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        t.setBlock(other, Blocks.STONE);
        List<BlockBreakEvent> seen = recordBreaks(t);
        try (Actors.Scope s = Actors.push(machine(t))) {
            Block.getDrops(t.level().getBlockState(other), t.level(), other, null, null,
                    new ItemStack(Items.DIAMOND_PICKAXE));
            t.level().setBlock(stone, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        t.assertEquals(new BlockBreakEvent.Tool.Unknown(BlockBreakEvent.Tool.Reason.REMOVED_DIRECTLY),
                one(t, seen).getTool(), "a removal next to a block whose drops were worked out");
    }

    @HadronTest
    @Covers(value = BlockBreakEvent.class, method = "getTool")
    void destroyBlockBreaksWithNothing(TestContext t) {
        BlockPos stone = t.pos(1, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        List<BlockBreakEvent> seen = recordBreaks(t);
        try (Actors.Scope s = Actors.push(machine(t))) {
            t.level().destroyBlock(stone, true);
        }
        t.assertEquals(BlockBreakEvent.Tool.None.INSTANCE, one(t, seen).getTool(),
                "destroyBlock harvests with nothing, as vanilla does");
    }

    /**
     * A mod asking NeoForge whether it may break a block (Mekanism's digital miner does) before saying what with.
     * Posted by reflection because self-tests compile against Hadron's API only.
     */
    @HadronTest
    @Covers(value = BlockBreakEvent.class, method = "getTool")
    void aModsPermissionCheckSaysTheToolIsUnknown(TestContext t) throws Exception {
        TestPlayer alice = t.player("alice");
        alice.hold(new ItemStack(Items.DIAMOND_PICKAXE));
        BlockPos stone = t.pos(1, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        List<BlockBreakEvent> seen = recordBreaks(t);
        Class<?> breakEvent = Class.forName("net.neoforged.neoforge.event.level.BlockEvent$BreakEvent");
        Object event = breakEvent.getConstructor(net.minecraft.world.level.Level.class, BlockPos.class,
                BlockState.class, net.minecraft.world.entity.player.Player.class)
                .newInstance(t.level(), stone, t.level().getBlockState(stone), alice.player());
        Object bus = Class.forName("net.neoforged.neoforge.common.NeoForge").getField("EVENT_BUS").get(null);
        Class<?> neoEvent = Class.forName("net.neoforged.bus.api.Event");
        bus.getClass().getMethod("post", neoEvent).invoke(bus, event);
        t.assertEquals(new BlockBreakEvent.Tool.Unknown(BlockBreakEvent.Tool.Reason.PERMISSION_CHECK),
                one(t, seen).getTool(), "a permission check's tool, even with a pickaxe in the player's hand");
    }

    @HadronTest
    @Covers(BlockUseEvent.class)
    void aRefusedBlockUseStillLetsTheHeldItemPlaceAgainstIt(TestContext t) {
        // Refusing the block is not refusing the item: a torch still goes on a crafting table nobody may open.
        TestPlayer alice = t.player("alice");
        BlockPos table = t.pos(1, 0, 0);
        t.setBlock(table, Blocks.CRAFTING_TABLE);
        alice.hold(new ItemStack(Items.TORCH));
        List<BlockPlaceEvent> places = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockUseEvent e) {
                e.setCancelled(true);
            }

            @EventHandler
            void on(BlockPlaceEvent e) {
                places.add(e);
            }
        });
        alice.useBlock(table);
        t.assertTrue(alice.player().containerMenu == alice.player().inventoryMenu, "the crafting table should stay "
                + "closed, but alice has " + alice.player().containerMenu + " open");
        t.assertBlock(table.above(), Blocks.TORCH);
        t.assertEquals(1, places.size(), "the torch's own BlockPlaceEvent");
    }
}
