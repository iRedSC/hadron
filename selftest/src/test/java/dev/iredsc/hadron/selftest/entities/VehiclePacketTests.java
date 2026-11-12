package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.player.PlayerMoveEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/**
 * Moves a client sends (steering a boat, walking) are applied by the packet handler between ticks: each is asked
 * about where the packet is applied, and refused, put back and the client corrected, before anything is broadcast.
 */
public final class VehiclePacketTests {
    /** Alice drives a boat at pos(2, 0, 0) + (0.9, 0.75) facing south; bob sits behind her. */
    private static Boat boat(TestContext t, TestPlayer alice, TestPlayer bob) {
        BlockPos at = t.pos(2, 0, 0);
        Boat boat = spawn(t, EntityType.BOAT, at);
        boat.moveTo(at.getX() + 0.9, at.getY(), at.getZ() + 0.75, 0, 0);
        boat.setNoGravity(true);
        t.assertTrue(alice.player().startRiding(boat, true) && bob.player().startRiding(boat, true),
                "alice and bob should get in the boat");
        t.tick(5);
        return boat;
    }

    /** Alice's client says the boat is {@code dx} further east, facing {@code yaw}. */
    private static void steer(TestPlayer alice, Boat boat, double dx, float yaw) {
        Boat client = new Boat(EntityType.BOAT, boat.level());
        client.moveTo(boat.getX() + dx, boat.getY(), boat.getZ(), yaw, 0);
        alice.player().connection.handleMoveVehicle(new ServerboundMoveVehiclePacket(client));
    }

    private static List<EntityMoveEvent> refuse(TestContext t, Entity entity) {
        List<EntityMoveEvent> refused = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMoveEvent e) {
                if (e.getEntity() == entity) {
                    refused.add(e);
                    e.setCancelled(true);
                }
            }
        });
        return refused;
    }

    /**
     * The packets {@code watcher} received that show {@code entity} somewhere else than it is: a relative move that
     * moves it, or a teleport elsewhere. Vanilla also resyncs every 60 ticks with a move of zero, which shows nothing.
     */
    static long movesShown(TestContext t, TestPlayer watcher, Entity entity) {
        Vec3 at = entity.position();
        return watcher.received(ClientboundMoveEntityPacket.class).stream()
                .filter(p -> p.getEntity(t.level()) == entity && p.hasPosition()
                        && (p.getXa() != 0 || p.getYa() != 0 || p.getZa() != 0)).count()
                + watcher.received(ClientboundTeleportEntityPacket.class).stream()
                .filter(p -> p.getId() == entity.getId()
                        && new Vec3(p.getX(), p.getY(), p.getZ()).distanceTo(at) > 1.0E-3).count();
    }

    /** A steering turn swings only the driver across a block edge: asked at once, and refused, she stays. */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aSteeringTurnThatMovesOnlyTheDriverIsAskedAtThePacket(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Boat boat = boat(t, alice, bob);
        BlockPos aliceWas = alice.player().blockPosition();
        List<EntityMoveEvent> refused = refuse(t, boat);
        steer(alice, boat, 0, -90);
        t.assertTrue(!refused.isEmpty(), "the turn should be asked about where the packet is applied");
        t.assertEquals(aliceWas, alice.player().blockPosition(), "alice's block, the turn refused");
        t.tick(3);
        t.assertEquals(aliceWas, alice.player().blockPosition(), "alice's block a few ticks on");
    }

    /** A steered move refused: the boat and both riders are exactly where they were, and alice's client is told. */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aRefusedSteeredMovePutsEveryoneBackAndCorrectsTheDriver(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Boat boat = boat(t, alice, bob);
        Vec3 boatWas = boat.position(), aliceWas = alice.player().position(), bobWas = bob.player().position();
        refuse(t, boat);
        alice.clearReceived();
        steer(alice, boat, 2, 0);
        t.assertTrue(boat.position().distanceTo(boatWas) < 1.0E-6, "the boat should be back at " + boatWas
                + ", but is at " + boat.position());
        t.assertTrue(alice.player().position().distanceTo(aliceWas) < 1.0E-6, "alice should be back at " + aliceWas
                + ", but is at " + alice.player().position());
        t.assertTrue(bob.player().position().distanceTo(bobWas) < 1.0E-6, "bob should be back at " + bobWas
                + ", but is at " + bob.player().position());
        // The correction travels to alice's client on the connection: wait for it to arrive.
        t.await("alice's client to be told where the boat really is",
                () -> !alice.received(ClientboundMoveVehiclePacket.class).isEmpty(), 5);
        steer(alice, boat, -0.2, 0);
        t.assertTrue(boat.position().distanceTo(boatWas.add(-0.2, 0, 0)) < 1.0E-3, "a small move within its block "
                + "after the refusal should go through, to " + boatWas.add(-0.2, 0, 0) + ", but the boat is at "
                + boat.position());
    }

    /** Refused steered moves never reach a player watching the boat. */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aRefusedSteeredMoveIsNeverSentToAWatcher(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        TestPlayer carol = t.player("carol");
        Boat boat = boat(t, alice, bob);
        t.tick(6);
        refuse(t, boat);
        carol.clearReceived();
        for (int i = 0; i < 4; i++) {
            steer(alice, boat, 2, 0);
            t.tick();
        }
        t.assertEquals(0L, movesShown(t, carol, boat), "the packets showing carol the boat moved");
    }

    /** A refused step never reaches a player watching the walker, and the walker is put back. */
    @HadronTest
    @Covers(PlayerMoveEvent.class)
    void aRefusedStepIsNeverSentToAWatcher(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer carol = t.player("carol");
        t.tick(6);
        Vec3 was = alice.player().position();
        List<EntityMoveEvent> refused = refuse(t, alice.player());
        carol.clearReceived();
        alice.player().connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(was.x + 1.5, was.y, was.z,
                true));
        t.assertTrue(!refused.isEmpty(), "the step should be asked about where the packet is applied");
        t.tick(2);
        t.assertTrue(alice.player().position().distanceTo(was) < 1.0E-6, "alice should be back at " + was
                + ", but is at " + alice.player().position());
        t.assertEquals(0L, movesShown(t, carol, alice.player()), "the packets showing carol alice moved");
    }
}
