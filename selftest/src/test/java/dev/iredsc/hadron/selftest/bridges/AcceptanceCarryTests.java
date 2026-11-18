package dev.iredsc.hadron.selftest.bridges;

import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.block.BlockChange;
import dev.iredsc.hadron.api.event.block.BlockMoveEvent;
import dev.iredsc.hadron.api.event.block.StructureMoveEvent;
import dev.iredsc.hadron.test.Acceptance;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.joml.Matrix4d;

/**
 * The acceptance kit and what a structure move carries: a refused ship that moves its crew anyway is a change, and a
 * ship let through must name every entity it moves in {@code getCarried()}, never leave it unknown.
 */
public final class AcceptanceCarryTests {
    private static BoundingBox around(TestContext t) {
        return BoundingBox.fromCorners(t.pos(1, 0, -2), t.pos(3, 2, 2));
    }

    /** The failure the assertion throws, or a failure of the test when it throws none. */
    private static String failure(TestContext t, Runnable assertion) {
        try {
            assertion.run();
        } catch (AssertionError e) {
            return e.getMessage();
        }
        t.fail("the assertion passed, but the step left a gap it should have named");
        return "";
    }

    /** A pig standing still at {@code at}, after a tick, so the world lists it wherever it stands. */
    private static Pig pig(TestContext t, BlockPos at) {
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        pig.setNoAi(true);
        t.level().addFreshEntity(pig);
        t.tick();
        return pig;
    }

    /** A still pig standing on planks at pos(2, 0, 0), in the area: a ship's one crew member. */
    private static Pig crew(TestContext t) {
        t.setBlock(t.pos(2, 0, 0), Blocks.OAK_PLANKS);
        return pig(t, t.pos(2, 1, 0));
    }

    /** bob's ship moving its planks one block east, carrying {@code crew}; whether it was cancelled. */
    private static boolean sail(TestContext t, TestPlayer bob, List<Entity> crew) {
        return Hadron.getPluginManager().callEvent(ship(t, bob, (from, to, moves, blocks) -> new StructureMoveEvent(
                t.level(), from, Blocks.OAK_PLANKS.defaultBlockState(), StructureMoveEvent.Phase.MOVE, new Matrix4d(),
                new Matrix4d().translation(1, 0, 0), moves, List.of(to), blocks, crew, new Actor.Player(bob.player()))));
    }

    /** The same, by a bridge that can't see its crew: unknown, somewhere around the deck. */
    private static boolean sailUntraced(TestContext t, TestPlayer bob) {
        return Hadron.getPluginManager().callEvent(ship(t, bob, (from, to, moves, blocks) -> new StructureMoveEvent(
                t.level(), from, Blocks.OAK_PLANKS.defaultBlockState(), StructureMoveEvent.Phase.MOVE, new Matrix4d(),
                new Matrix4d().translation(1, 0, 0), moves, List.of(to), blocks,
                Carried.unknown(new Place.Spot(t.level(), BoundingBox.fromCorners(from.above(), to.above(2))),
                        Carried.Unknown.Reason.UNTRACED), new Actor.Player(bob.player()))));
    }

    private interface Ship {
        StructureMoveEvent build(BlockPos from, BlockPos to, List<BlockMoveEvent.Move> moves, List<BlockChange> blocks);
    }

    private static StructureMoveEvent ship(TestContext t, TestPlayer bob, Ship ship) {
        BlockPos from = t.pos(2, 0, 0), to = t.pos(3, 0, 0);
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        return ship.build(from, to, List.of(new BlockMoveEvent.Move(from, to, planks)),
                List.of(BlockChange.unchanged(t.level(), from, planks)));
    }

    /** A bridge that moves its crew though protection refused the ship's move. */
    @HadronTest
    void aRefusedStructureMoveThatStillMovesItsCrewIsAChange(TestContext t) {
        TestPlayer bob = t.player("bob");
        Pig pig = crew(t);
        Acceptance area = t.acceptance(around(t));
        t.assertTrue(sail(t, bob, List.of(pig)), "bob's ship, bob not trusted, should be refused");
        pig.setPos(pig.getX() + 1, pig.getY(), pig.getZ()); // the bridge moves its crew anyway
        String why = failure(t, () -> area.assertUnchanged("bob's ship where he isn't trusted"));
        t.assertTrue(why.contains("though the move was refused") && why.contains("Pig"), why);
    }

