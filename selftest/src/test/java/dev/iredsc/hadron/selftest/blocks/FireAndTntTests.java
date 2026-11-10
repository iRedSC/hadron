package dev.iredsc.hadron.selftest.blocks;

import static dev.iredsc.hadron.selftest.blocks.Fixtures.assertMachineAt;
import static dev.iredsc.hadron.selftest.blocks.Fixtures.assertNature;
import static dev.iredsc.hadron.selftest.blocks.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.blocks.Fixtures.clearWeather;
import static dev.iredsc.hadron.selftest.blocks.Fixtures.dispenser;
import static dev.iredsc.hadron.selftest.blocks.Fixtures.entities;
import static dev.iredsc.hadron.selftest.blocks.Fixtures.fire;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.block.BlockBurnEvent;
import dev.iredsc.hadron.api.event.block.BlockChangeEvent;
import dev.iredsc.hadron.api.event.block.BlockIgniteEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.SideEffects;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;

/** Fire lit, spreading and burning; TNT lit by a player and by a dispenser. */
public final class FireAndTntTests {
    private static String classes(List<? extends Object> events) {
        return events.stream().map(e -> e.getClass().getSimpleName()).toList().toString();
    }

    // ---- Flint and steel on a block

    @HadronTest
    @Covers(BlockIgniteEvent.class)
    void flintAndSteelLightingFireIsOneIgniteByThePlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos floor = t.pos(1, -1, 0);
        alice.hold(new ItemStack(Items.FLINT_AND_STEEL));
        List<BlockChangeEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockChangeEvent e) {
                seen.add(e);
            }
        });
        alice.useBlock(floor, Direction.UP);
        t.assertBlock(floor.above(), Blocks.FIRE);
        t.assertEquals("[BlockIgniteEvent]", classes(seen), "the block change events for lighting one fire");
        assertPlayer(t, seen.get(0).getActor(), alice);
        t.assertEquals(1, alice.player().getMainHandItem().getDamageValue(), "the flint and steel's damage");
    }

    @HadronTest
    @Covers(BlockIgniteEvent.class)
    void cancelledIgniteLeavesNoFireAndTheFlintUnworn(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos floor = t.pos(1, -1, 0);
        alice.hold(new ItemStack(Items.FLINT_AND_STEEL));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockIgniteEvent e) {
                e.setCancelled(true);
            }
        });
        SideEffects effects = t.watchSideEffects();
        alice.useBlock(floor, Direction.UP);
        t.tick();
        t.assertBlock(floor.above(), Blocks.AIR);
        t.assertEquals(0, alice.player().getMainHandItem().getDamageValue(), "the flint and steel's damage");
        effects.assertNone("a refused ignite");
    }

    // ---- Fire on its own

    /** Fire on a wool block, walled in by wool, so it burns something within a few fire ticks. */
    private static BlockPos fireInWool(TestContext t) {
        clearWeather(t);
        BlockPos fire = t.pos(3, 1, 3);
        Fixtures.awaitTicking(t, fire);
        t.setBlock(fire.below(), Blocks.WHITE_WOOL);
        for (Direction side : Direction.Plane.HORIZONTAL) t.setBlock(fire.relative(side), Blocks.WHITE_WOOL);
        t.setBlock(fire, Blocks.FIRE);
        return fire;
    }

    @HadronTest(timeout = 900)
    @Covers(BlockBurnEvent.class)
    void fireBurningABlockIsABurnByNature(TestContext t) {
        List<BlockBurnEvent> burns = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockBurnEvent e) {
                burns.add(e);
            }
        });
        BlockPos fire = fireInWool(t);
        t.await("fire to burn a wool block", () -> !burns.isEmpty(), 600);
        BlockBurnEvent burn = burns.get(0);
        assertNature(t, burn.getActor());
        // The first burn may come from fire that has already spread, so any fire next to the burned block counts.
        BlockPos igniting = burn.getIgnitingPos().orElse(null);
        t.assertTrue(igniting != null && igniting.distManhattan(burn.getPos()) == 1, "the burn should name the fire "
                + "next to the burned block at " + t.describe(burn.getPos()) + ", but named "
                + (igniting == null ? "none" : t.describe(igniting)) + "; the first fire was at " + t.describe(fire));
        t.assertTrue(burn.getBefore().is(Blocks.WHITE_WOOL), "the burned block should be wool, but was "
                + burn.getBefore());
    }

    @HadronTest(timeout = 1500)
    @Covers(BlockIgniteEvent.class)
    void fireSpreadingIsAnIgniteByNature(TestContext t) {
        // Fire on netherrack never goes out; the wool ring around it gives the air beside the fire something to catch.
        clearWeather(t);
        BlockPos fire = t.pos(0, 1, 0);
        Fixtures.awaitTicking(t, fire);
        for (BlockPos pos : BlockPos.betweenClosed(t.pos(-1, 0, -1), t.pos(1, 0, 1))) {
            t.setBlock(pos, Blocks.WHITE_WOOL);
        }
        t.setBlock(fire.below(), Blocks.NETHERRACK);
        List<BlockIgniteEvent> ignites = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockIgniteEvent e) {
                if (!e.getPos().equals(fire)) ignites.add(e);
            }
        });
        t.setBlock(fire, Blocks.FIRE);
        for (int i = 0; i < 1200 && ignites.isEmpty(); i++) t.tick();
        if (ignites.isEmpty()) {
            List<String> fires = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(t.pos(-4, -1, -4), t.pos(4, 5, 4))) {
                if (t.getBlock(pos).is(Blocks.FIRE) && !pos.equals(fire)) fires.add(t.describe(pos.immutable()));
            }
            t.fail("No BlockIgniteEvent in 1200 ticks of fire beside wool. Fires now besides the first: " + fires
                    + "; the first is " + t.getBlock(fire) + ". Fires listed here spread without an event.");
        }
        assertNature(t, ignites.get(0).getActor());
    }

    @HadronTest(timeout = 900)
    @Covers(BlockBurnEvent.class)
    void cancelledBurnKeepsEveryBlock(TestContext t) {
        List<BlockBurnEvent> burns = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockBurnEvent e) {
                burns.add(e);
                e.setCancelled(true);
            }
        });
        BlockPos fire = fireInWool(t);
        t.await("fire to try burning a wool block", () -> !burns.isEmpty(), 600);
        t.tick(2);
        t.assertBlock(fire.below(), Blocks.WHITE_WOOL);
        for (Direction side : Direction.Plane.HORIZONTAL) t.assertBlock(fire.relative(side), Blocks.WHITE_WOOL);
    }

    // ---- TNT

    private static List<BlockBreakEvent> recordBreaks(TestContext t, boolean cancel) {
        List<BlockBreakEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockBreakEvent e) {
                seen.add(e);
                if (cancel) e.setCancelled(true);
            }
        });
        return seen;
    }

    @HadronTest
    @Covers(BlockBreakEvent.class)
    void lightingTntIsOneBreakByThePlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos tnt = t.pos(2, 0, 0);
        t.setBlock(tnt, Blocks.TNT);
        alice.hold(new ItemStack(Items.FLINT_AND_STEEL));
        List<BlockBreakEvent> seen = recordBreaks(t, false);
        alice.useBlock(tnt);
        t.assertBlock(tnt, Blocks.AIR);
        t.assertEquals(1, entities(t, PrimedTnt.class, tnt, 2), "the primed TNT");
        t.assertEquals(1, seen.size(), "the number of BlockBreakEvents for lighting TNT");
        assertPlayer(t, seen.get(0).getActor(), alice);
    }

    @HadronTest
    @Covers(BlockBreakEvent.class)
    void cancelledTntLightingLeavesTheTntUnprimedAndTheFlintUnworn(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos tnt = t.pos(2, 0, 0);
        t.setBlock(tnt, Blocks.TNT);
        alice.hold(new ItemStack(Items.FLINT_AND_STEEL));
        List<BlockBreakEvent> tried = recordBreaks(t, true);
        SideEffects effects = t.watchSideEffects();
        alice.useBlock(tnt);
        t.tick();
        t.assertEquals(1, tried.size(), "alice's attempts to light the TNT");
        t.assertBlock(tnt, Blocks.TNT);
        t.assertBlock(tnt.above(), Blocks.AIR); // the flint and steel mustn't light a fire on it instead
        t.assertEquals(0, entities(t, PrimedTnt.class, tnt, 2), "the primed TNT");
        t.assertEquals(0, alice.player().getMainHandItem().getDamageValue(), "the flint and steel's damage");
        effects.assertNone("a refused TNT lighting");
    }

    private static void awaitDispense(TestContext t, BlockPos at, DispenserBlockEntity dispenser,
            java.util.function.BooleanSupplier done) {
        for (int i = 0; i < 40 && !done.getAsBoolean(); i++) t.tick();
        if (!done.getAsBoolean()) {
            t.fail("The dispenser never lit the TNT, 46 ticks after being powered. Dispenser: " + t.getBlock(at)
                    + ", holding " + dispenser.getItem(0) + "; in front: " + t.getBlock(at.east()) + "; chunk ticking: "
                    + t.level().isPositionEntityTicking(at) + "; block ticks pending there: "
                    + t.level().getBlockTicks().hasScheduledTick(at, Blocks.DISPENSER));
        }
    }

    @HadronTest
    @Covers(BlockBreakEvent.class)
    void aDispenserLightingTntIsOneBreakByTheMachine(TestContext t) {
        BlockPos at = t.pos(2, 0, 0);
        t.setBlock(at.east(), Blocks.TNT);
        DispenserBlockEntity dispenser = dispenser(t, at, Direction.EAST, new ItemStack(Items.FLINT_AND_STEEL));
        List<BlockBreakEvent> seen = recordBreaks(t, false);
        fire(t, at, Direction.EAST);
        // KNOWN FLAKE: in 2 of 5 runs the dispenser hadn't lit the TNT 6 ticks after being powered (bucket dispensers
        // with the same set-up never missed). Waiting for the effect, and saying what the dispenser looks like if it
        // never comes, until the cause is found.
        awaitDispense(t, at, dispenser, () -> !t.getBlock(at.east()).is(Blocks.TNT));
        t.assertBlock(at.east(), Blocks.AIR);
        t.assertEquals(1, entities(t, PrimedTnt.class, at.east(), 2), "the primed TNT");
        t.assertEquals(1, seen.size(), "the number of BlockBreakEvents for a dispenser lighting TNT");
        assertMachineAt(t, seen.get(0).getActor(), at);
    }

    @HadronTest
    @Covers(BlockBreakEvent.class)
    void cancelledDispenserTntLightingPrimesNothingAndWearsNothing(TestContext t) {
        BlockPos at = t.pos(2, 0, 0);
        t.setBlock(at.east(), Blocks.TNT);
        DispenserBlockEntity dispenser = dispenser(t, at, Direction.EAST, new ItemStack(Items.FLINT_AND_STEEL));
        List<BlockBreakEvent> tried = recordBreaks(t, true);
        SideEffects effects = t.watchSideEffects();
        fire(t, at, Direction.EAST);
        awaitDispense(t, at, dispenser, () -> !tried.isEmpty());
        t.assertEquals(1, tried.size(), "the dispenser's attempts to light the TNT (TNT block now: "
                + t.getBlock(at.east()).getBlock() + ", primed TNT: " + entities(t, PrimedTnt.class, at.east(), 2)
                + ", dispenser holds: " + dispenser.getItem(0) + ", triggered: "
                + t.getBlock(at).getValue(net.minecraft.world.level.block.DispenserBlock.TRIGGERED) + ")");
        t.assertBlock(at.east(), Blocks.TNT);
        t.assertEquals(0, entities(t, PrimedTnt.class, at.east(), 2), "the primed TNT");
        t.assertTrue(dispenser.getItem(0).is(Items.FLINT_AND_STEEL), "the dispenser should still hold the flint "
                + "and steel, but holds " + dispenser.getItem(0));
        t.assertEquals(0, dispenser.getItem(0).getDamageValue(), "the dispensed flint and steel's damage");
        effects.assertNone("a dispenser's refused TNT lighting");
    }
}
