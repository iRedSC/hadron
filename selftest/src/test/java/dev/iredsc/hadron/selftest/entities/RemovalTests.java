package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.entities.Fixtures.reach;
import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityCaptureEvent;
import dev.iredsc.hadron.api.event.entity.EntityDeathEvent;
import dev.iredsc.hadron.api.event.entity.EntityRemoveEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.SideEffects;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cod;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Entities leaving the world without dying: scooped into a bucket, or removed by /kill. */
public final class RemovalTests {
    @HadronTest
    @Covers(EntityCaptureEvent.class)
    void scoopingACodIsThePlayersCapture(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cod cod = spawn(t, EntityType.COD, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.WATER_BUCKET));
        List<EntityCaptureEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityRemoveEvent e) {
                if (e.getEntity() == cod) events.add((EntityCaptureEvent) e);
            }
        });
        String before = reach(t, alice, cod);
        alice.interact(cod);
        t.assertTrue(events.size() == 1, "one scoop should fire one EntityCaptureEvent, heard by an EntityRemoveEvent "
                + "listener, but fired " + events.size() + "; before the click " + before);
        EntityCaptureEvent e = events.get(0);
        assertPlayer(t, e.getActor(), alice, "Scooping the cod");
        t.assertEquals(EntityRemoveEvent.Cause.CAPTURED, e.getCause(), "the cause");
        t.assertTrue(e.getStorage() instanceof EntityCaptureEvent.Storage.Item item && item.stack().is(Items.COD_BUCKET),
                "the cod should go into a bucket of cod, but went into " + e.getStorage());
        t.assertTrue(cod.isRemoved(), "the cod should be out of the world");
        t.assertHolding(alice, Items.COD_BUCKET, 1);
    }

    @HadronTest
    @Covers(EntityCaptureEvent.class)
    void aCancelledCaptureLeavesTheCodAndTheWaterBucket(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cod cod = spawn(t, EntityType.COD, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.WATER_BUCKET));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityCaptureEvent e) {
                if (e.getEntity() != cod) return;
                asked[0]++;
                e.setCancelled(true);
            }
        });
        alice.clearReceived();
        SideEffects effects = t.watchSideEffects();
        alice.interact(cod);
        t.assertEquals(1, asked[0], "the number of EntityCaptureEvents for one click");
        t.assertFalse(cod.isRemoved(), "the cod should still be in the world");
        t.assertHolding(alice, Items.WATER_BUCKET, 1);
        t.assertInventory(alice, Items.COD_BUCKET, 0);
        // A real client scoops the cod before the server answers; it must be shown the cod again.
        t.assertTrue(alice.received(ClientboundAddEntityPacket.class).stream().anyMatch(p -> p.getId() == cod.getId()),
                "the server should send the cod to alice again, since her client already removed it");
        effects.assertNone("a refused capture");
    }

    @HadronTest
    @Covers(EntityRemoveEvent.class)
    void killingABoatIsARemovalByTheOperator(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.setOp(true);
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        List<EntityRemoveEvent> events = removals(t, boat);
        alice.command("kill " + boat.getStringUUID());
        t.assertEquals(1, events.size(), "the number of EntityRemoveEvents for /kill on one boat");
        assertPlayer(t, events.get(0).getActor(), alice, "Killing the boat");
        t.assertEquals(EntityRemoveEvent.Cause.KILLED, events.get(0).getCause(), "the cause");
        t.assertTrue(boat.isRemoved(), "the boat should be gone");
    }

    @HadronTest
    @Covers(EntityRemoveEvent.class)
    void aRefusedKillLeavesTheBoatAndCountsNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.setOp(true);
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityRemoveEvent e) {
                if (e.getEntity() == boat) e.setCancelled(true);
            }
        });
        alice.command("kill " + boat.getStringUUID());
        t.assertFalse(boat.isRemoved(), "the boat should still be in the world");
        t.assertTrue(alice.chat().contains("Killed 0 entities"),
                "/kill's reply should count only what it killed, but alice saw " + alice.chat());
    }

    @HadronTest
    @Covers(EntityRemoveEvent.class)
    void killingAnArmorStandIsARemoval(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.setOp(true);
        ArmorStand stand = spawn(t, EntityType.ARMOR_STAND, t.pos(2, 0, 0));
        List<EntityRemoveEvent> events = removals(t, stand);
        alice.command("kill " + stand.getStringUUID());
        t.assertEquals(1, events.size(), "the number of EntityRemoveEvents for /kill on an armor stand, which "
                + "Minecraft removes without hurting");
        t.assertTrue(stand.isRemoved(), "the armor stand should be gone");
    }

    @HadronTest
    @Covers(EntityRemoveEvent.class)
    void aRefusedKillLeavesTheEnderDragon(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.setOp(true);
        EnderDragon dragon = spawn(t, EntityType.ENDER_DRAGON, t.pos(4, 2, 0));
        List<EntityRemoveEvent> events = removals(t, dragon);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityRemoveEvent e) {
                if (e.getEntity() == dragon) e.setCancelled(true);
            }
        });
        alice.command("kill " + dragon.getStringUUID());
        t.assertEquals(1, events.size(), "the number of EntityRemoveEvents for /kill on the ender dragon, which "
                + "removes itself without dying");
        t.assertEquals(EntityRemoveEvent.Cause.KILLED, events.get(0).getCause(), "the cause");
        t.assertFalse(dragon.isRemoved(), "the refused dragon should still be in the world");
    }

    @HadronTest
    @Covers(EntityRemoveEvent.class)
    void killingAPigIsADeathNotARemoval(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.setOp(true);
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        List<EntityRemoveEvent> removals = removals(t, pig);
        int[] deaths = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDeathEvent e) {
                if (e.getEntity() == pig) deaths[0]++;
            }
        });
        alice.command("kill " + pig.getStringUUID());
        t.assertEquals(1, deaths[0], "the number of EntityDeathEvents for /kill on a pig");
        t.assertEquals(0, removals.size(), "a pig killed by /kill dies, so it fires no EntityRemoveEvent");
    }

    @HadronTest
    @Covers(EntityRemoveEvent.class)
    void aRemovalCantClaimToBeACapture(TestContext t) {
        t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        try {
            new EntityRemoveEvent(boat, EntityRemoveEvent.Cause.CAPTURED, Actor.Unknown.INSTANCE);
            t.fail("EntityRemoveEvent with Cause.CAPTURED should be refused: a capture is an EntityCaptureEvent");
        } catch (IllegalArgumentException expected) {
            t.assertTrue(expected.getMessage().contains("EntityCaptureEvent"),
                    "the refusal should name the event to fire instead, but said: " + expected.getMessage());
        }
    }

    private static List<EntityRemoveEvent> removals(TestContext t, Entity entity) {
        List<EntityRemoveEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityRemoveEvent e) {
                if (e.getEntity() == entity) events.add(e);
            }
        });
        return events;
    }
}
