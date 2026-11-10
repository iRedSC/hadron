package dev.iredsc.hadron.selftest.bridges;

import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.ActionEvent;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockChange;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.block.BlockMoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityDataChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.entity.EntityMountEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.ProjectileHitEvent;
import dev.iredsc.hadron.api.event.item.EquipEvent;
import dev.iredsc.hadron.api.event.item.EquipmentChangeEvent;
import dev.iredsc.hadron.api.event.player.PlayerSwapHandItemsEvent;
import dev.iredsc.hadron.api.event.world.TickAccelerateEvent;
import dev.iredsc.hadron.api.event.world.WeatherChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.inventory.InventoryOpenEvent;
import dev.iredsc.hadron.api.event.item.ItemChangeEvent;
import dev.iredsc.hadron.api.event.item.ItemDropEvent;
import dev.iredsc.hadron.api.event.item.ItemMergeEvent;
import dev.iredsc.hadron.api.event.item.ItemPickupEvent;
import dev.iredsc.hadron.api.event.item.ItemUseEvent;
import dev.iredsc.hadron.api.event.resource.Resource;
import dev.iredsc.hadron.api.event.resource.ResourceConsumeEvent;
import dev.iredsc.hadron.api.event.resource.ResourceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import dev.iredsc.hadron.api.event.world.ExplosionPrimeEvent;
import dev.iredsc.hadron.test.Acceptance;
import dev.iredsc.hadron.test.Acceptance.Part;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.CompoundContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The acceptance kit on vanilla blocks: it passes what core already covers, and each assertion fails, naming the right
 * gap, when a change skips every event, a cancel is ignored, or nobody is behind an action. A kit that passed those
 * would pass a bridge that covers nothing.
 */
public final class AcceptanceKitTests {
    /** An action of a family the kit doesn't know, as a bridge's own event type might be. */
    public static final class OddEvent extends ActionEvent implements Cancellable {
        private boolean cancelled;

        public OddEvent(Actor actor) {
            super(actor);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancel) {
            cancelled = cancel;
        }
    }

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
    void aMobBornOutsideThatComesInIsMotion(TestContext t) {
        TestPlayer bob = t.player("bob");
        Acceptance area = t.acceptance(around(t));
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(t.pos(0, 0, 4).getX() + 0.5, t.pos(0, 0, 4).getY(), t.pos(0, 0, 4).getZ() + 0.5);
        try (Actors.Scope s = Actors.push(new Actor.Player(bob.player()))) {
            t.level().addFreshEntity(pig);
        }
        pig.teleportTo(t.pos(2, 0, 0).getX() + 0.5, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5);
        area.assertUnchanged("a mob born outside the area walking in", Part.ENTITIES);
    }

