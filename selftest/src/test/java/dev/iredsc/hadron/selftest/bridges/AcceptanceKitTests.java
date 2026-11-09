package dev.iredsc.hadron.selftest.bridges;

import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.test.Acceptance;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import dev.iredsc.hadron.api.event.block.BlockUseEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;

/**
 * The acceptance kit on vanilla blocks: it passes what core already covers, and each assertion fails, naming the
 * right gap, when a change skips the events, a cancel is ignored, or nobody is behind an action. A kit that passed
 * those would pass a bridge that covers nothing.
 */
public final class AcceptanceKitTests {
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

    @HadronTest
    void anOutsidersBreakIsDeniedAndLeavesTheAreaAsItWas(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        bob.hold(new ItemStack(Items.IRON_PICKAXE));
        Acceptance area = t.acceptance(around(t));
        bob.breakBlock(stone);
        t.tick(2);
        area.assertUnchanged("bob breaking stone in a region he isn't trusted in");
        t.assertTrue(area.events().stream().anyMatch(s -> s.event() instanceof BlockBreakEvent && s.cancelled()),
                "protection cancelled the break: " + area.events());
    }

    @HadronTest
    void aTrustedPlayersChangesAreSeenAndActForThem(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        alice.breakBlock(stone);
        t.tick(2);
        t.assertBlock(stone, Blocks.AIR);
        area.assertEveryChangeSeen("alice breaking stone where she is trusted");
        area.assertActingFor(alice, "alice breaking stone where she is trusted");
        t.assertFalse(area.events().isEmpty(), "the break was recorded");
    }

    @HadronTest
    void aChangeThatSkipsEveryChokepointIsAnInterceptionGap(TestContext t) {
        BlockPos pos = t.pos(2, 0, 0);
        Acceptance area = t.acceptance(around(t));
        // A direct chunk write, as a mod that skips Level.setBlock makes: nothing fires for it.
        t.level().getChunkAt(pos).setBlockState(pos, Blocks.GOLD_BLOCK.defaultBlockState(), false);
        String unchanged = failure(t, () -> area.assertUnchanged("a mod's direct chunk write"));
        t.assertTrue(unchanged.contains("no event fired for it. An interception gap"), unchanged);
        String unseen = failure(t, () -> area.assertEveryChangeSeen("a mod's direct chunk write"));
        String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        t.assertTrue(unseen.contains("interception gap") && unseen.contains(at + " went from")
                && unseen.contains("gold_block"), unseen);
    }

    @HadronTest
    void aChangeMadeDespiteACancelIsNamedAsTheCancelsResidue(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos pos = t.pos(2, 0, 0);
        t.setBlock(pos, Blocks.STONE);
        Acceptance area = t.acceptance(around(t));
        // A bridge that fires, sees the cancel, and makes the change anyway.
        BlockBreakEvent event = new BlockBreakEvent(t.level(), pos, Blocks.STONE.defaultBlockState(),
                Blocks.AIR.defaultBlockState(), new Actor.Player(bob.player()));
        t.assertTrue(Hadron.getPluginManager().callEvent(event), "protection cancelled bob's break");
        t.level().getChunkAt(pos).setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
        String why = failure(t, () -> area.assertUnchanged("a bridge ignoring a cancel"));
        t.assertTrue(why.contains("BlockBreakEvent") && why.contains("the cancel left this behind"), why);
    }