    @HadronTest
    void anAllowedStructureMoveThatMovesAnEntityItDoesntNameIsReported(TestContext t) {
        TestPlayer bob = t.player("bob");
        Pig pig = crew(t);
        Acceptance area = t.acceptance(around(t));
        area.trust(bob).mark();
        t.assertFalse(sail(t, bob, List.of()), "bob's ship, bob trusted, should go");
        pig.setPos(pig.getX() + 1, pig.getY(), pig.getZ());
        String why = failure(t, () -> area.assertActingFor(bob, "bob's ship"));
        t.assertTrue(why.contains("getCarried() doesn't name it") && why.contains("Pig"), why);
    }

    @HadronTest
    void anAllowedStructureMoveNamingItsCrewPasses(TestContext t) {
        TestPlayer bob = t.player("bob");
        Pig pig = crew(t);
        Acceptance area = t.acceptance(around(t));
        area.trust(bob).mark();
        t.assertFalse(sail(t, bob, List.of(pig)), "bob's ship, bob trusted, should go");
        pig.setPos(pig.getX() + 1, pig.getY(), pig.getZ());
        area.assertActingFor(bob, "bob's ship carrying its pig");
    }

    @HadronTest
    void aStructureMoveThatCantSayWhatItCarriesIsReported(TestContext t) {
        TestPlayer bob = t.player("bob");
        crew(t);
        Acceptance area = t.acceptance(around(t));
        area.trust(bob).mark();
        sailUntraced(t, bob);
        String why = failure(t, () -> area.assertActingFor(bob, "bob's ship"));
        t.assertTrue(why.contains("getCarried() is Unknown (UNTRACED)"), why);
    }

    /** A ship outside the area carrying a pig that stands in it is in the area: protection refuses it. */
    @HadronTest
    void aStructureMoveCarryingAnEntityInTheAreaIsRefused(TestContext t) {
        TestPlayer bob = t.player("bob");
        Pig pig = pig(t, t.pos(2, 0, 0));
        Acceptance area = t.acceptance(around(t));
        BlockPos from = t.pos(-3, 0, 0), to = t.pos(-4, 0, 0);
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        boolean cancelled = Hadron.getPluginManager().callEvent(new StructureMoveEvent(t.level(), from, planks,
                StructureMoveEvent.Phase.MOVE, new Matrix4d(), new Matrix4d(),
                List.of(new BlockMoveEvent.Move(from, to, planks)), List.of(to),
                List.of(BlockChange.unchanged(t.level(), from, planks)), List.<Entity>of(pig),
                new Actor.Player(bob.player())));
        t.assertTrue(cancelled, "a ship carrying a pig out of the area should be refused: " + area.events());
    }

    /** A ship whose deck stays out of the area carries its crew up into it: refused for where the crew ends up. */
    @HadronTest
    void aShipCarryingItsCrewIntoTheAreaIsRefused(TestContext t) {
        TestPlayer bob = t.player("bob");
        Pig pig = crew(t);
        Acceptance area = t.acceptance(BoundingBox.fromCorners(t.pos(3, 1, 0), t.pos(3, 2, 0)));
        t.assertTrue(sail(t, bob, List.of(pig)), "bob's ship carrying its pig into the area should be "
                + "refused: " + area.events());
    }

    /** Each move answers for what it moved: an earlier move naming the pig doesn't cover a later one that doesn't. */
    @HadronTest
    void eachMoveIsCheckedAgainstItsOwnCarriage(TestContext t) {
        TestPlayer bob = t.player("bob");
        Pig pig = crew(t);
        Acceptance area = t.acceptance(around(t));
        area.trust(bob).mark();
        t.assertFalse(sail(t, bob, List.of(pig)), "the first move, naming its pig, should go");
        pig.setPos(pig.getX() + 0.1, pig.getY(), pig.getZ());
        t.assertFalse(sail(t, bob, List.of()), "the second move, naming nothing, should go");
        pig.setPos(pig.getX() + 0.1, pig.getY(), pig.getZ());
        String why = failure(t, () -> area.assertActingFor(bob, "two moves, the second leaving its pig out"));
        t.assertTrue(why.contains("getCarried() doesn't name it") && why.contains("Pig"), why);
    }
}
