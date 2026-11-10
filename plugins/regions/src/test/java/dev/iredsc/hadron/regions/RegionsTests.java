package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.block.BlockBurnEvent;
import dev.iredsc.hadron.api.event.block.BlockIgniteEvent;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.entity.EntityPotionEffectEvent;
import dev.iredsc.hadron.api.event.entity.EntityRemoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.inventory.InventoryMoveItemEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent.TeleportCause;
import dev.iredsc.hadron.api.event.entity.ArmorStandManipulateEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.resource.Resource;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.resource.ResourceTransferEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Regions as an operator makes them and as players and the world meet them. */
public final class RegionsTests {
    /** An op in creative, who makes regions and is let through them. */
    /** An operator, alice, with every region an earlier test left removed, so a test sees only its own. */
    private static TestPlayer admin(TestContext t) {
        RegionStore store = ((RegionsPlugin) t.plugin()).regions();
        for (String id : List.copyOf(store.all().stream().map(Region::id).toList())) store.remove(id);
        TestPlayer alice = t.player("alice");
        alice.setOp(true);
        alice.player().setGameMode(GameType.CREATIVE);
        return alice;
    }

    /** Defines a region from {@code a} to {@code b} the way an operator does: the wand, then /rg define. */
    private static void define(TestContext t, TestPlayer admin, String id, BlockPos a, BlockPos b) {
        t.setBlock(a, Blocks.DIRT);
        t.setBlock(b, Blocks.DIRT);
        admin.command("rg wand");
        admin.breakBlock(a);
        admin.useBlock(b);
        admin.command("rg define " + id);
        t.assertChat(admin, "Defined " + id);
    }

    @HadronTest
    void theWandPicksCornersWithoutBreaking(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "the-wand-picks-corners-without-b", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        t.assertBlock(t.pos(-2, 0, -2), Blocks.DIRT);
    }