    @HadronTest
    void unknownAndOwnerlessActorsAreAttributionGaps(TestContext t) {
        TestPlayer alice = t.player("alice");
        Acceptance area = t.acceptance(around(t));
        area.trust(alice);
        // Nobody on the actor stack in a watched area: core fires it as Unknown, and protection refuses it.
        t.level().setBlock(t.pos(2, 0, 0), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // A machine nobody owns.
        try (Actors.Scope s = Actors.push(new Actor.Machine(t.level(), t.pos(3, 0, 0), Attribution.none()))) {
            t.level().setBlock(t.pos(2, 0, 1), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        t.assertBlock(t.pos(2, 0, 0), Blocks.AIR);
        t.assertBlock(t.pos(2, 0, 1), Blocks.AIR);
        String why = failure(t, () -> area.assertActingFor(alice, "an unbridged mod's work"));
        t.assertTrue(why.contains("Unknown, so nothing was on the actor stack"), why);
        t.assertTrue(why.contains("nobody is responsible for it"), why);
        area.assertUnchanged("an unbridged mod's work, refused");
    }

    @HadronTest
    void aBlockEventExplainsItsInventoryOnlyWhenTheBlockChanged(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(2, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        // A machine reports a change at its own block, then fills the chest there with no inventory event.
        Hadron.getPluginManager().callEvent(new BlockBreakEvent(t.level(), pos, Blocks.CHEST.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(), new Actor.Player(alice.player())));
        ((ChestBlockEntity) t.level().getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, 3));
        String why = failure(t, () -> area.assertEveryChangeSeen("a machine filling a chest unreported"));
        t.assertTrue(why.contains("the inventory at") && why.contains("diamond"), why);
    }

    @HadronTest
    void aHurtThatSkipsTheEventsIsNamed(TestContext t) {
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(t.pos(2, 0, 0).getX() + 0.5, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5);
        t.level().addFreshEntity(pig);
        Acceptance area = t.acceptance(around(t));
        pig.setHealth(pig.getHealth() - 4);
        String why = failure(t, () -> area.assertUnchanged("a weapon setting health directly"));
        t.assertTrue(why.contains("health") && why.contains("no event fired for it"), why);
    }

    @HadronTest
    void trustingNobodyDeniesAPlayerTrustedBefore(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).trustNobody().mark();
        alice.breakBlock(stone);
        t.tick(2);
        area.assertUnchanged("alice breaking stone after trust was withdrawn");
    }

    @HadronTest
    void aStepWhereNothingChangedProvesNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        String why = failure(t, () -> area.assertEveryChangeSeen("a machine that never started"));
        t.assertTrue(why.contains("nothing in the area changed since the last mark"), why);
    }

    @HadronTest
    void aUseThatChangesNothingExplainsNoChange(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(2, 0, 0);
        t.setBlock(pos, Blocks.STONE);
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        // Alice right-clicks a machine, which then rewrites its own block with no event.
        BlockState stone = Blocks.STONE.defaultBlockState();
        Hadron.getPluginManager().callEvent(new BlockUseEvent(t.level(), pos, stone, InteractionHand.MAIN_HAND,
                ItemStack.EMPTY, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false),
                new Actor.Player(alice.player())));
        t.level().getChunkAt(pos).setBlockState(pos, Blocks.GOLD_BLOCK.defaultBlockState(), false);
        String why = failure(t, () -> area.assertEveryChangeSeen("a machine rewriting itself after a use"));
        t.assertTrue(why.contains("gold_block"), why);
    }

    @HadronTest
    void aChangeMadeDespiteACancelIsNamedWhenCheckingAnAllowedStep(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos pos = t.pos(2, 0, 0);
        t.setBlock(pos, Blocks.STONE);
        Acceptance area = t.acceptance(around(t));
        Hadron.getPluginManager().callEvent(new BlockBreakEvent(t.level(), pos, Blocks.STONE.defaultBlockState(),
                Blocks.AIR.defaultBlockState(), new Actor.Player(bob.player())));
        t.level().getChunkAt(pos).setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
        String why = failure(t, () -> area.assertEveryChangeSeen("a bridge ignoring a cancel"));
        t.assertTrue(why.contains("fired here but was cancelled, and the change happened anyway"), why);
    }

    @HadronTest
    void anAllowedChangeInARefusedStepIsNamedAsLetThrough(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        alice.breakBlock(stone);
        t.tick(2);
        String why = failure(t, () -> area.assertUnchanged("a step expected to be refused"));
        t.assertTrue(why.contains("BlockBreakEvent") && why.contains("went through: protection let it"), why);
    }

    @HadronTest
    void anEntityWalkingOutIsNotAChangeAndNatureIsNobodysToAttribute(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(t.pos(2, 0, 1).getX() + 0.5, t.pos(2, 0, 1).getY(), t.pos(2, 0, 1).getZ() + 0.5);
        t.level().addFreshEntity(pig);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        alice.breakBlock(stone);
        pig.teleportTo(t.pos(-5, 0, 0).getX() + 0.5, t.pos(-5, 0, 0).getY(), t.pos(-5, 0, 0).getZ() + 0.5);
        // Gravel falling or water flowing in the area: the world on its own.
        Hadron.getPluginManager().callEvent(new BlockBreakEvent(t.level(), t.pos(3, 0, 0),
                Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Actor.Nature.INSTANCE));
        t.tick(2);
        area.assertEveryChangeSeen("alice breaking stone while a pig wanders off");
        area.assertActingFor(alice, "alice breaking stone beside nature at work");
    }

    @HadronTest
    void aPlayerHurtWithNoEventIsNamed(TestContext t) {
        TestPlayer alice = t.player("alice");
        Acceptance area = t.acceptance(around(t));
        alice.player().setHealth(10);
        String why = failure(t, () -> area.assertUnchanged("a weapon setting a player's health directly"));
        t.assertTrue(why.contains("alice's health went from 20.0 to 10.0"), why);
    }

    @HadronTest
    void openingAChestExplainsNoChangeToIt(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(2, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        alice.useBlock(pos);
        ((ChestBlockEntity) t.level().getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, 3));
        String why = failure(t, () -> area.assertEveryChangeSeen("a machine filling a chest someone opened"));
        t.assertTrue(why.contains("the inventory at") && why.contains("diamond"), why);
    }
}
