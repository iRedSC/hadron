package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent.TeleportCause;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Regions as an operator makes them and as players and the world meet them. */
public final class RegionsTests {
    /** An op in creative, who makes regions and is let through them. */
    private static TestPlayer admin(TestContext t) {
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
}