    @HadronTest
    void anOutsiderCantBreakOrPlace(TestContext t) {
        // Blocks inside are set before the region exists: once it does, the test's own writes are refused.
        t.setBlock(t.pos(1, 0, 1), Blocks.DIRT);
        define(t, admin(t), "an-outsider-cant-break-or-place", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        TestPlayer bob = t.player("bob");
        bob.breakBlock(t.pos(1, 0, 1));
        t.assertBlock(t.pos(1, 0, 1), Blocks.DIRT);
        bob.hold(new ItemStack(Items.STONE));
        bob.placeBlock(t.pos(1, 1, 1));
        t.assertBlock(t.pos(1, 1, 1), Blocks.AIR);
    }

    @HadronTest
    void aMemberBuilds(TestContext t) {
        t.setBlock(t.pos(1, 0, 1), Blocks.DIRT);
        TestPlayer alice = admin(t);
        define(t, alice, "a-member-builds", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        TestPlayer bob = t.player("bob");
        alice.command("rg addmember a-member-builds bob");
        bob.breakBlock(t.pos(1, 0, 1));
        t.assertBlock(t.pos(1, 0, 1), Blocks.AIR);
    }

    @HadronTest
    void anOutsiderBuildsOutside(TestContext t) {
        define(t, admin(t), "an-outsider-builds-outside", t.pos(-2, 0, -2), t.pos(-1, 2, -1));
        TestPlayer bob = t.player("bob");
        t.setBlock(t.pos(2, 0, 2), Blocks.DIRT);
        bob.breakBlock(t.pos(2, 0, 2));
        t.assertBlock(t.pos(2, 0, 2), Blocks.AIR);
    }

    @HadronTest
    void aChangeWithNobodyBehindItIsRefused(TestContext t) {
        define(t, admin(t), "a-change-with-nobody-behind-it-i", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        // The test's own code has nobody on the actor stack, as an unbridged mod's queued work wouldn't.
        t.setBlock(t.pos(1, 1, 1), Blocks.STONE);
        t.assertBlock(t.pos(1, 1, 1), Blocks.AIR);
    }

    @HadronTest
    void waterFromOutsideStaysOut(TestContext t) {
        define(t, admin(t), "water-from-outside-stays-out", t.pos(1, 0, 0), t.pos(3, 2, 3));
        t.setBlock(t.pos(0, 0, 2), Blocks.WATER);
        t.tick(40);
        t.assertBlock(t.pos(1, 0, 2), Blocks.AIR);
        t.assertBlock(t.pos(-1, 0, 2), Blocks.WATER);
    }

    @HadronTest
    void entryDenyKeepsOutsidersOut(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag vault entry deny");
        TestPlayer bob = t.player("bob");
        bob.moveTo(t.pos(1, 0, 0));
        t.tick(2);
        bob.moveTo(t.pos(2, 0, 0));
        // Moves are checked as the player ticks.
        t.tick(2);
        t.assertTrue(bob.player().getBlockX() < t.pos(2, 0, 0).getX(),
                "bob should have been put back outside the vault, but stands at " + bob.player().blockPosition());
    }

    @HadronTest
    void aRemovedRegionComesBack(TestContext t) {
        t.setBlock(t.pos(1, 0, 1), Blocks.DIRT);
        TestPlayer alice = admin(t);
        define(t, alice, "a-removed-region-comes-back", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        alice.command("rg remove a-removed-region-comes-back");
        t.assertChat(alice, "/region restore a-removed-region-comes-back");
        alice.command("rg restore a-removed-region-comes-back");
        t.assertChat(alice, "Restored a-removed-region-comes-back");
        TestPlayer bob = t.player("bob");
        bob.breakBlock(t.pos(1, 0, 1));
        t.assertBlock(t.pos(1, 0, 1), Blocks.DIRT);
    }

    @HadronTest
    void anOpBuildsInRegionsOnlyWithBypassOn(TestContext t) {
        t.setBlock(t.pos(1, 0, 1), Blocks.DIRT);
        t.setBlock(t.pos(-1, 0, -1), Blocks.DIRT);
        define(t, admin(t), "bypass-toggle", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        TestPlayer erin = t.player("erin");
        erin.setOp(true);
        erin.breakBlock(t.pos(1, 0, 1));
        t.assertBlock(t.pos(1, 0, 1), Blocks.DIRT);
        erin.command("rg bypass");
        t.assertChat(erin, "Region bypass is on");
        erin.breakBlock(t.pos(1, 0, 1));
        t.assertBlock(t.pos(1, 0, 1), Blocks.AIR);
        erin.command("rg bypass");
        t.assertChat(erin, "Region bypass is off");
        erin.breakBlock(t.pos(-1, 0, -1));
        t.assertBlock(t.pos(-1, 0, -1), Blocks.DIRT);
    }

    @HadronTest
    void bypassNeedsItsNode(TestContext t) {
        TestPlayer frank = t.player("frank");
        frank.command("rg bypass");
        t.assertChat(frank, "You need regions.bypass");
        t.assertNoChat(frank, "Region bypass is on");
    }

    @HadronTest
    void anOwnerManagesTheirRegionButCantRemoveIt(TestContext t) {
        t.setBlock(t.pos(1, 0, 1), Blocks.DIRT);
        TestPlayer alice = admin(t);
        define(t, alice, "owned-plot", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        define(t, alice, "someone-elses", t.pos(3, 0, -2), t.pos(4, 1, -1));
        TestPlayer bob = t.player("bob");
        TestPlayer carol = t.player("carol");
        alice.command("rg addowner owned-plot bob");
        bob.command("rg addmember owned-plot carol");
        t.assertChat(bob, "carol is a member of owned-plot");
        carol.breakBlock(t.pos(1, 0, 1));
        t.assertBlock(t.pos(1, 0, 1), Blocks.AIR);
        bob.command("rg remove owned-plot");
        t.assertChat(bob, "You need regions.manage");
        bob.command("rg flag someone-elses use allow");
        t.assertChat(bob, "You don't own a region named someone-elses");
    }

    @HadronTest
    void anOutsiderCantPlaceAnArmorStand(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "armor-stand-place", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        alice.command("rg flag armor-stand-place use allow");
        TestPlayer bob = t.player("bob");
        bob.hold(new ItemStack(Items.ARMOR_STAND));
        bob.useBlock(t.pos(1, -1, 1));
        t.tick(2);
        t.assertTrue(t.level().getEntitiesOfClass(ArmorStand.class, new AABB(t.pos(1, 0, 1)).inflate(3)).isEmpty(),
                "an outsider's armor stand should not appear in a region that denies building");
    }

    @HadronTest
    void anEnderPearlCantCarryAnOutsiderIn(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "pearl-vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag pearl-vault entry deny");
        TestPlayer bob = t.player("bob");
        ThrownEnderpearl pearl = new ThrownEnderpearl(t.level(), bob.player());
        BlockPos inside = t.pos(3, 1, 0);
        pearl.setPos(inside.getX() + 0.5, inside.getY() + 0.5, inside.getZ() + 0.5);
        pearl.setDeltaMovement(0, -0.5, 0);
        t.level().addFreshEntity(pearl);
        t.await("the pearl to land", pearl::isRemoved, 40);
        t.tick(2);
        t.assertTrue(bob.player().getBlockX() < t.pos(2, 0, 0).getX(),
                "bob should not have been carried into the vault, but stands at " + bob.player().blockPosition());
    }

    @HadronTest
    void anOutsiderCantKnockOverAnArmorStand(TestContext t) {
        ArmorStand stand = EntityType.ARMOR_STAND.create(t.level());
        BlockPos at = t.pos(1, 0, 1);
        stand.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        t.level().addFreshEntity(stand);
        TestPlayer alice = admin(t);
        define(t, alice, "armor-stand-hit", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        TestPlayer bob = t.player("bob");
        bob.attack(stand);
        t.tick(2);
        bob.attack(stand);
        t.tick(2);
        t.assertFalse(stand.isRemoved(), "an outsider's hits should not break a stand in a region that denies building");
    }

    @HadronTest
    void anOutsiderCantTakeAFramesItem(TestContext t) {
        BlockPos wall = t.pos(1, 0, 1);
        t.setBlock(wall, Blocks.STONE);
        ItemFrame frame = new ItemFrame(t.level(), wall.west(), Direction.WEST);
        frame.setItem(new ItemStack(Items.DIAMOND));
        t.level().addFreshEntity(frame);
        TestPlayer alice = admin(t);
        define(t, alice, "frame-item", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        TestPlayer bob = t.player("bob");
        bob.attack(frame);
        t.tick(2);
        t.assertTrue(frame.getItem().is(Items.DIAMOND), "the frame should keep its diamond, but holds " + frame.getItem());
        t.assertFalse(frame.isRemoved(), "the frame should still hang");
    }

    @HadronTest
    void sneakingAndRightClickingPicksTheFirstCornerInSurvival(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.setOp(true);
        BlockPos a = t.pos(-2, 0, -2);
        BlockPos b = t.pos(2, 2, 2);
        t.setBlock(a, Blocks.STONE);
        t.setBlock(b, Blocks.STONE);
        alice.command("rg wand");
        // A sneaking right-click on a block reaches the server as the stick used in the air, aimed at the block.
        alice.player().lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(a));
        alice.player().setShiftKeyDown(true);
        alice.useBlock(a);
        alice.useItem();
        // The clicks are packets handled on the next tick; still sneaking then.
        t.tick();
        alice.player().setShiftKeyDown(false);
        alice.useBlock(b);
        alice.command("rg define sneak-corner");
        t.assertChat(alice, "Defined sneak-corner");
    }

    @HadronTest
    void aMemberCantFerryAnOutsiderIn(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "ferry-vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag ferry-vault entry deny");
        TestPlayer carol = t.player("carol");
        alice.command("rg addmember ferry-vault carol");
        t.assertChat(alice, "carol is a member of ferry-vault");
        TestPlayer bob = t.player("bob");
        Boat boat = EntityType.BOAT.create(t.level());
        BlockPos start = t.pos(0, 0, 0);
        boat.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        t.level().addFreshEntity(boat);
        carol.interact(boat);
        bob.interact(boat);
        t.assertTrue(bob.player().getVehicle() == boat, "bob should be riding with carol");
        t.tick(2);
        boat.setPos(boat.getX() + 3, boat.getY(), boat.getZ());
        t.tick(2);
        t.assertTrue(boat.getBlockX() < t.pos(2, 0, 0).getX(),
                "the boat carrying bob should not enter, but is at " + boat.blockPosition());
    }

    @HadronTest
    void chorusFruitCantDropAnOutsiderIn(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "chorus-vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag chorus-vault entry deny");
        TestPlayer bob = t.player("bob");
        BlockPos above = t.pos(3, 6, 0);
        Vec3 asked = new Vec3(above.getX() + 0.5, above.getY(), above.getZ() + 0.5);
        // Chorus fruit settles on the ground below the spot it asks for, inside the vault.
        EntityTeleportEvent chorus = new EntityTeleportEvent(bob.player(), t.level(), asked,
                TeleportCause.CHORUS_FRUIT, new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(chorus);
        t.assertTrue(chorus.isCancelled(), "chorus fruit landing in the vault should be refused");
        // The control: a command's teleport lands exactly where asked, above the vault, and goes ahead.
        EntityTeleportEvent exact = new EntityTeleportEvent(bob.player(), t.level(), asked,
                TeleportCause.COMMAND, new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(exact);
        t.assertFalse(exact.isCancelled(), "a teleport to just above the vault should go ahead");
    }

    @HadronTest
    void anOutsiderCantBoardABoatInside(TestContext t) {
        // The boat comes first: once the region exists, the test's own spawn inside it is refused.
        Boat boat = EntityType.BOAT.create(t.level());
        BlockPos inside = t.pos(2, 0, 0);
        boat.moveTo(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5);
        t.level().addFreshEntity(boat);
        TestPlayer alice = admin(t);
        define(t, alice, "boat-vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag boat-vault entry deny");
        alice.command("rg flag boat-vault use allow");
        TestPlayer bob = t.player("bob");
        bob.interact(boat);
        t.tick(2);
        t.assertTrue(bob.player().getVehicle() == null, "bob should not board a boat inside a region he may not enter");
        t.assertFalse(boat.isRemoved(), "the boat should still be there");
    }

    @HadronTest
    void aTeleportThatLandsInsideAnywaySendsThemBack(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "landing-vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag landing-vault entry deny");
        TestPlayer bob = t.player("bob");
        Vec3 start = bob.player().position();
        BlockPos above = t.pos(3, 6, 0);
        // A mod's random teleport asks for a spot above the vault, which entry allows, then settles on the ground
        // inside it.
        t.assertTrue(bob.player().randomTeleport(above.getX() + 0.5, above.getY(), above.getZ() + 0.5, false),
                "the random teleport should find ground");
        t.tick(2);
        t.assertTrue(bob.player().position().distanceTo(start) < 1,
                "bob should be sent back to where he came from, but stands at " + bob.player().blockPosition());
    }

    @HadronTest
    void aMachineCantAttackOutOfASafeZone(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "safe-zone", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag safe-zone pvp deny");
        TestPlayer bob = t.player("bob");
        TestPlayer carol = t.player("carol");
        // Bob's turret stands in the safe zone; bob and carol are both outside it.
        Actor turret = new Actor.Machine(t.level(), t.pos(3, 0, 0), Attribution.of(bob.uuid()));
        EntityDamageEvent shot = new EntityDamageEvent(carol.player(), t.level().damageSources().generic(), 4f,
                turret);
        t.plugin().getServer().getPluginManager().callEvent(shot);
        t.assertTrue(shot.isCancelled(), "a turret inside a pvp deny region should not hurt a player");
    }

    @HadronTest
    void anOwnerlessMachineCantAttackOutOfASafeZone(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "ownerless-zone", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag ownerless-zone pvp deny");
        TestPlayer carol = t.player("carol");
        Actor turret = new Actor.Machine(t.level(), t.pos(3, 0, 0), Attribution.none());
        EntityDamageEvent shot = new EntityDamageEvent(carol.player(), t.level().damageSources().generic(), 4f,
                turret);
        t.plugin().getServer().getPluginManager().callEvent(shot);
        t.assertTrue(shot.isCancelled(), "a turret nobody owns inside a pvp deny region should not hurt a player");
    }

    @HadronTest
    void gettingOffABoatIntoARegionSendsThemBack(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "dismount-vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag dismount-vault entry deny");
        TestPlayer bob = t.player("bob");
        Boat boat = EntityType.BOAT.create(t.level());
        BlockPos start = t.pos(0, 0, 0);
        boat.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        t.level().addFreshEntity(boat);
        bob.interact(boat);
        t.assertTrue(bob.player().getVehicle() == boat, "bob should be in the boat");
        t.tick(2);
        // As vanilla's dismount does: off, then set down beside the boat, which here is inside the vault.
        bob.player().stopRiding();
        BlockPos inside = t.pos(2, 0, 0);
        bob.player().setPos(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5);
        t.tick(3);
        t.assertTrue(bob.player().getBlockX() < inside.getX(),
                "bob should be sent back out of the vault, but stands at " + bob.player().blockPosition());
    }

    @HadronTest
    void aHopperOutsideCantDrainAChestInside(TestContext t) {
        BlockPos chest = t.pos(3, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        ((ChestBlockEntity) t.level().getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 8));
        TestPlayer alice = admin(t);
        define(t, alice, "hopper-vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        // The hopper comes after the region, below it and outside.
        t.setBlock(chest.below(), Blocks.HOPPER);
        t.tick(40);
        t.assertEquals(8, ((ChestBlockEntity) t.level().getBlockEntity(chest)).getItem(0).getCount(),
                "the diamonds left in the chest");
    }

    @HadronTest
    void anOutsiderCantRemoveAnArmorStandOutright(TestContext t) {
        ArmorStand stand = EntityType.ARMOR_STAND.create(t.level());
        BlockPos at = t.pos(3, 0, 0);
        stand.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        t.level().addFreshEntity(stand);
        TestPlayer alice = admin(t);
        define(t, alice, "stand-removal", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        EntityRemoveEvent killed = new EntityRemoveEvent(stand, EntityRemoveEvent.Cause.KILLED,
                new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(killed);
        t.assertTrue(killed.isCancelled(), "an outsider removing a stand in a region should be refused");
    }

    /** A double chest across a region's edge, made by a player so the halves join: x 1 outside, x 2 inside. */
    private static BlockPos doubleChestAcrossTheEdge(TestContext t, TestPlayer alice) {
        alice.hold(new ItemStack(Items.CHEST, 2));
        alice.placeBlock(t.pos(1, 0, 0));
        alice.hold(new ItemStack(Items.CHEST, 2));
        alice.placeBlock(t.pos(2, 0, 0));
        BlockPos inside = t.pos(2, 0, 0);
        ((ChestBlockEntity) t.level().getBlockEntity(inside)).setItem(0, new ItemStack(Items.DIAMOND, 8));
        // An empty hand, so the wand /region wand hands out lands in it.
        alice.hold(ItemStack.EMPTY);
        return inside;
    }

    @HadronTest
    void anOutsiderCantOpenAProtectedDoubleChestFromOutside(TestContext t) {
        TestPlayer alice = admin(t);
        doubleChestAcrossTheEdge(t, alice);
        define(t, alice, "double-chest", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag double-chest use deny");
        TestPlayer bob = t.player("bob");
        bob.useBlock(t.pos(1, 0, 0));
        t.tick();
        t.assertTrue(bob.player().containerMenu == bob.player().inventoryMenu,
                "bob should not have the double chest open, but has " + bob.player().containerMenu);
    }

    @HadronTest
    void aRemovedMemberCantTakeFromAMenuTheyHadOpen(TestContext t) {
        BlockPos chest = t.pos(3, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        ((ChestBlockEntity) t.level().getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 8));
        TestPlayer alice = admin(t);
        define(t, alice, "open-menu", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer carol = t.player("carol");
        alice.command("rg addmember open-menu carol");
        t.assertChat(alice, "carol is a member of open-menu");
        carol.useBlock(chest);
        t.tick();
        t.assertFalse(carol.player().containerMenu == carol.player().inventoryMenu, "carol should have the chest open");
        alice.command("rg removeplayer open-menu carol");
        carol.clickSlot(0);
        t.tick();
        t.assertEquals(8, ((ChestBlockEntity) t.level().getBlockEntity(chest)).getItem(0).getCount(),
                "the diamonds left in the chest");
    }

    @HadronTest
    void aHopperInsideStillWorks(TestContext t) {
        BlockPos chest = t.pos(3, 1, 0);
        t.setBlock(chest.below(), Blocks.HOPPER);
        t.setBlock(chest, Blocks.CHEST);
        ((ChestBlockEntity) t.level().getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 8));
        define(t, admin(t), "hopper-inside", t.pos(2, 0, -1), t.pos(4, 2, 1));
        t.tick(40);
        t.assertTrue(((ChestBlockEntity) t.level().getBlockEntity(chest)).getItem(0).getCount() < 8,
                "a hopper inside the region should still pull from a chest inside it");
    }

    @HadronTest
    void itemsCantLeaveForAnInventoryNothingIdentified(TestContext t) {
        BlockPos chest = t.pos(3, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        define(t, admin(t), "unknown-sink", t.pos(2, 0, -1), t.pos(4, 2, 1));
        InventoryHolder.Block source = new InventoryHolder.Block(t.level(), chest);
        InventoryMoveItemEvent pipe = new InventoryMoveItemEvent(source, 0, new ItemStack(Items.DIAMOND, 8),
                InventoryHolder.Unknown.INSTANCE, new ItemStack(Items.DIAMOND), InventoryHolder.Unknown.INSTANCE,
                Actor.Nature.INSTANCE);
        t.plugin().getServer().getPluginManager().callEvent(pipe);
        t.assertTrue(pipe.isCancelled(), "items should not leave a region for an inventory with no place");
    }

    @HadronTest
    void anOutsidersMachineCantMoveItemsInsideARegion(TestContext t) {
        BlockPos a = t.pos(3, 0, 0);
        BlockPos b = t.pos(3, 0, 1);
        t.setBlock(a, Blocks.CHEST);
        t.setBlock(b, Blocks.BARREL);
        define(t, admin(t), "machine-moves", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        InventoryHolder.Block from = new InventoryHolder.Block(t.level(), a);
        InventoryMoveItemEvent move = new InventoryMoveItemEvent(from, 0, new ItemStack(Items.DIAMOND, 8),
                new InventoryHolder.Block(t.level(), b), new ItemStack(Items.DIAMOND), from,
                new Actor.Machine(t.level(), t.pos(0, 0, 0), Attribution.of(bob.uuid())));
        t.plugin().getServer().getPluginManager().callEvent(move);
        t.assertTrue(move.isCancelled(), "bob's machine should not move items between inventories in a region");
    }

    @HadronTest
    void damageNobodyIsBehindCantReachASafeZone(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "unknown-safe", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        alice.command("rg flag unknown-safe pvp deny");
        TestPlayer carol = t.player("carol");
        EntityDamageEvent hit = new EntityDamageEvent(carol.player(), t.level().damageSources().generic(), 4f,
                Actor.Unknown.INSTANCE);
        t.plugin().getServer().getPluginManager().callEvent(hit);
        t.assertTrue(hit.isCancelled(), "damage nobody is behind should not hurt a player in a pvp deny region");
    }

    @HadronTest
    void anOutsiderCantBreakABoatInside(TestContext t) {
        Boat boat = EntityType.BOAT.create(t.level());
        BlockPos inside = t.pos(1, 0, 1);
        boat.moveTo(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5);
        t.level().addFreshEntity(boat);
        define(t, admin(t), "boat-break", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        TestPlayer bob = t.player("bob");
        bob.player().setGameMode(GameType.CREATIVE);
        bob.attack(boat);
        t.tick();
        t.assertFalse(boat.isRemoved(), "an outsider's hit should not break a boat in a region");
    }

    @HadronTest
    void aRemovedMemberCantShiftClickIntoAMenuTheyHadOpen(TestContext t) {
        BlockPos chest = t.pos(3, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        TestPlayer alice = admin(t);
        define(t, alice, "shift-menu", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer carol = t.player("carol");
        alice.command("rg addmember shift-menu carol");
        t.assertChat(alice, "carol is a member of shift-menu");
        carol.useBlock(chest);
        t.tick();
        t.assertFalse(carol.player().containerMenu == carol.player().inventoryMenu, "carol should have the chest open");
        carol.hold(new ItemStack(Items.DIRT, 16));
        alice.command("rg removeplayer shift-menu carol");
        // Her own hotbar's first slot, below the chest's 27 and her 27 others.
        carol.clickSlot(54, 0, ClickType.QUICK_MOVE);
        t.tick();
        ChestBlockEntity box = (ChestBlockEntity) t.level().getBlockEntity(chest);
        for (int i = 0; i < box.getContainerSize(); i++) {
            t.assertTrue(box.getItem(i).isEmpty(), "the chest should stay empty, but slot " + i + " holds " + box.getItem(i));
        }
    }

    @HadronTest
    void aThrownPoisonCantReachASafeZone(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "poison-safe", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        alice.command("rg flag poison-safe pvp deny");
        TestPlayer bob = t.player("bob");
        TestPlayer carol = t.player("carol");
        EntityPotionEffectEvent poison = new EntityPotionEffectEvent(carol.player(),
                new EntityPotionEffectEvent.Change.Added(new MobEffectInstance(MobEffects.POISON, 100)),
                new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(poison);
        t.assertTrue(poison.isCancelled(), "bob's poison should not take hold on a player in a pvp deny region");
    }

    @HadronTest
    void theServersDamageIsntPvp(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "server-safe", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        alice.command("rg flag server-safe pvp deny");
        TestPlayer carol = t.player("carol");
        EntityDamageEvent hit = new EntityDamageEvent(carol.player(), t.level().damageSources().generic(), 4f,
                new Actor.Server("plugin Test"));
        t.plugin().getServer().getPluginManager().callEvent(hit);
        t.assertFalse(hit.isCancelled(), "the server's own damage should go through");
    }

    @HadronTest
    void fireFromOutsideCantBurnInside(TestContext t) {
        BlockPos wool = t.pos(3, 0, 0);
        t.setBlock(wool, Blocks.WHITE_WOOL);
        define(t, admin(t), "fire-proof", t.pos(3, 0, 0), t.pos(4, 1, 1));
        BlockBurnEvent fromOutside = new BlockBurnEvent(t.level(), wool, Blocks.WHITE_WOOL.defaultBlockState(),
                t.pos(2, 0, 0), Actor.Nature.INSTANCE);
        t.plugin().getServer().getPluginManager().callEvent(fromOutside);
        t.assertTrue(fromOutside.isCancelled(), "fire burning in from outside should be refused");
        BlockBurnEvent fromInside = new BlockBurnEvent(t.level(), wool, Blocks.WHITE_WOOL.defaultBlockState(),
                t.pos(4, 0, 0), Actor.Nature.INSTANCE);
        t.plugin().getServer().getPluginManager().callEvent(fromInside);
        t.assertFalse(fromInside.isCancelled(), "fire already inside the region burns as it would");
        BlockIgniteEvent unknown = new BlockIgniteEvent(t.level(), t.pos(3, 1, 0), Blocks.AIR.defaultBlockState(),
                Blocks.FIRE.defaultBlockState(), Actor.Nature.INSTANCE);
        t.plugin().getServer().getPluginManager().callEvent(unknown);
        t.assertTrue(unknown.isCancelled(), "fire from nowhere known should not catch inside");
    }

    @HadronTest
    void aChestMenuRegionsNeverSawOpenStillCantBeTakenFrom(TestContext t) {
        BlockPos chest = t.pos(3, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        ChestBlockEntity box = (ChestBlockEntity) t.level().getBlockEntity(chest);
        box.setItem(0, new ItemStack(Items.DIAMOND, 8));
        define(t, admin(t), "unseen-menu", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        // As if opened before Regions was listening, after a reload: no open event.
        ServerPlayer viewer = bob.player();
        viewer.containerMenu = ChestMenu.threeRows(99, viewer.getInventory(), box);
        bob.clickSlot(0);
        t.tick();
        t.assertEquals(8, box.getItem(0).getCount(), "the diamonds left in the chest");
    }

    @HadronTest
    void aHorsesInventoryWorksForAMemberAndNotOnceRemoved(TestContext t) {
        BlockPos chest = t.pos(2, 0, 1);
        t.setBlock(chest, Blocks.CHEST);
        Horse horse = EntityType.HORSE.create(t.level());
        BlockPos at = t.pos(3, 0, 0);
        horse.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        horse.setTamed(true);
        t.level().addFreshEntity(horse);
        TestPlayer alice = admin(t);
        define(t, alice, "horse-stable", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer carol = t.player("carol");
        alice.command("rg addmember horse-stable carol");
        t.assertChat(alice, "carol is a member of horse-stable");
        carol.hold(new ItemStack(Items.SADDLE));
        // A chest open first: the horse's menu opens before the chest's close arrives.
        carol.useBlock(chest);
        t.tick();
        horse.openCustomInventoryScreen(carol.player());
        t.tick();
        t.assertFalse(carol.player().containerMenu == carol.player().inventoryMenu, "carol should have the horse open");
        // A harmless click (an empty slot of her own) keeps the menu open: it was tracked though built late.
        carol.clickSlot(5);
        t.tick();
        t.assertFalse(carol.player().containerMenu == carol.player().inventoryMenu, "a member's menu should stay open");
        alice.command("rg removeplayer horse-stable carol");
        // Her hotbar's first slot, after the horse's two and her 27 others.
        carol.clickSlot(29, 0, ClickType.QUICK_MOVE);
        t.tick();
        t.assertFalse(horse.isSaddled(), "a removed member should not saddle the horse");
    }

    /** Stands {@code who} at {@code pos}, well clear of the regions these tests define. */
    private static void standAt(TestPlayer who, BlockPos pos) {
        who.player().teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    @HadronTest
    void aRemoteTerminalOutsideCantReachAChestInside(TestContext t) {
        BlockPos chest = t.pos(3, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        ChestBlockEntity box = (ChestBlockEntity) t.level().getBlockEntity(chest);
        define(t, admin(t), "terminal-reach", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        BlockPos terminal = t.pos(-4, 0, 0);
        standAt(bob, terminal.north());
        // A mod's terminal, open on its own block outside, whose slots show the chest inside: bob shift-clicks one of
        // his own slots, which moves the stack into the chest.
        ChestMenu menu = ChestMenu.threeRows(99, bob.player().getInventory(), box);
        InventoryClickEvent click = new InventoryClickEvent(bob.player(), new InventoryHolder.Entity(bob.player()), menu,
                new InventoryHolder.Block(t.level(), terminal), 30, 0, ClickType.QUICK_MOVE,
                new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(click);
        t.assertTrue(click.isCancelled(), "a click that can move items into a chest inside should be refused");
    }

    @HadronTest
    void aMenuNothingIdentifiesIsRefusedToAnOutsiderWhereverTheyStand(TestContext t) {
        define(t, admin(t), "unplaced-menu", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        standAt(bob, t.pos(-6, 0, 0));
        ChestMenu menu = ChestMenu.threeRows(99, bob.player().getInventory(), new SimpleContainer(27));
        InventoryClickEvent click = new InventoryClickEvent(bob.player(), InventoryHolder.Unknown.INSTANCE, menu,
                InventoryHolder.Unknown.INSTANCE, 0, 0, ClickType.PICKUP, new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(click);
        t.assertTrue(click.isCancelled(), "an inventory nobody can place could be inside, so an outsider is refused");
    }

    @HadronTest
    void anOutsidersPlayerInventoryStaysTheirsInsideARegion(TestContext t) {
        define(t, admin(t), "own-pockets", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        standAt(bob, t.pos(3, 0, 0));
        ServerPlayer viewer = bob.player();
        InventoryClickEvent click = new InventoryClickEvent(viewer, new InventoryHolder.Entity(viewer),
                viewer.inventoryMenu, new InventoryHolder.Entity(viewer), 36, 0, ClickType.QUICK_MOVE,
                new Actor.Player(viewer));
        t.plugin().getServer().getPluginManager().callEvent(click);
        t.assertFalse(click.isCancelled(), "bob's own inventory goes where he goes, so a region doesn't refuse it");
    }

    @HadronTest
    void anOutsidersCableCantDrainABatteryInside(TestContext t) {
        define(t, admin(t), "battery-room", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        ResourceTransferEvent drain = new ResourceTransferEvent(new Actor.Player(bob.player()), Resource.ENERGY,
                new ResourceHolder.Block(t.level(), t.pos(3, 0, 0)), new ResourceHolder.Block(t.level(), t.pos(-4, 0, 0)),
                1000);
        t.plugin().getServer().getPluginManager().callEvent(drain);
        t.assertTrue(drain.isCancelled(), "bob's cable should not take energy out of a battery inside");
        ResourceTransferEvent outside = new ResourceTransferEvent(new Actor.Player(bob.player()), Resource.ENERGY,
                new ResourceHolder.Block(t.level(), t.pos(-5, 0, 0)), new ResourceHolder.Block(t.level(), t.pos(-4, 0, 0)),
                1000);
        t.plugin().getServer().getPluginManager().callEvent(outside);
        t.assertFalse(outside.isCancelled(), "a transfer between two blocks outside is none of the region's business");
    }

    @HadronTest
    void anOutsiderCantStripAnArmorStandThroughAnEditor(TestContext t) {
        ArmorStand stand = EntityType.ARMOR_STAND.create(t.level());
        BlockPos at = t.pos(3, 0, 0);
        stand.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        t.level().addFreshEntity(stand);
        define(t, admin(t), "stand-editor", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        ArmorStandManipulateEvent edit = new ArmorStandManipulateEvent(stand, EquipmentSlot.HEAD, InteractionHand.MAIN_HAND,
                ItemStack.EMPTY, stand.getItemBySlot(EquipmentSlot.HEAD), new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(edit);
        t.assertTrue(edit.isCancelled(), "bob should not take the helmet off a stand inside");
    }

    @HadronTest
    void aPublicParkElsewhereDoesntOpenAVaultToAnUnplacedInventory(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        standAt(alice, t.pos(-7, 0, 3));
        define(t, alice, "park", t.pos(-8, 0, -1), t.pos(-6, 2, 1));
        alice.command("rg priority park 1");
        alice.command("rg flag park use allow");
        TestPlayer bob = t.player("bob");
        standAt(bob, t.pos(-7, 0, 0));
        ChestMenu menu = ChestMenu.threeRows(99, bob.player().getInventory(), new SimpleContainer(27));
        InventoryClickEvent click = new InventoryClickEvent(bob.player(), InventoryHolder.Unknown.INSTANCE, menu,
                InventoryHolder.Unknown.INSTANCE, 0, 0, ClickType.PICKUP, new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(click);
        t.assertTrue(click.isCancelled(), "an inventory nobody can place may be in the vault, which the park, "
                + "though higher, doesn't cover");
    }

    @HadronTest
    void nothingCarriesBetweenTwoInventoriesNobodyCanPlace(TestContext t) {
        define(t, admin(t), "unknown-both", t.pos(2, 0, -1), t.pos(4, 2, 1));
        InventoryMoveItemEvent pipe = new InventoryMoveItemEvent(InventoryHolder.Unknown.INSTANCE, 0,
                new ItemStack(Items.DIAMOND, 8), InventoryHolder.Unknown.INSTANCE, new ItemStack(Items.DIAMOND),
                InventoryHolder.Unknown.INSTANCE, Actor.Nature.INSTANCE);
        t.plugin().getServer().getPluginManager().callEvent(pipe);
        t.assertTrue(pipe.isCancelled(), "either end may be in the region and the other outside it");
    }
}
