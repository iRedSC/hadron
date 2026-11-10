package dev.iredsc.hadron.selftest.bridges;

import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.item.ItemDropEvent;
import dev.iredsc.hadron.api.event.item.ItemPickupEvent;
import dev.iredsc.hadron.api.event.item.ItemUseEvent;
import dev.iredsc.hadron.api.event.resource.Resource;
import dev.iredsc.hadron.api.event.resource.ResourceConsumeEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import dev.iredsc.hadron.test.Acceptance;
import dev.iredsc.hadron.test.Acceptance.Part;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * The acceptance kit on vanilla blocks: it passes what core already covers, and each assertion fails, naming the right
 * gap, when a change skips every event, a cancel is ignored, or nobody is behind an action. A kit that passed those
 * would pass a bridge that covers nothing.
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

    /** A pig at {@code at}, after a tick, so the world lists it wherever it stands. */
    private static Pig pig(TestContext t, BlockPos at) {
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        t.level().addFreshEntity(pig);
        t.tick();
        return pig;
    }

    // ---- Refused steps: nothing may change -------------------------------------------------------------------------

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
    void aChangeThatSkipsEveryChokepointIsAnInterceptionGap(TestContext t) {
        BlockPos pos = t.pos(2, 0, 0);
        Acceptance area = t.acceptance(around(t));
        // A direct chunk write, as a mod that skips Level.setBlock makes: nothing fires for it.
        t.level().getChunkAt(pos).setBlockState(pos, Blocks.GOLD_BLOCK.defaultBlockState(), false);
        String why = failure(t, () -> area.assertUnchanged("a mod's direct chunk write"));
        String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        t.assertTrue(why.contains(at + " went from") && why.contains("gold_block")
                && why.contains("no event was heard for it. An interception gap"), why);
    }

    @HadronTest
    void aChangeMadeDespiteACancelNamesTheCancelledEvent(TestContext t) {
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
        t.assertTrue(why.contains("BlockBreakEvent") && why.contains("the cancel left it behind"), why);
    }

    @HadronTest
    void anEntityHurtWithNoEventIsNamed(TestContext t) {
        Pig pig = pig(t, t.pos(2, 0, 0));
        Acceptance area = t.acceptance(around(t));
        pig.setHealth(pig.getHealth() - 4);
        String why = failure(t, () -> area.assertUnchanged("a weapon setting health directly"));
        t.assertTrue(why.contains("health") && why.contains("no event was heard"), why);
    }

    @HadronTest
    void anEntityHurtInTheAreaThenWalkingOutIsStillChecked(TestContext t) {
        Pig pig = pig(t, t.pos(2, 0, 0));
        Acceptance area = t.acceptance(around(t));
        pig.setHealth(pig.getHealth() - 4);
        pig.teleportTo(t.pos(-5, 0, 0).getX() + 0.5, t.pos(-5, 0, 0).getY(), t.pos(-5, 0, 0).getZ() + 0.5);
        String why = failure(t, () -> area.assertUnchanged("a pig hurt, then walking off", Part.ENTITIES));
        t.assertTrue(why.contains("health"), why);
    }

    @HadronTest
    void anEntityWalkingInIsNoChange(TestContext t) {
        // Outside the area but in the origin's chunk, whose entities the world lists from the start.
        Pig pig = pig(t, t.pos(0, 0, 4));
        Acceptance area = t.acceptance(around(t));
        pig.teleportTo(t.pos(2, 0, 0).getX() + 0.5, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5);
        area.assertUnchanged("a pig wandering into the area");
    }

    @HadronTest
    void aMobSpawnedOutsideAndPlacedInsideIsAChange(TestContext t) {
        TestPlayer bob = t.player("bob");
        Acceptance area = t.acceptance(around(t));
        // A bridge firing the spawn before placing its mob, so the event says where the mob isn't.
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(t.pos(-5, 0, 0).getX() + 0.5, t.pos(-5, 0, 0).getY(), t.pos(-5, 0, 0).getZ() + 0.5);
        try (Actors.Scope s = Actors.push(new Actor.Player(bob.player()))) {
            t.level().addFreshEntity(pig);
        }
        pig.teleportTo(t.pos(2, 0, 0).getX() + 0.5, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5);
        String why = failure(t, () -> area.assertUnchanged("bob's mob placed in an area he isn't trusted in"));
        t.assertTrue(why.contains("minecraft:pig appeared"), why);
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
    void aPlayerJoiningAfterTheMarkIsRefused(TestContext t) {
        Acceptance area = t.acceptance(around(t));
        TestPlayer alice = t.player("alice");
        alice.player().setHealth(10);
        String why = failure(t, () -> area.assertUnchanged("alice hurt after joining late"));
        t.assertTrue(why.contains("Join every player before calling mark()"), why);
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
        t.assertTrue(area.events().stream().anyMatch(e -> e.event() instanceof ExplosionEvent && e.cancelled()
                && e.pos().isPresent()), "protection refused the blast reaching into the area: " + area.events());
    }

    @HadronTest
    void aChunksResourceSpentByAnOutsiderIsRefused(TestContext t) {
        TestPlayer bob = t.player("bob");
        Acceptance area = t.acceptance(around(t));
        ResourceConsumeEvent drain = new ResourceConsumeEvent(new Actor.Player(bob.player()), Resource.ENERGY,
                new ResourceHolder.Chunk(t.level(), new ChunkPos(t.pos(2, 0, 0))), 100, 40);
        t.assertTrue(Hadron.getPluginManager().callEvent(drain), "protection refused a drain on the area's chunk");
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
    void anAllowedChangeInARefusedStepIsNamedAsLetThrough(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        alice.breakBlock(stone);
        t.tick(2);
        String why = failure(t, () -> area.assertUnchanged("a step expected to be refused", Part.BLOCKS));
        t.assertTrue(why.contains("BlockBreakEvent") && why.contains("none cancelled: protection let them through"),
                why);
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

    // ---- Refusing one kind: the machine runs, and what that kind covers must not happen -----------------------------

    @HadronTest
    void aTrustedPlayersRefusedKindIsDenied(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).refuse(BlockEvent.class).mark();
        alice.breakBlock(stone);
        t.tick(2);
        t.assertBlock(stone, Blocks.STONE);
        area.assertUnchanged("alice breaking stone with block changes refused", Part.BLOCKS);
        area.refuseNothing().mark();
        alice.breakBlock(stone);
        t.tick(2);
        t.assertBlock(stone, Blocks.AIR);
    }

    @HadronTest
    void aMachineFillingAChestUnreportedFailsWhenInventoriesAreRefused(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(2, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).refuse(InventoryEvent.class).mark();
        ((ChestBlockEntity) t.level().getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, 3));
        String why = failure(t, () -> area.assertUnchanged("alice's machine filling a chest", Part.INVENTORIES));
        t.assertTrue(why.contains("the inventory at") && why.contains("slot 0") && why.contains("no event was heard"),
                why);
    }

    @HadronTest
    void aMachineHonouringARefusedInventoryChangePasses(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(2, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).refuse(InventoryEvent.class).mark();
        Actor machine = new Actor.Machine(t.level(), t.pos(3, 0, 0), Attribution.of(alice.uuid()));
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 3);
        boolean cancelled = Hadron.getPluginManager().callEvent(new InventoryChangeEvent(List.of(
                new InventoryChangeEvent.Change(new InventoryHolder.Block(t.level(), pos), 0, ItemStack.EMPTY,
                        diamonds.copy())), machine));
        t.assertTrue(cancelled, "the refused kind came back cancelled");
        if (!cancelled) ((ChestBlockEntity) t.level().getBlockEntity(pos)).setItem(0, diamonds.copy());
        area.assertUnchanged("alice's machine honouring a refused fill", Part.INVENTORIES);
        area.assertActingFor(alice, "alice's machine");
    }

    @HadronTest
    void onlyThePartsAskedForAreCompared(TestContext t) {
        BlockPos pos = t.pos(2, 0, 0);
        Acceptance area = t.acceptance(around(t));
        t.level().getChunkAt(pos).setBlockState(pos, Blocks.GOLD_BLOCK.defaultBlockState(), false);
        area.assertUnchanged("a block write, checking inventories only", Part.INVENTORIES, Part.ENTITIES);
        failure(t, () -> area.assertUnchanged("a block write, checking blocks", Part.BLOCKS));
    }

    @HadronTest
    void refuseTakesOnlyEventClasses(TestContext t) {
        Acceptance area = t.acceptance(around(t));
        String why = "no refusal";
        try {
            area.refuse(String.class);
        } catch (IllegalArgumentException e) {
            why = e.getMessage();
        }
        t.assertTrue(why.contains("java.lang.String isn't a Hadron event"), why);
    }

    // ---- Attribution -----------------------------------------------------------------------------------------------

    @HadronTest
    void aTrustedPlayersOwnWorkActsForThem(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        alice.breakBlock(stone);
        t.tick(2);
        t.assertBlock(stone, Blocks.AIR);
        area.assertActingFor(alice, "alice breaking stone where she is trusted");
    }

    @HadronTest
    void unknownAndOwnerlessActorsAreAttributionGaps(TestContext t) {
        TestPlayer alice = t.player("alice");
        Acceptance area = t.acceptance(around(t));
        area.trust(alice);
        // Nobody on the actor stack in a watched area: core fires it as Unknown, and protection refuses it.
        t.level().setBlock(t.pos(2, 0, 0), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        try (Actors.Scope s = Actors.push(new Actor.Machine(t.level(), t.pos(3, 0, 0), Attribution.none()))) {
            t.level().setBlock(t.pos(2, 0, 1), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        String why = failure(t, () -> area.assertActingFor(alice, "an unbridged mod's work"));
        t.assertTrue(why.contains("Unknown, so nothing was on the actor stack"), why);
        t.assertTrue(why.contains("nobody is responsible for it"), why);
        area.assertUnchanged("an unbridged mod's work, refused");
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
    void natureIsNobodysToAttribute(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        alice.breakBlock(stone);
        Hadron.getPluginManager().callEvent(new BlockBreakEvent(t.level(), t.pos(3, 0, 0),
                Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Actor.Nature.INSTANCE));
        t.tick(2);
        area.assertActingFor(alice, "alice breaking stone beside nature at work");
    }

    @HadronTest
    void anEventWithNoHolderIsRefusedAndNamed(TestContext t) {
        TestPlayer alice = t.player("alice");
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        boolean cancelled = Hadron.getPluginManager().callEvent(new InventoryChangeEvent(List.of(
                new InventoryChangeEvent.Change(InventoryHolder.Unknown.INSTANCE, 0, ItemStack.EMPTY,
                        new ItemStack(Items.DIAMOND))), new Actor.Player(alice.player())));
        t.assertTrue(cancelled, "an event nothing can place is refused, even from someone trusted");
        String why = failure(t, () -> area.assertActingFor(alice, "a pipe that can't say which chest it filled"));
        t.assertTrue(why.contains("its holder is unknown") && why.contains("name the holder"), why);
    }

    // ---- Where events are placed -----------------------------------------------------------------------------------

    @HadronTest
    void aShiftClickIntoAMenuOpenOnABlockInTheAreaIsRefused(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos table = t.pos(2, 0, 0);
        t.setBlock(table, Blocks.CRAFTING_TABLE);
        Acceptance area = t.acceptance(around(t));
        // A menu whose slots sit over no block entity (a modded machine's over its item handler, a crafting grid),
        // open on a block in the area; bob, outside it, shift-clicks from his own inventory.
        CraftingMenu menu = new CraftingMenu(1, bob.player().getInventory(),
                ContainerLevelAccess.create(t.level(), table));
        boolean cancelled = Hadron.getPluginManager().callEvent(new InventoryClickEvent(bob.player(),
                new InventoryHolder.Entity(bob.player()), menu, 20, 0, ClickType.QUICK_MOVE,
                new Actor.Player(bob.player())));
        t.assertTrue(cancelled, "the click into a menu open on the area was refused: " + area.events());
    }

    @HadronTest
    void aPickupIntoAnInventoryInTheAreaIsRefused(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos hopper = t.pos(2, 0, 0);
        t.setBlock(hopper, Blocks.HOPPER);
        Acceptance area = t.acceptance(around(t));
        ItemEntity item = new ItemEntity(t.level(), t.pos(-3, 0, 0).getX() + 0.5, t.pos(-3, 0, 0).getY(),
                t.pos(-3, 0, 0).getZ() + 0.5, new ItemStack(Items.DIAMOND));
        boolean cancelled = Hadron.getPluginManager().callEvent(new ItemPickupEvent(t.level(), item,
                new InventoryHolder.Block(t.level(), hopper), new Actor.Player(bob.player())));
        t.assertTrue(cancelled, "a pickup into the area's hopper was refused: " + area.events());
    }

    @HadronTest
    void anItemUsedByAMachineInTheAreaIsRefused(TestContext t) {
        Pig pig = pig(t, t.pos(-3, 0, 0));
        Acceptance area = t.acceptance(around(t));
        Actor machine = new Actor.Machine(t.level(), t.pos(2, 0, 0), Attribution.none());
        boolean cancelled = Hadron.getPluginManager().callEvent(new ItemUseEvent(t.level(), pig,
                InteractionHand.MAIN_HAND, new ItemStack(Items.BONE_MEAL), machine));
        t.assertTrue(cancelled, "an item used by a machine standing in the area was refused: " + area.events());
    }

    @HadronTest
    void anItemDroppedIntoTheAreaIsRefused(TestContext t) {
        TestPlayer bob = t.player("bob");
        Acceptance area = t.acceptance(around(t));
        BlockPos in = t.pos(2, 0, 0);
        boolean cancelled = Hadron.getPluginManager().callEvent(new ItemDropEvent(t.level(),
                new Vec3(in.getX() + 0.5, in.getY(), in.getZ() + 0.5), new ItemStack(Items.DIAMOND),
                new Actor.Player(bob.player())));
        t.assertTrue(cancelled, "an item landing in the area was refused: " + area.events());
    }

    @HadronTest
    void aBlastCentredInTheAreaIsRefused(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos dirt = t.pos(2, 0, 0);
        t.setBlock(dirt, Blocks.DIRT);
        Acceptance area = t.acceptance(around(t));
        try (Actors.Scope s = Actors.push(new Actor.Player(bob.player()))) {
            t.level().explode(null, dirt.getX() + 0.5, dirt.getY() + 1.5, dirt.getZ() + 0.5, 2f,
                    Level.ExplosionInteraction.TNT);
        }
        t.tick(2);
        area.assertUnchanged("bob's blast in an area he isn't trusted in");
    }

    @HadronTest
    void refusedKindsAddUp(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(2, 0, 0);
        BlockPos chest = t.pos(3, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        t.setBlock(chest, Blocks.CHEST);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).refuse(BlockEvent.class).refuse(InventoryEvent.class).mark();
        alice.breakBlock(stone);
        t.tick(2);
        t.assertBlock(stone, Blocks.STONE);
        boolean cancelled = Hadron.getPluginManager().callEvent(new InventoryChangeEvent(List.of(
                new InventoryChangeEvent.Change(new InventoryHolder.Block(t.level(), chest), 0, ItemStack.EMPTY,
                        new ItemStack(Items.DIAMOND))), new Actor.Player(alice.player())));
        t.assertTrue(cancelled, "the second kind refused is refused too");
    }

    @HadronTest
    void assertingTwiceComparesWithTheSameMark(TestContext t) {
        Pig pig = pig(t, t.pos(2, 0, 0));
        Acceptance area = t.acceptance(around(t));
        // Out to where the world doesn't list entities yet: only following it keeps it from looking gone.
        pig.teleportTo(t.pos(-5, 0, 0).getX() + 0.5, t.pos(-5, 0, 0).getY(), t.pos(-5, 0, 0).getZ() + 0.5);
        area.assertUnchanged("a pig walking off", Part.ENTITIES);
        area.assertUnchanged("a pig walking off, asked again", Part.ENTITIES);
    }
}
