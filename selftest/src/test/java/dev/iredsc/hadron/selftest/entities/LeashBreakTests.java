package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityLeashBreakEvent;
import dev.iredsc.hadron.api.event.entity.EntityLeashBreakEvent.Reason;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;

/** Leads coming off with nobody choosing it: one after-the-fact event each, with the reason. */
public final class LeashBreakTests {
    private static List<EntityLeashBreakEvent> listen(TestContext t) {
        List<EntityLeashBreakEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashBreakEvent e) {
                heard.add(e);
            }
        });
        return heard;
    }

    private static EntityLeashBreakEvent one(TestContext t, List<EntityLeashBreakEvent> heard) {
        t.assertEquals(1, heard.size(), "the number of EntityLeashBreakEvents fired (" + heard + ")");
        return heard.get(0);
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void aLeadStretchedTooFarBreaksForDistance(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        cow.setLeashedTo(alice.player(), true);
        List<EntityLeashBreakEvent> heard = listen(t);
        BlockPos far = t.pos(16, 0, 0);
        alice.teleport(far.getX() + 0.5, far.getY(), far.getZ() + 0.5);
        t.await("the lead to break", () -> !heard.isEmpty(), 40);
        EntityLeashBreakEvent e = one(t, heard);
        t.assertEquals(Reason.DISTANCE, e.getReason(), "the reason");
        t.assertEquals(cow, e.getEntity(), "the entity");
        t.assertEquals(alice.player(), e.getHolder(), "the former holder");
        t.assertTrue(e.getActor() instanceof Actor.Nature, "a lead snapping should be Actor.Nature, but was "
                + e.getActor());
        t.assertFalse(cow.isLeashed(), "the cow should be free");
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void aBrokenKnotFreesItsMobsAsHolderGone(TestContext t) {
        t.player("alice");
        BlockPos fence = t.pos(2, 0, 0);
        t.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState());
        LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(t.level(), fence);
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 2));
        cow.setLeashedTo(knot, true);
        t.tick();
        List<EntityLeashBreakEvent> heard = listen(t);
        knot.discard();
        t.await("the lead to break", () -> !heard.isEmpty(), 20);
        EntityLeashBreakEvent e = one(t, heard);
        t.assertEquals(Reason.HOLDER_GONE, e.getReason(), "the reason");
        t.assertEquals(knot, e.getHolder(), "the former holder");
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void mountingABoatBreaksTheLeadAsMounted(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 2));
        cow.setLeashedTo(alice.player(), true);
        List<EntityLeashBreakEvent> heard = listen(t);
        t.assertTrue(cow.startRiding(boat), "the cow should board the boat");
        t.assertEquals(Reason.MOUNTED, one(t, heard).getReason(), "the reason");
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void aDestroyedBoatBreaksItsLeadAsEntityGone(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        boat.setLeashedTo(alice.player(), true);
        List<EntityLeashBreakEvent> heard = listen(t);
        boat.discard();
        EntityLeashBreakEvent e = one(t, heard);
        t.assertEquals(Reason.ENTITY_GONE, e.getReason(), "the reason");
        t.assertEquals(boat, e.getEntity(), "the entity");
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void takingALeadOffIsAnUnleashNotABreak(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        cow.setLeashedTo(alice.player(), true);
        List<EntityLeashBreakEvent> heard = listen(t);
        alice.interact(cow);
        t.tick();
        t.assertFalse(cow.isLeashed(), "the click should take the lead off");
        t.assertTrue(heard.isEmpty(), "a chosen unleash should fire no EntityLeashBreakEvent, but fired " + heard);
    }
}
