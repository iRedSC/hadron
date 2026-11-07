package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.assertPlayer;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;

/** EntityMoveEvent: a vehicle a player drives crossing into another block is the driver's move, and can be undone. */
public final class VehicleMoveTests {
    /** Alice in a boat at pos(2, 0, 0), the boat's position tracked for a tick. */
    private static Boat boatWithAlice(TestContext t, TestPlayer alice) {
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        alice.interact(boat);
        t.assertTrue(alice.player().getVehicle() == boat, "alice should be in the boat, but rides "
                + alice.player().getVehicle());
        t.tick(2);
        return boat;
    }

    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aDrivenBoatCrossingABlockIsTheDriversMove(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = boatWithAlice(t, alice);
        List<EntityMoveEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMoveEvent e) {
                if (e.getEntity() == boat && !(e instanceof PlayerMoveEvent)) events.add(e);
            }
        });
        BlockPos from = boat.blockPosition();
        boat.setPos(boat.getX() + 3, boat.getY(), boat.getZ());
        t.tick();
        t.assertEquals(1, events.size(), "the EntityMoveEvents for the boat crossing into another block");
        EntityMoveEvent e = events.get(0);
        assertPlayer(t, e.getActor(), alice, "The boat's move");
        t.assertEquals(from, e.getFromBlock(), "the block it left");
        t.assertEquals(from.east(3), e.getToBlock(), "the block it entered");
        t.assertEquals(e.getToBlock(), BlockPos.containing(e.getTo()), "getTo is inside getToBlock");
    }

    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aCancelledVehicleMovePutsItBack(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = boatWithAlice(t, alice);
        BlockPos from = boat.blockPosition();
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMoveEvent e) {
                if (e.getEntity() != boat || e instanceof PlayerMoveEvent) return;
                asked[0]++;
                e.setCancelled(true);
            }
        });
        boat.setPos(boat.getX() + 3, boat.getY(), boat.getZ());
        t.tick(2);
        t.assertEquals(1, asked[0], "the EntityMoveEvents for the boat");
        t.assertEquals(from, boat.blockPosition(), "the boat's block after a cancelled move");
        t.assertTrue(alice.player().getVehicle() == boat, "alice should still be in the boat");
    }
}
