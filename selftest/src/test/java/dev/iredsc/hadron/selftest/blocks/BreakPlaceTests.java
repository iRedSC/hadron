package dev.iredsc.hadron.selftest.blocks;

import static dev.iredsc.hadron.selftest.blocks.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.blocks.Fixtures.console;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.block.BlockChangeEvent;
import dev.iredsc.hadron.api.event.block.BlockMultiPlaceEvent;
import dev.iredsc.hadron.api.event.block.BlockPlaceEvent;
import dev.iredsc.hadron.api.event.block.BlockTransformEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.SideEffects;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.gameevent.GameEvent;

/** Breaking, placing and changing blocks, by players and the console: who did it, cancels, one event per effect. */
public final class BreakPlaceTests {
    /** Every block change event, by class, so a test can check nothing fired twice or as the wrong kind. */
    private static List<BlockChangeEvent> recordChanges(TestContext t) {
        List<BlockChangeEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockChangeEvent e) {
                seen.add(e);
            }
        });
        return seen;
    }

    private static String classes(List<? extends Object> events) {
        return events.stream().map(e -> e.getClass().getSimpleName()).toList().toString();
    }

    // ---- Breaking

    @HadronTest
    @Covers(BlockBreakEvent.class)
    void aPlayersBreakIsOneBlockChangeAndItsABreak(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos dirt = t.pos(1, 0, 0);
        t.setBlock(dirt, Blocks.DIRT);
        List<BlockChangeEvent> seen = recordChanges(t);
        alice.breakBlock(dirt);
        t.assertBlock(dirt, Blocks.AIR);
        t.assertEquals("[BlockBreakEvent]", classes(seen), "the block change events for one break");
    }

    @HadronTest
    @Covers(BlockBreakEvent.class)
    void consoleSetblockToAirIsABreakByTheServer(TestContext t) {
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        List<BlockChangeEvent> seen = recordChanges(t);
        console(t, stone, "setblock ~ ~ ~ minecraft:air");
        t.assertBlock(stone, Blocks.AIR);
        t.assertEquals("[BlockBreakEvent]", classes(seen), "the block change events for /setblock air");
        t.assertTrue(seen.get(0).getActor() instanceof Actor.Server,
                "/setblock from the console should be Actor.Server, but was " + seen.get(0).getActor());
    }

    @HadronTest
    @Covers(BlockBreakEvent.class)
    void cancelledConsoleSetblockKeepsTheBlock(TestContext t) {
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockBreakEvent e) {
                e.setCancelled(true);
            }
        });
        console(t, stone, "setblock ~ ~ ~ minecraft:air");
        t.assertBlock(stone, Blocks.STONE);
    }

    // ---- Placing

    @HadronTest
    @Covers(BlockPlaceEvent.class)
    void consoleSetblockIsAPlaceByTheServer(TestContext t) {
        BlockPos at = t.pos(2, 0, 0);
        List<BlockChangeEvent> seen = recordChanges(t);
        console(t, at, "setblock ~ ~ ~ minecraft:stone");
        t.assertBlock(at, Blocks.STONE);
        t.assertEquals("[BlockPlaceEvent]", classes(seen), "the block change events for /setblock stone");
        t.assertTrue(seen.get(0).getActor() instanceof Actor.Server,
                "/setblock from the console should be Actor.Server, but was " + seen.get(0).getActor());
    }

    @HadronTest
    @Covers(BlockPlaceEvent.class)
    void aPlayersPlaceIsOnePlaceByThatPlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(1, 0, 0);
        alice.hold(new ItemStack(Items.STONE, 64));
        List<BlockChangeEvent> seen = recordChanges(t);
        SideEffects effects = t.watchSideEffects();
        alice.placeBlock(at);
        t.assertBlock(at, Blocks.STONE);
        t.assertHolding(alice, Items.STONE, 63);
        t.assertEquals("[BlockPlaceEvent]", classes(seen), "the block change events for one place");
        assertPlayer(t, seen.get(0).getActor(), alice);
        // An allowed place is held only until it is allowed: it still counts and is still heard.
        assertTriggered(t, effects, CriteriaTriggers.PLACED_BLOCK, "an allowed place");
        assertHeard(t, effects, GameEvent.BLOCK_PLACE, at, "an allowed place");
    }

    @HadronTest
    @Covers(BlockPlaceEvent.class)
    void cancelledPlaceLeavesAirAndKeepsTheItem(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(1, 0, 0);
        alice.hold(new ItemStack(Items.STONE, 64));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockPlaceEvent e) {
                e.setCancelled(true);
            }
        });
        SideEffects effects = t.watchSideEffects();
        alice.placeBlock(at);
        t.tick();
        t.assertBlock(at, Blocks.AIR);
        t.assertHolding(alice, Items.STONE, 64);
        effects.assertNone("a refused place");
    }

    @HadronTest
    @Covers(BlockPlaceEvent.class)
    void aPlantingCountsTowardItsAdvancementOnlyWhenAllowed(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos crop = t.pos(1, 1, 0);
        t.setBlock(crop.below(), Blocks.FARMLAND);
        alice.hold(new ItemStack(Items.WHEAT_SEEDS, 4));
        boolean[] refuse = {true};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockPlaceEvent e) {
                if (refuse[0]) e.setCancelled(true);
            }
        });
        alice.placeBlock(crop);
        t.assertBlock(crop, Blocks.AIR);
        t.assertFalse(plantedWheat(t, alice), "a refused planting should not count toward husbandry/plant_seed");
        refuse[0] = false;
        alice.placeBlock(crop);
        t.assertBlock(crop, Blocks.WHEAT);
        t.assertTrue(plantedWheat(t, alice), "an allowed planting should count toward husbandry/plant_seed");
    }

    /** Whether vanilla's husbandry/plant_seed counted {@code player} planting wheat. */
    private static boolean plantedWheat(TestContext t, TestPlayer player) {
        AdvancementHolder plant = t.server().getAdvancements()
                .get(ResourceLocation.withDefaultNamespace("husbandry/plant_seed"));
        if (plant == null) throw new AssertionError("vanilla's husbandry/plant_seed advancement is missing");
        CriterionProgress wheat = player.player().getAdvancements().getOrStartProgress(plant).getCriterion("wheat");
        if (wheat == null) throw new AssertionError("husbandry/plant_seed has no wheat criterion");
        return wheat.isDone();
    }

    @HadronTest
    @Covers(BlockMultiPlaceEvent.class)
    void placingADoorIsOneMultiPlaceOfBothHalves(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(1, 0, 0);
        alice.hold(new ItemStack(Items.OAK_DOOR, 2));
        List<BlockChangeEvent> seen = recordChanges(t);
        alice.placeBlock(at);
        t.assertBlock(at, Blocks.OAK_DOOR);
        t.assertBlock(at.above(), Blocks.OAK_DOOR);
        t.assertEquals("[BlockMultiPlaceEvent]", classes(seen), "the block change events for placing a door");
        BlockMultiPlaceEvent place = (BlockMultiPlaceEvent) seen.get(0);
        t.assertEquals(2, place.getBlocks().size(), "the blocks a door's multi-place lists");
        assertPlayer(t, place.getActor(), alice);
    }

    @HadronTest
    @Covers(BlockMultiPlaceEvent.class)
    void cancelledDoorPlaceLeavesNeitherHalfAndKeepsTheDoor(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(1, 0, 0);
        alice.hold(new ItemStack(Items.OAK_DOOR, 2));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockMultiPlaceEvent e) {
                e.setCancelled(true);
            }
        });
        SideEffects effects = t.watchSideEffects();
        alice.placeBlock(at);
        t.tick();
        t.assertBlock(at, Blocks.AIR);
        t.assertBlock(at.above(), Blocks.AIR);
        t.assertHolding(alice, Items.OAK_DOOR, 2);
        effects.assertNone("a refused door place");
    }

    // ---- Transforming and changing

    @HadronTest
    @Covers(BlockTransformEvent.class)
    void strippingALogIsOneTransformByThePlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos log = t.pos(1, 0, 0);
        t.setBlock(log, Blocks.OAK_LOG);
        alice.hold(new ItemStack(Items.IRON_AXE));
        List<BlockChangeEvent> seen = recordChanges(t);
        alice.useBlock(log);
        t.assertBlock(log, Blocks.STRIPPED_OAK_LOG);
        t.assertEquals("[BlockTransformEvent]", classes(seen), "the block change events for stripping a log");
        assertPlayer(t, seen.get(0).getActor(), alice);
    }

    @HadronTest
    @Covers(BlockTransformEvent.class)
    void cancelledStripKeepsTheLogAndTheAxesDurability(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos log = t.pos(1, 0, 0);
        t.setBlock(log, Blocks.OAK_LOG);
        alice.hold(new ItemStack(Items.IRON_AXE));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockTransformEvent e) {
                e.setCancelled(true);
            }
        });
        SideEffects effects = t.watchSideEffects();
        alice.useBlock(log);
        t.tick();
        t.assertBlock(log, Blocks.OAK_LOG);
        t.assertEquals(0, alice.player().getMainHandItem().getDamageValue(), "the axe's damage");
        effects.assertNone("a refused strip");
    }

    private static BlockState lever() {
        return Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR);
    }

    @HadronTest
    @Covers(BlockChangeEvent.class)
    void flippingALeverIsOneBlockChangeByThePlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(1, 0, 0);
        t.setBlock(at, lever());
        List<BlockChangeEvent> seen = recordChanges(t);
        SideEffects effects = t.watchSideEffects();
        alice.useBlock(at);
        t.assertTrue(t.getBlock(at).getValue(LeverBlock.POWERED), "the lever should be on after alice flips it");
        // An allowed flip is held only until its change is allowed: it still counts and is still heard.
        assertTriggered(t, effects, CriteriaTriggers.DEFAULT_BLOCK_USE, "an allowed lever flip");
        assertHeard(t, effects, GameEvent.BLOCK_ACTIVATE, at, "an allowed lever flip");
        t.assertEquals("[BlockChangeEvent]", classes(seen), "the block change events for flipping a lever");
        assertPlayer(t, seen.get(0).getActor(), alice);
        t.assertTrue(seen.get(0).getAfter().getValue(LeverBlock.POWERED), "the event's after state should be on");
    }

    @HadronTest
    @Covers(BlockChangeEvent.class)
    void cancelledLeverFlipLeavesItOff(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(1, 0, 0);
        t.setBlock(at, lever());
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockChangeEvent e) {
                e.setCancelled(true);
            }
        });
        SideEffects effects = t.watchSideEffects();
        alice.useBlock(at);
        t.tick();
        t.assertBlock(at, lever());
        effects.assertNone("a refused lever flip");
    }

    private static void assertTriggered(TestContext t, SideEffects effects, CriterionTrigger<?> trigger, String step) {
        t.assertTrue(effects.seen(SideEffects.CriterionTriggered.class).stream().anyMatch(c -> c.trigger() == trigger),
                step + " should fire " + BuiltInRegistries.TRIGGER_TYPES.getKey(trigger) + ", but the side effects were "
                        + effects.seen());
    }

    private static void assertHeard(TestContext t, SideEffects effects, Holder<GameEvent> event, BlockPos at,
            String step) {
        t.assertTrue(effects.seen(SideEffects.GameEventEmitted.class).stream()
                .anyMatch(e -> e.event().is(event) && BlockPos.containing(e.pos()).equals(at)),
                step + " should emit " + event.getRegisteredName() + " at " + at.toShortString()
                        + ", but the side effects were " + effects.seen());
    }
}
