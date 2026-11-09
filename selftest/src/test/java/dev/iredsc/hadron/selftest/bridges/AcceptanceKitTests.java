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
import dev.iredsc.hadron.api.event.entity.EntityTransformEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import java.util.List;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.entity.EntityType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.MinecartChest;

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

    @HadronTest
    void aPlayerInTheAreaHurtWithNoEventFailsAnAllowedStep(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.teleport(t.pos(2, 0, 0).getX() + 0.5, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5);
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        alice.player().setHealth(10);
        String why = failure(t, () -> area.assertEveryChangeSeen("a drain on a player standing in the area"));
        t.assertTrue(why.contains("alice's health went from 20.0 to 10.0"), why);
    }

    @HadronTest
    void anAllowedExplosionReportsItsBlocks(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos dirt = t.pos(2, 0, 0);
        t.setBlock(dirt, Blocks.DIRT);
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        try (Actors.Scope s = Actors.push(new Actor.Player(alice.player()))) {
            t.level().explode(null, dirt.getX() + 0.5, dirt.getY() + 0.5, dirt.getZ() + 0.5, 2f,
                    Level.ExplosionInteraction.TNT);
        }
        t.tick(2);
        t.assertBlock(dirt, Blocks.AIR);
        area.assertEveryChangeSeen("alice's blast in an area she is trusted in");
        area.assertActingFor(alice, "alice's blast in an area she is trusted in");
    }

    @HadronTest
    void aBlastJustOutsideTheAreaIsRefusedForTheBlocksInIt(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos dirt = t.pos(1, 0, 0);
        t.setBlock(dirt, Blocks.DIRT);
        Acceptance area = t.acceptance(around(t));
        try (Actors.Scope s = Actors.push(new Actor.Player(bob.player()))) {
            BlockPos outside = t.pos(0, 0, 0);
            t.level().explode(null, outside.getX() + 0.5, outside.getY() + 0.5, outside.getZ() + 0.5, 2f,
                    Level.ExplosionInteraction.TNT);
        }
        t.tick(2);
        area.assertUnchanged("bob's blast beside an area he isn't trusted in");
        // The blast reached into the area: protection placed it there, by a block of its list inside the box.
        t.assertTrue(area.events().stream().anyMatch(e -> e.event() instanceof ExplosionEvent && e.cancelled()
                && e.pos().isPresent()), "protection refused the blast reaching into the area: " + area.events());
        t.assertBlock(dirt, Blocks.DIRT);
    }

    @HadronTest
    void eventsOnlyTheServerIsBehindShowNobodyActed(TestContext t) {
        TestPlayer alice = t.player("alice");
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        try (Actors.Scope s = Actors.push(new Actor.Server("a bridge hiding its owner"))) {
            t.level().setBlock(t.pos(2, 0, 0), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        String why = failure(t, () -> area.assertActingFor(alice, "a machine acting as the server"));
        t.assertTrue(why.contains("no event in the area acts for anyone"), why);
    }

    @HadronTest
    void anEntityBornOutsideThatWalksInIsNotAChange(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(t.pos(-5, 0, 0).getX() + 0.5, t.pos(-5, 0, 0).getY(), t.pos(-5, 0, 0).getZ() + 0.5);
        try (Actors.Scope s = Actors.push(new Actor.Player(alice.player()))) {
            t.level().addFreshEntity(pig);
        }
        pig.teleportTo(t.pos(2, 0, 1).getX() + 0.5, t.pos(2, 0, 1).getY(), t.pos(2, 0, 1).getZ() + 0.5);
        alice.breakBlock(stone);
        t.tick(2);
        area.assertEveryChangeSeen("alice breaking stone as a pig born outside walks in");
    }

    @HadronTest
    void aBlockRemovedAfterItWasUsedStillChecks(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(1, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        alice.hold(new ItemStack(Items.IRON_AXE));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        alice.useBlock(pos);
        alice.breakBlock(pos);
        t.tick(2);
        t.assertBlock(pos, Blocks.AIR);
        area.assertEveryChangeSeen("alice opening a chest, then breaking it");
    }

    @HadronTest
    void oneReportedSlotDoesNotCoverAnother(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(2, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        ChestBlockEntity chest = (ChestBlockEntity) t.level().getBlockEntity(pos);
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 3);
        Hadron.getPluginManager().callEvent(new InventoryChangeEvent(List.of(new InventoryChangeEvent.Change(
                new InventoryHolder.Block(t.level(), pos), 0, ItemStack.EMPTY, diamonds.copy())),
                new Actor.Player(alice.player())));
        chest.setItem(0, diamonds.copy());
        chest.setItem(1, new ItemStack(Items.EMERALD, 5));
        String why = failure(t, () -> area.assertEveryChangeSeen("a machine reporting one slot and filling two"));
        t.assertTrue(why.contains("slot 1") && why.contains("emerald") && !why.contains("slot 0"), why);
    }

    @HadronTest
    void takingFromAChestByClickingIsSeen(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(1, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        ((ChestBlockEntity) t.level().getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, 3));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        alice.useBlock(pos);
        alice.clickSlot(0);
        t.assertTrue(alice.player().containerMenu.getCarried().is(Items.DIAMOND), "alice took the diamonds");
        area.assertEveryChangeSeen("alice taking diamonds from a chest");
    }

    @HadronTest
    void anOutsiderWalksThroughTheAreaFreelyAndUnlogged(TestContext t) {
        TestPlayer bob = t.player("bob");
        Acceptance area = t.acceptance(around(t));
        BlockPos inside = t.pos(2, 0, 0);
        bob.moveTo(inside);
        t.tick(2);
        t.assertEquals(inside, bob.player().blockPosition(), "where bob stands");
        t.assertTrue(area.events().isEmpty(), "bob's walk was logged: " + area.events());
    }

    @HadronTest
    void aCartEmptiedWithNoEventIsNamed(TestContext t) {
        BlockPos at = t.pos(2, 0, 0);
        MinecartChest cart = new MinecartChest(t.level(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        cart.setItem(0, new ItemStack(Items.DIAMOND, 3));
        t.level().addFreshEntity(cart);
        Acceptance area = t.acceptance(around(t));
        cart.setItem(0, ItemStack.EMPTY);
        String why = failure(t, () -> area.assertUnchanged("a pipe draining a chest minecart"));
        t.assertTrue(why.contains("chest_minecart") && why.contains("slot 0") && why.contains("diamond"), why);
    }

    @HadronTest
    void aReportFromAnotherWorldExplainsNothingHere(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(2, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 3);
        // The same coordinates in the overworld, and a slot in the area so the event is heard here.
        Hadron.getPluginManager().callEvent(new InventoryChangeEvent(List.of(
                new InventoryChangeEvent.Change(new InventoryHolder.Block(t.server().overworld(), pos), 0,
                        ItemStack.EMPTY, diamonds.copy()),
                new InventoryChangeEvent.Change(new InventoryHolder.Block(t.level(), t.pos(3, 0, 0)), 0,
                        ItemStack.EMPTY, ItemStack.EMPTY)), new Actor.Player(alice.player())));
        ((ChestBlockEntity) t.level().getBlockEntity(pos)).setItem(0, diamonds.copy());
        String why = failure(t, () -> area.assertEveryChangeSeen("a write excused by another world's report"));
        t.assertTrue(why.contains("the inventory at") && why.contains("diamond"), why);
    }

    @HadronTest
    void aChangeOutsideTheAreaIsNoWorkInIt(TestContext t) {
        TestPlayer alice = t.player("alice");
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        alice.player().setHealth(10);
        String why = failure(t, () -> area.assertEveryChangeSeen("alice hurt far from the area"));
        t.assertTrue(why.contains("nothing in the area changed since the last mark"), why);
    }

    @HadronTest
    void aPlayerJoiningAfterTheMarkIsRefused(TestContext t) {
        Acceptance area = t.acceptance(around(t));
        TestPlayer alice = t.player("alice");
        alice.player().setHealth(10);
        String why = failure(t, () -> area.assertUnchanged("alice hurt after joining late"));
        t.assertTrue(why.contains("Join every player before calling mark()"), why);
    }

    @HadronTest
    void anEntityHurtInTheAreaThenWalkingOutIsStillChecked(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(t.pos(2, 0, 0).getX() + 0.5, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5);
        t.level().addFreshEntity(pig);
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        pig.setHealth(pig.getHealth() - 4);
        pig.teleportTo(t.pos(-5, 0, 0).getX() + 0.5, t.pos(-5, 0, 0).getY(), t.pos(-5, 0, 0).getZ() + 0.5);
        String why = failure(t, () -> area.assertEveryChangeSeen("a pig hurt unreported, then walking off"));
        t.assertTrue(why.contains("health"), why);
    }

    @HadronTest
    void aMachineReportingWhatItGaveAPlayerIsSeen(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos in = t.pos(2, 0, 0);
        alice.teleport(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 3);
        Actor machine = new Actor.Machine(t.level(), t.pos(3, 0, 0), Attribution.of(alice.uuid()));
        Hadron.getPluginManager().callEvent(new InventoryChangeEvent(List.of(new InventoryChangeEvent.Change(
                new InventoryHolder.Entity(alice.player()), 0, ItemStack.EMPTY, diamonds.copy())), machine));
        alice.player().getInventory().setItem(0, diamonds.copy());
        area.assertEveryChangeSeen("alice's machine handing her diamonds");
    }

    @HadronTest(timeout = 200)
    void aHopperPickingUpIsSeen(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos hopper = t.pos(2, 0, 0);
        t.setBlock(hopper, Blocks.HOPPER);
        Actors.remember(t.level().getBlockEntity(hopper), Attribution.of(alice.uuid()));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        ItemEntity item = new ItemEntity(t.level(), hopper.getX() + 0.5, hopper.getY() + 1.2, hopper.getZ() + 0.5,
                new ItemStack(Items.DIAMOND, 3));
        t.level().addFreshEntity(item);
        t.await("the hopper to pick the diamonds up", item::isRemoved, 60);
        area.assertEveryChangeSeen("alice's hopper picking up diamonds");
    }

    @HadronTest
    void aTransformedMobThatWalksOffIsStillItsTransformation(TestContext t) {
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(t.pos(1, 0, -2).getX() + 0.5, t.pos(1, 0, -2).getY(), t.pos(1, 0, -2).getZ() + 0.5);
        t.level().addFreshEntity(pig);
        Acceptance area = t.acceptance(around(t));
        ZombifiedPiglin piglin;
        // As vanilla's callers do before converting (a lightning strike): the event, then the conversion.
        Actor ritual = new Actor.Server("a ritual");
        t.assertFalse(Hadron.getPluginManager().callEvent(new EntityTransformEvent(pig, EntityType.ZOMBIFIED_PIGLIN,
                ritual)), "the server's transformation went through");
        try (Actors.Scope s = Actors.push(ritual)) {
            piglin = pig.convertTo(EntityType.ZOMBIFIED_PIGLIN, false);
        }
        t.assertTrue(piglin != null, "the pig converted");
        piglin.teleportTo(t.pos(3, 0, 2).getX() + 0.5, t.pos(3, 0, 2).getY(), t.pos(3, 0, 2).getZ() + 0.5);
        area.assertEveryChangeSeen("a pig turned into a piglin that wandered across the area");
    }

    @HadronTest
    void aChangeInsideAnItemCountsForTheSlotHoldingIt(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(2, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) t.level().getBlockEntity(pos);
        chest.setItem(5, new ItemStack(Items.SHULKER_BOX));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        ItemStack box = chest.getItem(5);
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 3);
        Hadron.getPluginManager().callEvent(new InventoryChangeEvent(List.of(new InventoryChangeEvent.Change(
                new InventoryHolder.Item(new InventoryHolder.Block(t.level(), pos), box), 0, ItemStack.EMPTY,
                diamonds.copy())), new Actor.Player(alice.player())));
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(diamonds.copy())));
        chest.setItem(0, new ItemStack(Items.EMERALD, 2));
        String why = failure(t, () -> area.assertEveryChangeSeen("a backpack filled and a chest slot written"));
        t.assertTrue(why.contains("slot 0") && why.contains("emerald") && !why.contains("slot 5"), why);
    }

    @HadronTest
    void aClickInAMenuOverAMachinesHandlerIsSeen(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(2, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        // A modded machine's menu: its slots sit over a stand-in container (NeoForge's SlotItemHandler passes an
        // empty SimpleContainer), not over the block, so only the click's holder says whose inventory it is.
        ChestMenu menu = ChestMenu.threeRows(1, alice.player().getInventory(), new SimpleContainer(27));
        Hadron.getPluginManager().callEvent(new InventoryClickEvent(alice.player(),
                new InventoryHolder.Block(t.level(), pos), menu, 0, 0, ClickType.PICKUP, new Actor.Player(alice.player())));
        ((ChestBlockEntity) t.level().getBlockEntity(pos)).setItem(0, new ItemStack(Items.IRON_ORE, 4));
        area.assertEveryChangeSeen("alice putting ore into a machine by hand");
    }

    @HadronTest
    void aSpawnReportedOutsideTheAreaDoesNotExcuseAMobInIt(TestContext t) {
        TestPlayer bob = t.player("bob");
        Acceptance area = t.acceptance(around(t));
        Pig pig = EntityType.PIG.create(t.level());
        // A bridge firing the spawn before placing its mob, so the event says where the mob isn't.
        pig.moveTo(t.pos(-5, 0, 0).getX() + 0.5, t.pos(-5, 0, 0).getY(), t.pos(-5, 0, 0).getZ() + 0.5);
        try (Actors.Scope s = Actors.push(new Actor.Player(bob.player()))) {
            t.level().addFreshEntity(pig);
        }
        pig.teleportTo(t.pos(2, 0, 0).getX() + 0.5, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5);
        String why = failure(t, () -> area.assertUnchanged("bob's mob placed in an area he isn't trusted in"));
        t.assertTrue(why.contains("minecraft:pig appeared"), why);
    }

    @HadronTest
    void anInventoryEventWithNoHolderSaysToNameIt(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos pos = t.pos(2, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        Acceptance area = t.acceptance(around(t));
        Hadron.getPluginManager().callEvent(new InventoryChangeEvent(List.of(new InventoryChangeEvent.Change(
                InventoryHolder.Unknown.INSTANCE, 0, ItemStack.EMPTY, new ItemStack(Items.DIAMOND))),
                new Actor.Player(bob.player())));
        ((ChestBlockEntity) t.level().getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND));
        String why = failure(t, () -> area.assertUnchanged("a pipe that can't say which chest it filled"));
        t.assertTrue(why.contains("fired with an Unknown holder") && why.contains("name the holder"), why);
    }
}
