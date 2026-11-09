package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityLeashBreakEvent;
import dev.iredsc.hadron.api.event.entity.EntityLeashBreakEvent.Reason;
import dev.iredsc.hadron.api.event.entity.EntityUnleashEvent;
import dev.iredsc.hadron.selftest.platform.ModBus;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Leads coming off with nobody choosing it: one after-the-fact event each, with the reason. */
public final class LeashBreakTests {
    /** Every break heard, and any that fired while the lead was still on (it must already be off). */
    private record Heard(List<EntityLeashBreakEvent> events, List<String> early) {
        boolean isEmpty() {
            return events.isEmpty();
        }
    }

    private static Heard listen(TestContext t) {
        Heard heard = new Heard(new ArrayList<>(), new ArrayList<>());
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashBreakEvent e) {
                // A destroyed entity keeps its leash data in vanilla; being gone is what takes the lead off.
                if (e.getEntity() instanceof Leashable l && l.isLeashed() && !e.getEntity().isRemoved()) {
                    heard.early().add(e.getEntity() + " still held by " + l.getLeashHolder());
                }
                heard.events().add(e);
            }
        });
        return heard;
    }

    private static EntityLeashBreakEvent one(TestContext t, Heard heard) {
        t.assertEquals(List.of(), heard.early(), "breaks fired before the lead came off");
        t.assertEquals(1, heard.events().size(), "the number of EntityLeashBreakEvents fired (" + heard.events() + ")");
        return heard.events().get(0);
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void aLeadStretchedTooFarBreaksForDistance(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        cow.setLeashedTo(alice.player(), true);
        Heard heard = listen(t);
        BlockPos far = t.pos(16, 0, 0);
        alice.teleport(far.getX() + 0.5, far.getY(), far.getZ() + 0.5);
        t.await("the lead to break", () -> !heard.isEmpty(), 40);
        EntityLeashBreakEvent e = one(t, heard);
        t.assertEquals(Reason.DISTANCE, e.getReason(), "the reason");
        t.assertEquals(cow, e.getEntity(), "the entity");
        t.assertEquals(Optional.of(alice.player()), e.getHolder(), "the former holder");
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
        Heard heard = listen(t);
        knot.discard();
        t.await("the lead to break", () -> !heard.isEmpty(), 20);
        EntityLeashBreakEvent e = one(t, heard);
        t.assertEquals(Reason.HOLDER_GONE, e.getReason(), "the reason");
        t.assertEquals(Optional.of(knot), e.getHolder(), "the former holder");
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void mountingABoatBreaksTheLeadAsMounted(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 2));
        cow.setLeashedTo(alice.player(), true);
        Heard heard = listen(t);
        t.assertTrue(cow.startRiding(boat), "the cow should board the boat");
        t.assertEquals(Reason.MOUNTED, one(t, heard).getReason(), "the reason");
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void aDestroyedBoatBreaksItsLeadAsEntityGone(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        boat.setLeashedTo(alice.player(), true);
        Heard heard = listen(t);
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
        Heard heard = listen(t);
        alice.interact(cow);
        t.tick();
        t.assertFalse(cow.isLeashed(), "the click should take the lead off");
        t.assertTrue(heard.isEmpty(), "a chosen unleash should fire no EntityLeashBreakEvent, but fired " + heard.events());
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void aBoatDestroyedFarFromItsHolderIsGoneNotStretched(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        boat.setLeashedTo(alice.player(), true);
        BlockPos far = t.pos(16, 0, 0);
        alice.player().teleportTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5); // no tick between, so no snap
        Heard heard = listen(t);
        boat.discard();
        t.assertEquals(Reason.ENTITY_GONE, one(t, heard).getReason(), "the reason");
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void aSavedHolderThatNeverLoadsBreaksAsNotFound(TestContext t) {
        t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        CompoundTag saved = new CompoundTag();
        CompoundTag holder = new CompoundTag();
        holder.putUUID("UUID", new UUID(0x4ad0, 0x1eaf)); // nobody in the world
        saved.put("leash", holder);
        cow.setLeashData(cow.readLeashData(saved));
        cow.tickCount = 101; // vanilla waits 100 ticks for a saved holder to load
        Heard heard = listen(t);
        t.await("the cow to give up on its holder", () -> !heard.isEmpty(), 20);
        EntityLeashBreakEvent e = one(t, heard);
        t.assertEquals(Reason.HOLDER_NOT_FOUND, e.getReason(), "the reason");
        t.assertTrue(e.getHolder().isEmpty(), "a holder never loaded can't be named, but got " + e.getHolder());
        t.assertTrue(cow.getLeashData() == null, "the cow's leash data should be cleared");
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void aLeashedMobDiscardedWithoutDroppingItsLeadIsGone(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        cow.setLeashedTo(alice.player(), true);
        Heard heard = listen(t);
        cow.discard(); // as a conversion does: no dropLeash on the way out
        Leashable.tickLeash(cow); // a conversion ticks the removed mob once more, which drops the lead
        t.tick();
        EntityLeashBreakEvent e = one(t, heard);
        t.assertEquals(Reason.ENTITY_GONE, e.getReason(), "the reason");
        t.assertEquals(Optional.of(alice.player()), e.getHolder(), "the former holder");
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void aLeadPutBackOnDuringTheDropStillReportsTheBreak(TestContext t) throws Exception {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        cow.setLeashedTo(alice.player(), true);
        BlockPos far = t.pos(16, 0, 0);
        alice.player().teleportTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5); // past the ten blocks
        Heard heard = listen(t);
        // A mod that hands the cow to bob as the snapped lead drops.
        try (AutoCloseable mod = ModBus.listen("net.neoforged.neoforge.event.entity.EntityJoinLevelEvent", event -> {
            Object joined;
            try {
                joined = event.getClass().getMethod("getEntity").invoke(event);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            if (joined instanceof ItemEntity item && item.getItem().is(Items.LEAD)) {
                cow.setLeashedTo(bob.player(), true);
            }
        })) {
            Leashable.tickLeash(cow);
        }
        t.assertEquals(bob.player(), cow.getLeashHolder(), "the cow's holder after the mod put a new lead on");
        t.assertEquals(1, heard.events().size(), "the number of EntityLeashBreakEvents fired (" + heard.events() + ")");
        EntityLeashBreakEvent e = heard.events().get(0); // fired with bob's new lead already on, so not one()
        t.assertEquals(Reason.DISTANCE, e.getReason(), "the reason");
        t.assertEquals(Optional.of(alice.player()), e.getHolder(), "the former holder");
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void aLeadPutOnDuringAChosenUnleashStillBreaks(TestContext t) throws Exception {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        cow.setLeashedTo(alice.player(), true);
        TestPlayer bob = t.player("bob");
        BlockPos far = t.pos(16, 0, 0);
        bob.player().teleportTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5); // past the ten blocks
        Heard heard = listen(t);
        boolean[] handed = {false};
        // A mod that hands the cow to bob as alice's lead drops, and ticks it: bob's lead snaps for distance.
        try (AutoCloseable mod = ModBus.listen("net.neoforged.neoforge.event.entity.EntityJoinLevelEvent", event -> {
            Object joined;
            try {
                joined = event.getClass().getMethod("getEntity").invoke(event);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            if (joined instanceof ItemEntity item && item.getItem().is(Items.LEAD) && !handed[0]) {
                handed[0] = true;
                cow.setLeashedTo(bob.player(), true);
                Leashable.tickLeash(cow);
            }
        })) {
            alice.interact(cow);
        }
        EntityLeashBreakEvent e = one(t, heard);
        t.assertEquals(Reason.DISTANCE, e.getReason(), "the reason bob's lead came off");
        t.assertEquals(Optional.of(bob.player()), e.getHolder(), "the former holder");
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void aPortalHandsTheLeadToTheCopyAndReportsNothingYet(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        cow.setLeashedTo(alice.player(), true);
        ServerLevel nether = t.server().getLevel(Level.NETHER);
        if (nether == null) throw new AssertionError("the test server has no nether");
        Heard heard = listen(t);
        Entity copy = cow.changeDimension(new DimensionTransition(nether, new Vec3(0.5, 64, 0.5), Vec3.ZERO, 0, 0,
                DimensionTransition.DO_NOTHING));
        t.tick();
        t.assertTrue(copy instanceof Leashable l && l.getLeashData() != null,
                "the copy in the nether should carry the lead on, waiting for its holder, but has " + copy);
        t.assertEquals(List.of(), heard.events(), "breaks reported for the portal itself");
        if (copy != null) copy.discard(); // the test cleans up the overworld only
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void aChosenUnleashIsNeverABreakWhereverTheMobEndsUp(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        cow.setLeashedTo(alice.player(), true);
        BlockPos far = t.pos(16, 0, 0);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityUnleashEvent e) {
                e.getEntity().teleportTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5); // past the ten blocks
            }
        });
        Heard heard = listen(t);
        alice.interact(cow);
        t.tick();
        t.assertFalse(cow.isLeashed(), "the click should take the lead off");
        t.assertEquals(List.of(), heard.events(), "breaks reported for a chosen unleash");
    }

    @HadronTest
    @Covers(EntityLeashBreakEvent.class)
    void aMobDestroyedWhileWaitingForItsSavedHolderIsGoneWithNoHolder(TestContext t) {
        t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        CompoundTag saved = new CompoundTag();
        CompoundTag holder = new CompoundTag();
        holder.putUUID("UUID", new UUID(0x4ad0, 0x1eaf)); // nobody in the world
        saved.put("leash", holder);
        cow.setLeashData(cow.readLeashData(saved));
        Heard heard = listen(t);
        cow.discard();
        cow.tickCount = 101; // past vanilla's wait for a saved holder
        Leashable.tickLeash(cow); // a conversion ticks the removed mob once more, which gives up on the holder
        t.tick();
        EntityLeashBreakEvent e = one(t, heard);
        t.assertEquals(Reason.ENTITY_GONE, e.getReason(), "the reason");
        t.assertTrue(e.getHolder().isEmpty(), "a holder never loaded can't be named, but got " + e.getHolder());
    }
}