    @HadronTest
    void anItemAppearingInTheAreaIsAChange(TestContext t) {
        BlockPos in = t.pos(2, 0, 0);
        Acceptance area = t.acceptance(around(t));
        t.level().addFreshEntity(new ItemEntity(t.level(), in.getX() + 0.5, in.getY(), in.getZ() + 0.5,
                new ItemStack(Items.DIAMOND)));
        String why = failure(t, () -> area.assertUnchanged("a diamond dropped into the area", Part.ENTITIES));
        t.assertTrue(why.contains("minecraft:item") && why.contains("appeared"), why);
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
        // Refused as it primed (its reach overlaps the area) or as it went off (its blocks reach in).
        t.assertTrue(area.events().stream().anyMatch(e -> (e.event() instanceof ExplosionEvent
                || e.event() instanceof ExplosionPrimeEvent) && e.cancelled()),
                "protection refused the blast reaching into the area: " + area.events());
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

    @HadronTest
    void aShiftClickIntoADoubleChestInTheAreaIsRefused(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos left = t.pos(2, 0, 0), right = t.pos(2, 0, 1);
        t.setBlock(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST)
                .setValue(ChestBlock.TYPE, ChestType.LEFT));
        t.setBlock(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT));
        Acceptance area = t.acceptance(around(t));
        CompoundContainer both = new CompoundContainer((ChestBlockEntity) t.level().getBlockEntity(left),
                (ChestBlockEntity) t.level().getBlockEntity(right));
        ChestMenu menu = ChestMenu.sixRows(1, bob.player().getInventory(), both);
        boolean cancelled = Hadron.getPluginManager().callEvent(new InventoryClickEvent(bob.player(),
                new InventoryHolder.Entity(bob.player()), menu, 60, 0, ClickType.QUICK_MOVE,
                new Actor.Player(bob.player())));
        t.assertTrue(cancelled, "a shift-click into the area's double chest was refused: " + area.events());
    }

    @HadronTest
    void anItemChangedInAnInventoryInTheAreaIsRefused(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos chest = t.pos(2, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        Acceptance area = t.acceptance(around(t));
        Actor machine = new Actor.Machine(t.level(), t.pos(-3, 0, 0), Attribution.of(bob.uuid()));
        boolean cancelled = Hadron.getPluginManager().callEvent(new ItemChangeEvent(t.level(),
                new InventoryHolder.Block(t.level(), chest), new ItemStack(Items.IRON_SWORD),
                new ItemStack(Items.DIAMOND_SWORD), machine));
        t.assertTrue(cancelled, "a machine outside changing an item in the area's chest was refused: "
                + area.events());
    }

    @HadronTest
    void aHeldToolsEnergyIsWhereItsHolderIs(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos in = t.pos(2, 0, 0);
        alice.teleport(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).refuse(ResourceEvent.class).mark();
        boolean cancelled = Hadron.getPluginManager().callEvent(new ResourceConsumeEvent(
                new Actor.Player(alice.player()), Resource.ENERGY,
                new ResourceHolder.Item(new ItemStack(Items.IRON_PICKAXE)), 100, 40));
        t.assertTrue(cancelled, "a tool's energy spent in the area, with resources refused, was refused");
    }

    @HadronTest
    void movingTheBoxAfterwardsMovesNothing(TestContext t) {
        BoundingBox box = around(t);
        Acceptance area = t.acceptance(box);
        box.move(20, 0, 0);
        area.mark();
        BlockPos pos = t.pos(2, 0, 0);
        t.level().getChunkAt(pos).setBlockState(pos, Blocks.GOLD_BLOCK.defaultBlockState(), false);
        failure(t, () -> area.assertUnchanged("a write in the area first given"));
    }

    @HadronTest
    void itemsMergingInTheAreaAreRefused(TestContext t) {
        BlockPos in = t.pos(2, 0, 0);
        Acceptance area = t.acceptance(around(t));
        ItemEntity a = new ItemEntity(t.level(), in.getX() + 0.5, in.getY(), in.getZ() + 0.5,
                new ItemStack(Items.DIAMOND));
        ItemEntity b = new ItemEntity(t.level(), in.getX() + 0.5, in.getY(), in.getZ() + 0.5,
                new ItemStack(Items.DIAMOND));
        boolean cancelled = Hadron.getPluginManager().callEvent(new ItemMergeEvent(t.level(), a, b,
                Actor.Nature.INSTANCE));
        t.assertTrue(cancelled, "a merge lying in the area, which nature is behind, was refused: " + area.events());
    }

    @HadronTest
    void aShotFromOutsideIntoTheAreaIsRefused(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos target = t.pos(2, 0, 0);
        t.setBlock(target, Blocks.STONE);
        Acceptance area = t.acceptance(around(t));
        // A hitscan gun names its shooter as the entity; bob stands outside the area.
        boolean cancelled = Hadron.getPluginManager().callEvent(new ProjectileHitEvent(bob.player(),
                new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false),
                new Actor.Player(bob.player())));
        t.assertTrue(cancelled, "bob's shot landing in the area was refused: " + area.events());
    }

    @HadronTest
    void aStepWhereNothingWasRefusedProvesNothing(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        bob.hold(new ItemStack(Items.IRON_PICKAXE));
        Acceptance area = t.acceptance(around(t));
        t.tick(2);
        String why = failure(t, () -> area.assertRefused(bob, "bob's machine, never started"));
        t.assertTrue(why.contains("nothing acting for bob was refused"), why);
        bob.breakBlock(stone);
        t.tick(2);
        area.assertRefused(bob, "bob breaking stone where he isn't trusted");
    }

    @HadronTest
    void attributionRefusesAStepAPlayerJoinedLate(TestContext t) {
        Acceptance area = t.acceptance(around(t));
        TestPlayer alice = t.player("alice");
        String why = failure(t, () -> area.assertActingFor(alice, "alice, who joined after the mark"));
        t.assertTrue(why.contains("Join every player before calling mark()"), why);
    }

    @HadronTest
    void aDoubleChestWithOnlyItsSecondHalfInTheAreaIsProtected(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos outside = t.pos(3, 0, 3), inside = t.pos(3, 0, 2);
        t.setBlock(outside, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST)
                .setValue(ChestBlock.TYPE, ChestType.LEFT));
        t.setBlock(inside, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT));
        Acceptance area = t.acceptance(around(t));
        CompoundContainer both = new CompoundContainer((ChestBlockEntity) t.level().getBlockEntity(outside),
                (ChestBlockEntity) t.level().getBlockEntity(inside));
        ChestMenu menu = ChestMenu.sixRows(1, bob.player().getInventory(), both);
        boolean cancelled = Hadron.getPluginManager().callEvent(new InventoryClickEvent(bob.player(),
                new InventoryHolder.Entity(bob.player()), menu, 60, 0, ClickType.QUICK_MOVE,
                new Actor.Player(bob.player())));
        t.assertTrue(cancelled, "a shift-click into a double chest half in the area was refused: " + area.events());
    }

    @HadronTest
    void anItemChangeInAnUnknownInventoryIsRefusedAndNamed(TestContext t) {
        TestPlayer bob = t.player("bob");
        Acceptance area = t.acceptance(around(t));
        boolean cancelled = Hadron.getPluginManager().callEvent(new ItemChangeEvent(t.level(),
                InventoryHolder.Unknown.INSTANCE, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.DIAMOND_SWORD),
                new Actor.Player(bob.player())));
        t.assertTrue(cancelled, "an item change nothing can place was refused");
        String why = failure(t, () -> area.assertActingFor(bob, "bob changing an item nobody can place"));
        t.assertTrue(why.contains("its holder is unknown"), why);
    }

    @HadronTest
    void aMobTakenToAnotherWorldIsNotGone(TestContext t) {
        Pig pig = pig(t, t.pos(2, 0, 0));
        Acceptance area = t.acceptance(around(t));
        ServerLevel nether = t.server().getLevel(Level.NETHER);
        t.assertTrue(nether != null, "the test server has a Nether");
        pig.changeDimension(new DimensionTransition(nether, new Vec3(0.5, 100, 0.5), Vec3.ZERO, 0, 0,
                DimensionTransition.DO_NOTHING));
        area.assertUnchanged("a pig taken through a portal", Part.ENTITIES);
    }

    @HadronTest
    void natureBeingRefusedDoesNotShowTheMachineTried(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos in = t.pos(2, 0, 0);
        Acceptance area = t.acceptance(around(t));
        ItemEntity a = new ItemEntity(t.level(), in.getX() + 0.5, in.getY(), in.getZ() + 0.5,
                new ItemStack(Items.DIAMOND));
        ItemEntity b = new ItemEntity(t.level(), in.getX() + 0.5, in.getY(), in.getZ() + 0.5,
                new ItemStack(Items.DIAMOND));
        t.assertTrue(Hadron.getPluginManager().callEvent(new ItemMergeEvent(t.level(), a, b, Actor.Nature.INSTANCE)),
                "nature's merge was refused");
        String why = failure(t, () -> area.assertRefused(bob, "bob's idle machine beside merging items"));
        t.assertTrue(why.contains("nothing acting for bob was refused"), why);
    }

    @HadronTest
    void refuseRejectsKindsProtectionCantDeny(TestContext t) {
        Acceptance area = t.acceptance(around(t));
        for (Class<? extends dev.iredsc.hadron.api.event.Event> kind : List.of(EntityTeleportEvent.class,
                PlayerSwapHandItemsEvent.class, EquipmentChangeEvent.class)) {
            String why = "no refusal";
            try {
                area.refuse(kind);
            } catch (IllegalArgumentException e) {
                why = e.getMessage();
            }
            t.assertTrue(why.contains(kind.getSimpleName() + " can't be refused"), why);
        }
        area.refuse(BlockEvent.class).refuse(EntityEvent.class);
    }

    @HadronTest
    void armourPutOnAPlayerInTheAreaIsRefused(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos in = t.pos(2, 0, 0);
        alice.teleport(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
        Acceptance area = t.acceptance(around(t));
        // A dispenser outside the area, nobody's, equips the player standing in it.
        Actor dispenser = new Actor.Machine(t.level(), t.pos(-3, 0, 0), Attribution.none());
        boolean cancelled = Hadron.getPluginManager().callEvent(new EquipEvent(t.level(), alice.player(),
                EquipmentSlot.HEAD, ItemStack.EMPTY, new ItemStack(Items.IRON_HELMET), dispenser));
        t.assertTrue(cancelled, "armour put on a player in the area from outside was refused: " + area.events());
    }

    @HadronTest
    void anAcceleratorReachingIntoTheAreaIsRefused(TestContext t) {
        TestPlayer bob = t.player("bob");
        Acceptance area = t.acceptance(around(t));
        boolean cancelled = Hadron.getPluginManager().callEvent(new TickAccelerateEvent(
                new Actor.Player(bob.player()), t.level(), BoundingBox.fromCorners(t.pos(-2, 0, -1), t.pos(1, 1, 1)),
                4));
        t.assertTrue(cancelled, "a field overlapping the area was refused: " + area.events());
    }

    @HadronTest
    void aMergeIntoAnItemInTheAreaIsRefused(TestContext t) {
        BlockPos in = t.pos(1, 0, 0), out = t.pos(0, 0, 0);
        Acceptance area = t.acceptance(around(t));
        ItemEntity donor = new ItemEntity(t.level(), out.getX() + 0.5, out.getY(), out.getZ() + 0.5,
                new ItemStack(Items.DIAMOND));
        ItemEntity target = new ItemEntity(t.level(), in.getX() + 0.5, in.getY(), in.getZ() + 0.5,
                new ItemStack(Items.DIAMOND));
        t.assertTrue(Hadron.getPluginManager().callEvent(new ItemMergeEvent(t.level(), donor, target,
                Actor.Nature.INSTANCE)), "a merge into an item lying in the area was refused: " + area.events());
    }

    @HadronTest
    void openingADoubleChestWithItsFirstHalfOutsideIsRefused(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos outside = t.pos(3, 0, 3), inside = t.pos(3, 0, 2);
        t.setBlock(outside, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST)
                .setValue(ChestBlock.TYPE, ChestType.LEFT));
        t.setBlock(inside, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT));
        Acceptance area = t.acceptance(around(t));
        CompoundContainer both = new CompoundContainer((ChestBlockEntity) t.level().getBlockEntity(outside),
                (ChestBlockEntity) t.level().getBlockEntity(inside));
        ChestMenu menu = ChestMenu.sixRows(1, bob.player().getInventory(), both);
        t.assertTrue(Hadron.getPluginManager().callEvent(new InventoryOpenEvent(bob.player(),
                new InventoryHolder.Block(t.level(), outside), menu, new Actor.Player(bob.player()))),
                "opening a double chest reaching into the area was refused: " + area.events());
    }

    @HadronTest
    void aMobTakenToAnotherWorldAndDestroyedThereIsGone(TestContext t) {
        Pig pig = pig(t, t.pos(2, 0, 0));
        Acceptance area = t.acceptance(around(t));
        ServerLevel nether = t.server().getLevel(Level.NETHER);
        t.assertTrue(nether != null, "the test server has a Nether");
        var copy = pig.changeDimension(new DimensionTransition(nether, new Vec3(0.5, 100, 0.5), Vec3.ZERO, 0, 0,
                DimensionTransition.DO_NOTHING));
        t.assertTrue(copy != null, "the pig went to the Nether");
        copy.discard();
        String why = failure(t, () -> area.assertUnchanged("a pig taken away and destroyed", Part.ENTITIES));
        t.assertTrue(why.contains("minecraft:pig") && why.contains("is gone"), why);
    }

    @HadronTest
    void aRefusalNothingCouldPlaceStillShowsTheMachineTried(TestContext t) {
        TestPlayer bob = t.player("bob");
        Acceptance area = t.acceptance(around(t));
        t.assertTrue(Hadron.getPluginManager().callEvent(new InventoryChangeEvent(List.of(
                new InventoryChangeEvent.Change(InventoryHolder.Unknown.INSTANCE, 0, ItemStack.EMPTY,
                        new ItemStack(Items.DIAMOND))), new Actor.Player(bob.player()))), "refused, failing closed");
        area.assertRefused(bob, "bob's pipe that can't name its chest");
    }

    @HadronTest
    void aChangeInsideAnAcceleratorsFieldNamesIt(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos pos = t.pos(2, 0, 0);
        Acceptance area = t.acceptance(around(t));
        Hadron.getPluginManager().callEvent(new TickAccelerateEvent(new Actor.Player(bob.player()), t.level(),
                BoundingBox.fromCorners(t.pos(1, 0, -1), t.pos(3, 1, 1)), 4));
        t.level().getChunkAt(pos).setBlockState(pos, Blocks.GOLD_BLOCK.defaultBlockState(), false);
        String why = failure(t, () -> area.assertUnchanged("an accelerator that ignored its refusal"));
        t.assertTrue(why.contains("TickAccelerateEvent") && why.contains("the cancel left it behind"), why);
    }

    @HadronTest
    void anEntityHurtWhilePassingThroughIsCompared(TestContext t) {
        Pig pig = pig(t, t.pos(0, 0, 4));
        Acceptance area = t.acceptance(around(t));
        pig.teleportTo(t.pos(2, 0, 0).getX() + 0.5, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5);
        CompoundTag after = new CompoundTag();
        after.putInt("level", 2);
        // Any event in the area naming the pig, as its bridge fires on the way through.
        t.bridge().fire(new EntityDataChangeEvent(pig, ResourceLocation.fromNamespaceAndPath("hadron_test_target",
                "mark"), new CompoundTag(), after, Actor.Nature.INSTANCE));
        pig.setHealth(pig.getHealth() - 4);
        pig.teleportTo(t.pos(0, 0, 4).getX() + 0.5, t.pos(0, 0, 4).getY(), t.pos(0, 0, 4).getZ() + 0.5);
        String why = failure(t, () -> area.assertUnchanged("a pig hurt on its way through", Part.ENTITIES));
        t.assertTrue(why.contains("health"), why);
    }

    @HadronTest
    void boardingAVehicleInTheAreaFromOutsideIsRefused(TestContext t) {
        TestPlayer bob = t.player("bob");
        Pig horse = pig(t, t.pos(1, 0, 0));
        Acceptance area = t.acceptance(around(t));
        t.assertTrue(Hadron.getPluginManager().callEvent(new EntityMountEvent(bob.player(), horse,
                new Actor.Player(bob.player()))), "bob boarding a mount in the area was refused: " + area.events());
    }

    @HadronTest
    void aMobReloadedAndThenDestroyedIsGone(TestContext t) {
        Pig pig = pig(t, t.pos(2, 0, 0));
        Acceptance area = t.acceptance(around(t));
        CompoundTag saved = new CompoundTag();
        pig.save(saved);
        pig.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        Entity reloaded = EntityType.loadEntityRecursive(saved, t.level(), e -> e);
        t.assertTrue(reloaded != null, "the pig loads back from its saved data");
        try (Actors.Scope s = Actors.push(new Actor.Server("its chunk loading"))) {
            t.level().addFreshEntity(reloaded);
        }
        area.assertUnchanged("a pig unloaded and loaded back", Part.ENTITIES);
        reloaded.discard();
        String why = failure(t, () -> area.assertUnchanged("the loaded-back pig destroyed", Part.ENTITIES));
        t.assertTrue(why.contains("minecraft:pig") && why.contains("is gone"), why);
    }

    @HadronTest
    void aStructureSweepingThroughTheAreaIsRefused(TestContext t) {
        TestPlayer bob = t.player("bob");
        Acceptance area = t.acceptance(around(t));
        BlockPos from = t.pos(-2, 0, 0), to = t.pos(5, 0, 0);
        BlockState stone = Blocks.STONE.defaultBlockState();
        // A ship flying across the area: it leaves from outside and lands outside, sweeping through.
        boolean cancelled = Hadron.getPluginManager().callEvent(new BlockMoveEvent(t.level(), from, stone,
                List.of(new BlockMoveEvent.Move(from, to, stone)), List.of(t.pos(2, 0, 0)),
                List.of(BlockChange.unchanged(t.level(), from, stone)), new Actor.Player(bob.player())));
        t.assertTrue(cancelled, "a structure sweeping through the area was refused: " + area.events());
    }

    @HadronTest
    void anotherListenersCancelDoesNotShowTheMachineTried(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        bob.hold(new ItemStack(Items.IRON_PICKAXE));
        Acceptance area = t.acceptance(around(t));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockBreakEvent e) {
                e.setCancelled(true);
            }
        });
        area.trust(bob).refuse(InventoryEvent.class).mark();
        bob.breakBlock(stone);
        t.tick(2);
        String why = failure(t, () -> area.assertRefused(bob, "bob's miner, its inventory changes refused"));
        t.assertTrue(why.contains("nothing acting for bob was refused"), why);
    }

    @HadronTest
    void anEquipmentChangeOnAPlayerInTheAreaIsLogged(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos in = t.pos(2, 0, 0);
        alice.teleport(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        Hadron.getPluginManager().callEvent(new EquipmentChangeEvent(t.level(), alice.player(), EquipmentSlot.HEAD,
                ItemStack.EMPTY, new ItemStack(Items.IRON_HELMET),
                new Actor.Machine(t.level(), t.pos(-3, 0, 0), Attribution.none())));
        String why = failure(t, () -> area.assertActingFor(alice, "a machine nobody owns dressing alice"));
        t.assertTrue(why.contains("EquipmentChangeEvent") && why.contains("nobody is responsible"), why);
    }

    @HadronTest
    void anItemChangeElsewhereWithNobodyBehindItIsLeftAlone(TestContext t) {
        BlockPos chest = t.pos(-3, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        Acceptance area = t.acceptance(around(t));
        boolean cancelled = Hadron.getPluginManager().callEvent(new ItemChangeEvent(t.level(),
                new InventoryHolder.Block(t.level(), chest), new ItemStack(Items.IRON_SWORD),
                new ItemStack(Items.DIAMOND_SWORD), Actor.Unknown.INSTANCE));
        t.assertFalse(cancelled, "an item change in a chest known to be outside the area isn't the area's");
    }

    @HadronTest
    void aDoubleChestsOutsideHalfFillingLeavesTheAreaUnchanged(TestContext t) {
        BlockPos outside = t.pos(3, 0, 3), inside = t.pos(3, 0, 2);
        t.setBlock(outside, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST)
                .setValue(ChestBlock.TYPE, ChestType.LEFT));
        t.setBlock(inside, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT));
        Acceptance area = t.acceptance(around(t));
        ((ChestBlockEntity) t.level().getBlockEntity(outside)).setItem(0, new ItemStack(Items.DIAMOND, 3));
        area.assertUnchanged("a hopper outside filling the outside half", Part.INVENTORIES);
    }

    @HadronTest
    void refuseRejectsWholeWorldKinds(TestContext t) {
        Acceptance area = t.acceptance(around(t));
        String why = "no refusal";
        try {
            area.refuse(WeatherChangeEvent.class);
        } catch (IllegalArgumentException e) {
            why = e.getMessage();
        }
        t.assertTrue(why.contains("WeatherChangeEvent can't be refused") && why.contains("a whole world"), why);
    }

    @HadronTest
    void naturesUnplaceableEventsAreNobodysButAreListed(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        alice.breakBlock(stone);
        Hadron.getPluginManager().callEvent(new InventoryChangeEvent(List.of(new InventoryChangeEvent.Change(
                InventoryHolder.Unknown.INSTANCE, 0, ItemStack.EMPTY, new ItemStack(Items.DIAMOND))),
                Actor.Nature.INSTANCE));
        t.tick(2);
        area.assertActingFor(alice, "alice breaking stone while nature fills somewhere unknown");
        t.assertTrue(area.events().stream().anyMatch(e -> e.event() instanceof InventoryChangeEvent
                && e.pos().isEmpty()), "events() lists the event nothing could place: " + area.events());
    }

    @HadronTest
    void anActionOfAnUnknownKindIsRefusedAndNamed(TestContext t) {
        TestPlayer alice = t.player("alice");
        Acceptance area = t.acceptance(around(t));
        area.trust(alice).mark();
        OddEvent odd = t.bridge().fire(new OddEvent(new Actor.Player(alice.player())));
        t.assertTrue(odd.isCancelled(), "an action the kit can't place was refused, failing closed");
        String why = failure(t, () -> area.assertActingFor(alice, "alice's machine firing its own kind"));
        t.assertTrue(why.contains("doesn't know where a OddEvent happens"), why);
    }

    @HadronTest
    void aCartDrainedOnItsWayThroughIsCaught(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos start = t.pos(0, 0, 4), in = t.pos(2, 0, 0);
        MinecartChest cart = new MinecartChest(t.level(), start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        cart.setItem(0, new ItemStack(Items.DIAMOND, 3));
        t.level().addFreshEntity(cart);
        t.tick();
        Acceptance area = t.acceptance(around(t));
        cart.teleportTo(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
        t.assertTrue(Hadron.getPluginManager().callEvent(new InventoryChangeEvent(List.of(
                new InventoryChangeEvent.Change(new InventoryHolder.Entity(cart), 0, new ItemStack(Items.DIAMOND, 3),
                        ItemStack.EMPTY)), new Actor.Player(bob.player()))), "bob's drain was refused");
        cart.setItem(0, ItemStack.EMPTY);
        cart.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        String why = failure(t, () -> area.assertUnchanged("a pipe draining a cart as it passes", Part.ENTITIES));
        t.assertTrue(why.contains("chest_minecart") && why.contains("diamond"), why);
    }

    @HadronTest
    void anEntityReplacedByAnotherKindUnderItsIdIsCaught(TestContext t) {
        Pig pig = pig(t, t.pos(2, 0, 0));
        Acceptance area = t.acceptance(around(t));
        Cow cow = EntityType.COW.create(t.level());
        cow.moveTo(pig.getX(), pig.getY(), pig.getZ());
        cow.setUUID(pig.getUUID());
        cow.setHealth(pig.getHealth());
        pig.discard();
        try (Actors.Scope s = Actors.push(new Actor.Server("a transformation that ignored its refusal"))) {
            t.level().addFreshEntity(cow);
        }
        String why = failure(t, () -> area.assertUnchanged("a pig turned into a cow", Part.ENTITIES));
        t.assertTrue(why.contains("minecraft:pig") && why.contains("became a minecraft:cow"), why);
    }

    @HadronTest
    void aRefusedSpawnLeavesNoPhantom(TestContext t) {
        TestPlayer bob = t.player("bob");
        Acceptance area = t.acceptance(around(t));
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(t.pos(2, 0, 0).getX() + 0.5, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5);
        boolean added;
        try (Actors.Scope s = Actors.push(new Actor.Player(bob.player()))) {
            added = t.level().addFreshEntity(pig);
        }
        t.assertFalse(added, "bob's spawn in the area was refused");
        area.assertUnchanged("bob's refused spawn", Part.ENTITIES);
    }

    @HadronTest
    void aDropKnownToLandOutsideIsLeftAlone(TestContext t) {
        Acceptance area = t.acceptance(around(t));
        BlockPos out = t.pos(-3, 0, 0);
        boolean cancelled = Hadron.getPluginManager().callEvent(new ItemDropEvent(t.level(),
                new Vec3(out.getX() + 0.5, out.getY(), out.getZ() + 0.5), new ItemStack(Items.DIAMOND),
                Actor.Unknown.INSTANCE));
        t.assertFalse(cancelled, "a drop landing outside the area isn't the area's");
    }

    @HadronTest
    void aChangeByAnEventPlacedOutsideSaysSo(TestContext t) {
        Pig pig = pig(t, t.pos(2, 0, 0));
        Acceptance area = t.acceptance(around(t));
        CompoundTag after = new CompoundTag();
        after.putInt("level", 2);
        // An event about the pig that says it happened somewhere else.
        pig.teleportTo(t.pos(-3, 0, 0).getX() + 0.5, t.pos(-3, 0, 0).getY(), t.pos(-3, 0, 0).getZ() + 0.5);
        t.bridge().fire(new EntityDataChangeEvent(pig, ResourceLocation.fromNamespaceAndPath("hadron_test_target",
                "mark"), new CompoundTag(), after, Actor.Nature.INSTANCE));
        pig.setHealth(pig.getHealth() - 4);
        String why = failure(t, () -> area.assertUnchanged("a pig changed by an event placed outside", Part.ENTITIES));
        t.assertTrue(why.contains("EntityDataChangeEvent") && why.contains("placed outside the area"), why);
    }

    @HadronTest
    void aCartWhoseItemChangeWasIgnoredOnItsWayThroughIsCaught(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos start = t.pos(0, 0, 4), in = t.pos(2, 0, 0);
        MinecartChest cart = new MinecartChest(t.level(), start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        cart.setItem(0, new ItemStack(Items.DIAMOND, 3));
        t.level().addFreshEntity(cart);
        t.tick();
        Acceptance area = t.acceptance(around(t));
        cart.teleportTo(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
        t.assertTrue(Hadron.getPluginManager().callEvent(new ItemChangeEvent(t.level(),
                new InventoryHolder.Entity(cart), new ItemStack(Items.DIAMOND, 3), new ItemStack(Items.DIAMOND, 2),
                new Actor.Player(bob.player()))),
                "bob's change to the cart's diamonds was refused");
        cart.setItem(0, new ItemStack(Items.DIAMOND, 2));
        cart.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        String why = failure(t, () -> area.assertUnchanged("a machine taking a diamond as the cart passes",
                Part.ENTITIES));
        t.assertTrue(why.contains("chest_minecart") && why.contains("diamond"), why);
    }

    @HadronTest
    void aMergeWhollyOutsideWithNobodyBehindItIsLeftAlone(TestContext t) {
        Acceptance area = t.acceptance(around(t));
        BlockPos out = t.pos(-3, 0, 0);
        ItemEntity a = new ItemEntity(t.level(), out.getX() + 0.5, out.getY(), out.getZ() + 0.5,
                new ItemStack(Items.DIAMOND));
        ItemEntity b = new ItemEntity(t.level(), out.getX() + 0.5, out.getY(), out.getZ() + 0.5,
                new ItemStack(Items.DIAMOND));
        t.assertFalse(Hadron.getPluginManager().callEvent(new ItemMergeEvent(t.level(), a, b, Actor.Unknown.INSTANCE)),
                "a merge lying wholly outside the area isn't the area's");
    }
}
