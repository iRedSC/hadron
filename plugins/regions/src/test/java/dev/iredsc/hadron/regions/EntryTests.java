package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockChange;
import dev.iredsc.hadron.api.event.block.StructureMoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityDismountEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.KnownGap;
import dev.iredsc.hadron.test.SideEffects;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;

/**
 * Entry where the events state it: a teleport's landing, a dismount's set-down and each rider's own ride, refused
 * before anything moves.
 */
public final class EntryTests {
    /** An entry-deny region over every block of the floor but the one players arrive in, by a store write. */
    private static void vaultsAllAround(TestContext t, String id) {
        int[][] boxes = {{-8, -8, 8, -1}, {-8, 1, 8, 8}, {-8, 0, -1, 0}, {1, 0, 8, 0}};
        for (int i = 0; i < boxes.length; i++) {
            ((RegionsPlugin) t.plugin()).regions().put(Region.of(id + i, t.level().dimension().location(),
                    BoundingBox.fromCorners(t.pos(boxes[i][0], 0, boxes[i][1]), t.pos(boxes[i][2], 8, boxes[i][3])))
                    .withRules(Rules.fresh().withFlag(Bundle.ENTRY, Optional.of(Rules.Verdict.DENY))));
        }
    }

    @HadronTest
    void chorusFruitCantDropAnOutsiderIn(TestContext t) {
        RegionsTests.admin(t);
        TestPlayer bob = t.player("bob");
        BlockPos start = bob.player().blockPosition();
        vaultsAllAround(t, "chorus-vault");
        List<EntityTeleportEvent> asked = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() == bob.player()) asked.add(e);
            }
        });
        // Wherever on the floor the fruit finds to land is a vault, unless it is the block bob is in.
        Items.CHORUS_FRUIT.finishUsingItem(new ItemStack(Items.CHORUS_FRUIT), t.level(), bob.player());
        List<EntityTeleportEvent> into = asked.stream().filter(e -> !BlockPos.containing(e.getTo()).equals(start))
                .toList();
        t.assertFalse(into.isEmpty(), "chorus fruit should have found somewhere in a vault to land");
        for (EntityTeleportEvent e : into) {
            t.assertTrue(e.isCancelled(), "a landing in the vault at " + BlockPos.containing(e.getTo())
                    + " should be refused");
        }
        // No tick has passed: refused before he moved, not sent back after.
        t.assertEquals(start, bob.player().blockPosition(), "bob's block, every landing refused");
    }

    @HadronTest
    void aRandomTeleportThatWouldLandInsideNeverMovesThem(TestContext t) {
        TestPlayer alice = RegionsTests.admin(t);
        RegionsTests.define(t, alice, "landing-vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag landing-vault entry deny");
        TestPlayer bob = t.player("bob");
        Vec3 start = bob.player().position();
        BlockPos above = t.pos(3, 6, 0);
        SideEffects effects = t.watchSideEffects();
        // A mod's random teleport asks for a spot above the vault, which entry allows, and would settle on the ground
        // inside it.
        bob.player().randomTeleport(above.getX() + 0.5, above.getY(), above.getZ() + 0.5, false);
        // No tick has passed: refused before he moved, not sent back after.
        t.assertEquals(start, bob.player().position(), "where bob stands, his landing in the vault refused");
        effects.assertNone("a refused random teleport");
        // The control: one that settles outside the vault goes ahead.
        t.assertTrue(bob.player().randomTeleport(above.getX() - 5.5, above.getY(), above.getZ() + 0.5, false),
                "a random teleport that lands outside the vault should go ahead");
        t.assertEquals(t.pos(-3, 0, 0), bob.player().blockPosition(), "bob's block after landing outside");
    }

    @HadronTest
    void anOutsiderCantGetOffABoatIntoARegion(TestContext t) {
        // The boat comes first, beside where the vault will be.
        Boat boat = EntityType.BOAT.create(t.level());
        BlockPos beside = t.pos(1, 0, 0);
        boat.moveTo(beside.getX() + 0.5, beside.getY(), beside.getZ() + 0.5);
        t.level().addFreshEntity(boat);
        TestPlayer alice = RegionsTests.admin(t);
        RegionsTests.define(t, alice, "dismount-vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag dismount-vault entry deny");
        TestPlayer bob = t.player("bob");
        bob.interact(boat);
        t.assertTrue(bob.player().getVehicle() == boat, "bob should be in the boat");
        // Facing east: Minecraft sets a rider down on the side they face, which here is inside the vault.
        bob.player().setYRot(-90f);
        t.tick(2);
        Vec3 seat = bob.player().position();
        List<EntityDismountEvent> asked = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDismountEvent e) {
                if (e.getEntity() == bob.player()) asked.add(e);
            }
        });
        SideEffects effects = t.watchSideEffects();
        // Sneaking, as the client says it: the rider gets off on its next tick.
        bob.player().connection.handlePlayerInput(new ServerboundPlayerInputPacket(0, 0, false, true));
        t.tick(3);
        t.assertFalse(asked.isEmpty(), "sneaking should have asked to get off");
        for (EntityDismountEvent e : asked) {
            t.assertTrue(BlockPos.containing(e.getTo()).getX() >= t.pos(2, 0, 0).getX(), "this test needs a dismount "
                    + "that sets bob down in the vault, but it set him down at " + e.getTo());
            t.assertTrue(e.isCancelled(), "getting off into the vault should be refused");
        }
        t.assertTrue(bob.player().getVehicle() == boat, "bob should still be in the boat");
        t.assertEquals(seat, bob.player().position(), "bob's seat");
        // Players standing by creep a few centimetres a tick, counted as walking; nothing else may show.
        List<SideEffects.Effect> left = effects.seen().stream().filter(e -> !(e instanceof SideEffects.StatChanged st
                && st.stat().getValue().equals(Stats.WALK_ONE_CM))).toList();
        t.assertTrue(left.isEmpty(), "a refused dismount should leave no side effect, but left " + left);
    }

    /** Carol alone in a boat in the block just west of x 3, facing {@code yaw}. */
    private static Boat boatAtTheEdge(TestContext t, TestPlayer carol, float yaw) {
        Boat boat = EntityType.BOAT.create(t.level());
        BlockPos at = t.pos(2, 0, 0);
        boat.moveTo(at.getX() + 0.75, at.getY(), at.getZ() + 0.5, yaw, 0f);
        boat.setNoGravity(true);
        t.level().addFreshEntity(boat);
        carol.interact(boat);
        return boat;
    }

    /** Carol's client says her boat has turned in place to face {@code yaw}. */
    private static void turn(TestPlayer carol, Boat boat, float yaw) {
        Boat client = new Boat(EntityType.BOAT, boat.level());
        client.moveTo(boat.getX(), boat.getY(), boat.getZ(), yaw, 0f);
        carol.player().connection.handleMoveVehicle(new ServerboundMoveVehiclePacket(client));
    }

    @HadronTest
    void aMemberCantSwingAnOutsiderInByTurningTheBoat(TestContext t) {
        TestPlayer alice = RegionsTests.admin(t);
        // From the ground down: seated in a boat, a rider's feet are in the ground's own layer, and a turn in place
        // moves nothing but the seats.
        RegionsTests.define(t, alice, "turn-vault", t.pos(3, -1, -1), t.pos(5, 2, 1));
        alice.command("rg flag turn-vault entry deny");
        TestPlayer carol = t.player("carol");
        alice.command("rg addmember turn-vault carol");
        TestPlayer bob = t.player("bob");
        int edge = t.pos(3, 0, 0).getX();
        // Facing east, the back seat is west of the boat: bob sits outside the vault.
        Boat boat = boatAtTheEdge(t, carol, -90f);
        bob.interact(boat);
        t.assertTrue(carol.player().getVehicle() == boat && bob.player().getVehicle() == boat,
                "carol and bob should be in the boat");
        t.tick(5);
        Vec3 bobWas = bob.player().position();
        t.assertTrue(bobWas.x < edge, "bob should start outside the vault, but sits at " + bobWas);
        // Turned to face west, the boat stays in its block and its back seat swings east, into the vault.
        turn(carol, boat, 90f);
        t.assertEquals(bobWas, bob.player().position(), "bob's seat, the turn refused");
        t.assertEquals(-90f, boat.getYRot(), "the boat's facing, put back");
        t.tick(3);
        t.assertTrue(bob.player().getX() < edge, "bob should still be outside the vault a few ticks on, but sits at "
                + bob.player().position());
        // The control: once bob is a member, the same turn carries him in.
        alice.command("rg addmember turn-vault bob");
        turn(carol, boat, 90f);
        t.assertTrue(bob.player().getX() >= edge, "the turn should carry a member into the vault, but bob sits at "
                + bob.player().position());
    }

    @HadronTest
    void anOutsiderCantBoardASeatInsideARegion(TestContext t) {
        TestPlayer alice = RegionsTests.admin(t);
        RegionsTests.define(t, alice, "seat-vault", t.pos(3, -1, -1), t.pos(5, 2, 1));
        alice.command("rg flag seat-vault entry deny");
        TestPlayer carol = t.player("carol");
        alice.command("rg addmember seat-vault carol");
        TestPlayer bob = t.player("bob");
        // Facing west, the boat is outside the vault and its back seat inside it.
        Boat boat = boatAtTheEdge(t, carol, 90f);
        t.assertTrue(carol.player().getVehicle() == boat, "carol should be in the boat");
        bob.interact(boat);
        t.tick(2);
        t.assertTrue(bob.player().getX() < t.pos(3, 0, 0).getX(), "bob should not be seated in the vault, but sits at "
                + bob.player().position());
    }

    @HadronTest
    void aMemberBoardingCantShiftAnOutsiderAboardIntoARegion(TestContext t) {
        TestPlayer alice = RegionsTests.admin(t);
        RegionsTests.define(t, alice, "shift-vault", t.pos(3, -1, -1), t.pos(5, 2, 1));
        alice.command("rg flag shift-vault entry deny");
        TestPlayer carol = t.player("carol");
        alice.command("rg addmember shift-vault carol");
        TestPlayer bob = t.player("bob");
        int edge = t.pos(3, 0, 0).getX();
        // Facing east, a tenth of a block short of the vault: a lone rider sits where the boat is, outside it, and
        // moves 0.2 forward, into it, when a second boards behind.
        Boat boat = EntityType.BOAT.create(t.level());
        boat.moveTo(edge - 0.1, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5, -90f, 0f);
        boat.setNoGravity(true);
        t.level().addFreshEntity(boat);
        bob.interact(boat);
        t.assertTrue(bob.player().getVehicle() == boat, "bob should be in the boat");
        t.tick(2);
        Vec3 bobWas = bob.player().position();
        t.assertTrue(bobWas.x < edge, "bob should start outside the vault, but sits at " + bobWas);
        carol.interact(boat);
        t.assertTrue(carol.player().getVehicle() == null, "carol's boarding, which shifts bob into the vault, should "
                + "be refused");
        t.assertEquals(bobWas, bob.player().position(), "bob's seat, the boarding refused");
        t.tick(3);
        t.assertTrue(bob.player().getX() < edge, "bob should still be outside the vault a few ticks on, but sits at "
                + bob.player().position());
        // The control: once bob is a member, the same boarding shifts him in.
        alice.command("rg addmember shift-vault bob");
        carol.interact(boat);
        t.tick(2);
        t.assertTrue(carol.player().getVehicle() == boat, "carol should board once the rider she shifts is a member");
        t.assertTrue(bob.player().getX() >= edge, "the boarding should shift a member into the vault, but bob sits at "
                + bob.player().position());
    }

    @HadronTest
    @KnownGap("A rider swung across an edge by a turn in place, in a region that starts above the block its feet point "
            + "is in: the boat's own block doesn't change in a turn, and a ride states the block its rider's feet are "
            + "in, not the space the rider takes up")
    void anOutsiderSeatedInABoatCantBeSwungInAboveTheGround(TestContext t) {
        TestPlayer alice = RegionsTests.admin(t);
        RegionsTests.define(t, alice, "ground-vault", t.pos(3, 0, -1), t.pos(5, 2, 1));
        alice.command("rg flag ground-vault entry deny");
        TestPlayer carol = t.player("carol");
        alice.command("rg addmember ground-vault carol");
        TestPlayer bob = t.player("bob");
        Boat boat = boatAtTheEdge(t, carol, -90f);
        bob.interact(boat);
        t.tick(5);
        turn(carol, boat, 90f);
        t.assertTrue(bob.player().getX() < t.pos(3, 0, 0).getX(), "bob, most of him above the ground, should not be "
                + "swung into the vault, but sits at " + bob.player().position());
    }

    @HadronTest
    void aShipCantCarryAnOutsiderIn(TestContext t) {
        TestPlayer alice = RegionsTests.admin(t);
        RegionsTests.define(t, alice, "ship-vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag ship-vault entry deny");
        TestPlayer carol = t.player("carol");
        alice.command("rg addmember ship-vault carol");
        TestPlayer bob = t.player("bob");
        // Carol's ship, a block outside the vault that stays outside it, carries its crew three blocks east, into it.
        t.assertTrue(sails(t, carol, bob), "a member's ship carrying an outsider into the vault should be refused");
        t.assertFalse(sails(t, carol, carol), "a member's ship carrying only a member should sail");
    }

    /** Whether {@code owner}'s ship moving its crew from the centre three blocks east was refused. */
    private static boolean sails(TestContext t, TestPlayer owner, TestPlayer crew) {
        BlockPos deck = t.pos(-2, 0, 0);
        StructureMoveEvent move = new StructureMoveEvent(t.level(), deck, Blocks.OAK_PLANKS.defaultBlockState(),
                StructureMoveEvent.Phase.MOVE, new Matrix4d(), new Matrix4d().translation(3, 0, 0), List.of(), List.of(),
                List.of(BlockChange.unchanged(t.level(), deck, Blocks.OAK_PLANKS.defaultBlockState())),
                List.of(crew.player()), new Actor.Player(owner.player()));
        t.plugin().getServer().getPluginManager().callEvent(move);
        return move.isCancelled();
    }
}
