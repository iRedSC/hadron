package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Place;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * EntityMoveEvent: a vehicle a player drives crossing into another block, or carrying a rider into one, is the
 * driver's move, names every rider where the move leaves them, and can be undone.
 */
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

    private static List<Entity> carried(EntityMoveEvent e) {
        return switch (e.getCarried()) {
            case Carried.Known k -> k.entities();
            case Carried.Unknown u ->
                    throw new AssertionError("a vehicle's move always knows its riders, but said " + u);
        };
    }

    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aVehiclesMoveNamesEachRiderWhereTheMoveLeavesThem(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Boat boat = boatWithAlice(t, alice);
        bob.interact(boat);
        t.assertTrue(bob.player().getVehicle() == boat, "bob should be in the boat too");
        t.tick(2);
        List<List<Entity>> riders = new ArrayList<>();
        List<BlockPos> bobAt = new ArrayList<>();
        List<Boolean> placed = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMoveEvent e) {
                if (e.getEntity() != boat) return;
                riders.add(carried(e));
                bobAt.add(bob.player().blockPosition());
                placed.add(e.getPlaces().contains(Place.of(bob.player())));
            }
        });
        boat.setPos(boat.getX() + 3, boat.getY(), boat.getZ());
        t.tick();
        t.assertEquals(1, riders.size(), "the EntityMoveEvents for the boat");
        t.assertEquals(List.of(alice.player(), bob.player()), riders.get(0), "the boat's riders, in their seats");
        t.assertTrue(placed.get(0), "bob's place should be one of the move's places");
        t.assertEquals(bob.player().blockPosition(), bobAt.get(0), "where the event said bob is, against where he "
                + "ended up");
    }

    /**
     * A boat turning in place swings its back seat across a block edge: the boat at x + 0.75 facing west seats bob at
     * x + 0.15; facing east, at x + 1.35, in the next block, which a region refuses him.
     */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aTurningBoatCarryingARiderIntoARefusedBlockIsPutBack(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        BlockPos at = t.pos(1, 0, 0);
        Boat boat = spawn(t, EntityType.BOAT, at);
        boat.moveTo(at.getX() + 0.75, at.getY(), at.getZ() + 0.5, -90f, 0f);
        alice.interact(boat);
        bob.interact(boat);
        t.assertTrue(alice.player().getVehicle() == boat && bob.player().getVehicle() == boat,
                "alice should drive the boat and bob sit behind her");
        t.tick(2);
        int regionStarts = at.getX() + 1;
        t.assertTrue(bob.player().getX() < regionStarts, "bob should start outside the region, but is at "
                + bob.player().position());
        List<EntityMoveEvent> refused = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMoveEvent e) {
                if (e.getEntity() != boat) return;
                // A region from regionStarts east, whose entry bob may not make: refuse any move that carries him in.
                boolean entering = carried(e).contains(bob.player()) && e.getPlaces().stream()
                        .anyMatch(p -> p.equals(Place.of(bob.player())) && p instanceof Place.Spot s
                                && s.box().minX() >= regionStarts);
                if (entering) {
                    refused.add(e);
                    e.setCancelled(true);
                }
            }
        });
        boat.setYRot(90f);
        t.tick();
        t.assertEquals(1, refused.size(), "the boat's moves refused for carrying bob into the region");
        t.assertEquals(refused.get(0).getFromBlock(), refused.get(0).getToBlock(), "the boat's own block, which it "
                + "never left");
        t.tick(2);
        t.assertTrue(bob.player().getX() < regionStarts, "bob should be kept outside the region, but is at "
                + bob.player().position());
        t.assertEquals(-90f, boat.getYRot(), "the boat's facing, put back");
        t.assertTrue(bob.player().getVehicle() == boat, "bob should still be in the boat");
    }

    /**
     * A camel sitting down lowers its riders by more than a block over two seconds; a region refusing bob the block
     * below his seat holds the camel where its sitting had got to before bob would cross, its clock put back each
     * tick, so bob never enters that block.
     */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aCamelSittingDownWithARiderOverARefusedBlockKeepsHimOut(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Camel camel = spawn(t, EntityType.CAMEL, t.pos(2, 0, 0));
        camel.setTamed(true);
        camel.equipSaddle(new ItemStack(Items.SADDLE), null);
        t.assertTrue(alice.player().startRiding(camel, true) && bob.player().startRiding(camel, true),
                "alice and bob should get on the camel");
        t.tick(2);
        t.assertTrue(camel.getControllingPassenger() == alice.player(), "alice should drive the camel");
        int seat = bob.player().blockPosition().getY();
        int[] refused = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMoveEvent e) {
                if (e.getEntity() != camel || !carried(e).contains(bob.player())) return;
                if (bob.player().blockPosition().getY() < seat) {
                    refused[0]++;
                    e.setCancelled(true);
                }
            }
        });
        camel.sitDown();
        int lowest = seat;
        for (int i = 0; i < 60; i++) {
            t.tick();
            lowest = Math.min(lowest, bob.player().blockPosition().getY());
        }
        t.assertTrue(refused[0] > 0, "the camel sitting down should have carried bob into the block below");
        t.assertEquals(seat, lowest, "the lowest block bob was in after a tick");
        t.assertTrue(camel.isInPoseTransition(), "the camel should be held partway down, its sitting clock put back");
    }
}
