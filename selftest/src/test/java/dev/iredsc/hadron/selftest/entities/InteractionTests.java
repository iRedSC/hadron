package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.entities.Fixtures.near;
import static dev.iredsc.hadron.selftest.entities.Fixtures.reach;
import static dev.iredsc.hadron.selftest.entities.Fixtures.ready;
import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;
import static dev.iredsc.hadron.selftest.entities.Fixtures.spawnWithAi;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityBreedEvent;
import dev.iredsc.hadron.api.event.entity.EntityDismountEvent;
import dev.iredsc.hadron.api.event.entity.EntityMountEvent;
import dev.iredsc.hadron.api.event.entity.EntityTameEvent;
import dev.iredsc.hadron.api.event.entity.EntityUseEvent;
import dev.iredsc.hadron.api.event.entity.ExperiencePickupEvent;
import dev.iredsc.hadron.api.event.entity.HangingBreakEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Right-clicking, riding, taming, breeding, picking up experience, and breaking an item frame. */
public final class InteractionTests {
    @HadronTest
    @Covers(EntityUseEvent.class)
    void milkingACowIsThePlayersUse(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.BUCKET));
        List<EntityUseEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityUseEvent e) {
                if (e.getEntity() == cow) events.add(e);
            }
        });
        String before = reach(t, alice, cow);
        alice.interact(cow);
        t.assertTrue(events.size() == 1, "one click should fire one EntityUseEvent, but fired " + events.size()
                + "; before the click " + before + ", after " + reach(t, alice, cow));
        EntityUseEvent e = events.get(0);
        assertPlayer(t, e.getActor(), alice, "Milking the cow");
        t.assertEquals(Optional.of(alice.player()), e.getPlayer(), "the player");
        t.assertEquals(InteractionHand.MAIN_HAND, e.getHand(), "the hand");
        t.assertTrue(e.getItem().is(Items.BUCKET), "the item used should be the bucket, but was " + e.getItem());
        t.assertHolding(alice, Items.MILK_BUCKET, 1);
    }

    @HadronTest
    @Covers(EntityUseEvent.class)
    void aCancelledUseLeavesTheBucketEmpty(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.BUCKET));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityUseEvent e) {
                if (e.getEntity() != cow) return;
                asked[0]++;
                e.setCancelled(true);
            }
        });
        alice.interact(cow);
        t.assertEquals(1, asked[0], "the number of EntityUseEvents for one click");
        t.assertHolding(alice, Items.BUCKET, 1);
        t.assertInventory(alice, Items.MILK_BUCKET, 0);
    }

    @HadronTest
    @Covers(EntityMountEvent.class)
    void boardingABoatIsThePlayersMount(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        List<EntityMountEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMountEvent e) {
                events.add(e);
            }
        });
        String before = reach(t, alice, boat);
        alice.interact(boat);
        t.assertTrue(events.size() == 1, "one click should fire one EntityMountEvent, but fired " + events.size()
                + "; before the click " + before + ", after " + reach(t, alice, boat));
        t.assertEquals(alice.player(), events.get(0).getEntity(), "the rider");
        t.assertEquals(boat, events.get(0).getMount(), "the mount");
        assertPlayer(t, events.get(0).getActor(), alice, "Boarding the boat");
        t.assertEquals(boat, alice.player().getVehicle(), "alice's vehicle");
    }

    @HadronTest
    @Covers(EntityMountEvent.class)
    void aCancelledMountLeavesThePlayerOnFoot(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMountEvent e) {
                asked[0]++;
                e.setCancelled(true);
            }
        });
        alice.interact(boat);
        t.tick();
        t.assertEquals(1, asked[0], "the number of EntityMountEvents for one click");
        t.assertFalse(alice.player().isPassenger(), "alice should not be riding, but rides "
                + alice.player().getVehicle());
        t.assertTrue(boat.getPassengers().isEmpty(), "the boat should have no passengers");
    }

    /** What a client sends while riding with the sneak key down; the harness has no sneak action. */
    private static void holdSneakWhileRiding(TestPlayer player) {
        player.player().connection.handlePlayerInput(new ServerboundPlayerInputPacket(0, 0, false, true));
    }

    @HadronTest
    @Covers(EntityDismountEvent.class)
    void sneakingOutOfABoatIsThePlayersDismount(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        String before = reach(t, alice, boat);
        alice.interact(boat);
        t.assertTrue(alice.player().getVehicle() == boat, "alice should be in the boat before sneaking, but rides "
                + alice.player().getVehicle() + "; before the click " + before + ", after " + reach(t, alice, boat));
        List<EntityDismountEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDismountEvent e) {
                events.add(e);
            }
        });
        holdSneakWhileRiding(alice);
        t.await("alice to get off", () -> !alice.player().isPassenger(), 10);
        t.assertEquals(1, events.size(), "the number of EntityDismountEvents");
        t.assertEquals(boat, events.get(0).getDismounted(), "what she got off");
        t.assertFalse(events.get(0).isForced(), "a sneak is not a forced dismount");
        assertPlayer(t, events.get(0).getActor(), alice, "Getting off the boat");
    }

    @HadronTest
    @Covers(EntityDismountEvent.class)
    void aCancelledDismountKeepsThePlayerRiding(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        alice.interact(boat);
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDismountEvent e) {
                if (e.isForced()) return; // the test's end takes her off
                asked[0]++;
                e.setCancelled(true);
            }
        });
        holdSneakWhileRiding(alice);
        t.tick(5);
        t.assertTrue(asked[0] > 0, "sneaking should have asked to dismount");
        t.assertEquals(boat, alice.player().getVehicle(), "alice's vehicle");
    }

    /** Feeds bones until a tame is asked about, or the bones run out; returns how many were asked. */
    private static int feedBones(TestContext t, TestPlayer alice, Wolf wolf, int[] asked) {
        alice.hold(new ItemStack(Items.BONE, 40));
        for (int i = 0; i < 40 && asked[0] == 0 && !wolf.isTame(); i++) alice.interact(wolf);
        return asked[0];
    }

    @HadronTest
    @Covers(EntityTameEvent.class)
    void tamingAWolfIsThePlayersAndTheyOwnIt(TestContext t) {
        TestPlayer alice = t.player("alice");
        Wolf wolf = spawn(t, EntityType.WOLF, t.pos(2, 0, 0));
        List<EntityTameEvent> events = new ArrayList<>();
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTameEvent e) {
                if (e.getEntity() != wolf) return;
                events.add(e);
                asked[0]++;
            }
        });
        feedBones(t, alice, wolf, asked);
        t.assertEquals(1, events.size(), "the number of EntityTameEvents (a third of bones tame, so 40 should do)");
        assertPlayer(t, events.get(0).getActor(), alice, "Taming the wolf");
        t.assertEquals(alice.player(), events.get(0).getOwner(), "the new owner");
        t.assertTrue(wolf.isTame() && wolf.isOwnedBy(alice.player()), "the wolf should be alice's");
    }

    @HadronTest
    @Covers(EntityTameEvent.class)
    void aCancelledTameLeavesTheWolfWild(TestContext t) {
        TestPlayer alice = t.player("alice");
        Wolf wolf = spawn(t, EntityType.WOLF, t.pos(2, 0, 0));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTameEvent e) {
                if (e.getEntity() != wolf) return;
                asked[0]++;
                e.setCancelled(true);
            }
        });
        feedBones(t, alice, wolf, asked);
        t.assertEquals(1, asked[0], "the number of EntityTameEvents");
        t.assertFalse(wolf.isTame(), "the wolf should stay wild");
        t.assertEquals(null, wolf.getOwnerUUID(), "the wolf's owner");
    }

    /** Two cows in a glass pen east of alice, fed wheat by her. */
    private static void feedTwoCows(TestContext t, TestPlayer alice) {
        for (int x = 1; x <= 5; x++) {
            for (int z = -2; z <= 2; z++) {
                if (x != 1 && x != 5 && z != -2 && z != 2) continue;
                t.setBlock(t.pos(x, 0, z), Blocks.GLASS);
                t.setBlock(t.pos(x, 1, z), Blocks.GLASS);
            }
        }
        Cow a = spawnWithAi(t, EntityType.COW, t.pos(2, 0, 0));
        Cow b = spawnWithAi(t, EntityType.COW, t.pos(3, 0, 0));
        alice.hold(new ItemStack(Items.WHEAT, 2));
        alice.interact(a);
        alice.interact(b);
        t.assertTrue(a.isInLove() && b.isInLove(), "both cows should be in love after the wheat");
    }

    @HadronTest(timeout = 900)
    @Covers(EntityBreedEvent.class)
    void breedingCowsIsTheFeedersAndMakesACalf(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<EntityBreedEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityBreedEvent e) {
                events.add(e);
            }
        });
        feedTwoCows(t, alice);
        t.await("the cows to breed", () -> !events.isEmpty(), 400);
        EntityBreedEvent e = events.get(0);
        assertPlayer(t, e.getActor(), alice, "Breeding the cows");
        t.assertEquals(Optional.of(alice.player()), e.getBreeder(), "the breeder");
        t.assertTrue(e.getEntity() instanceof Cow, "the child should be a cow, but was " + e.getEntity());
        t.tick();
        t.assertEquals(3, near(t, Cow.class).size(), "the number of cows after breeding");
    }

    @HadronTest(timeout = 900)
    @Covers(EntityBreedEvent.class)
    void aCancelledBreedMakesNoCalf(TestContext t) {
        TestPlayer alice = t.player("alice");
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityBreedEvent e) {
                asked[0]++;
                e.setCancelled(true);
            }
        });
        feedTwoCows(t, alice);
        t.await("the cows to try to breed", () -> asked[0] > 0, 400);
        t.tick(5);
        t.assertEquals(2, near(t, Cow.class).size(), "the number of cows");
    }

    @HadronTest
    @Covers(ExperiencePickupEvent.class)
    void pickingUpExperienceIsThePlayers(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<ExperiencePickupEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ExperiencePickupEvent e) {
                events.add(e);
            }
        });
        ready(t);
        var at = alice.player().position();
        t.level().addFreshEntity(new ExperienceOrb(t.level(), at.x, at.y + 0.5, at.z, 7));
        t.await("alice to pick up the orb", () -> !events.isEmpty(), 40);
        assertPlayer(t, events.get(0).getActor(), alice, "Picking up experience");
        t.assertEquals(7, events.get(0).getAmount(), "the orb's amount");
        t.assertEquals(7, alice.player().totalExperience, "alice's total experience");
    }

    @HadronTest
    @Covers(ExperiencePickupEvent.class)
    void aCancelledPickupLeavesTheOrbAndGivesNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ExperiencePickupEvent e) {
                asked[0]++;
                e.setCancelled(true);
            }
        });
        ready(t);
        var at = alice.player().position();
        ExperienceOrb orb = new ExperienceOrb(t.level(), at.x, at.y + 0.5, at.z, 7);
        t.level().addFreshEntity(orb);
        t.await("alice to touch the orb", () -> asked[0] > 0, 40);
        t.tick(5);
        t.assertEquals(0, alice.player().totalExperience, "alice's total experience");
        t.assertFalse(orb.isRemoved(), "the orb should still be in the world");
    }

    private static ItemFrame hangFrame(TestContext t) {
        ready(t);
        BlockPos wall = t.pos(2, 0, 0);
        t.setBlock(wall, Blocks.STONE);
        ItemFrame frame = new ItemFrame(t.level(), wall.west(), Direction.WEST);
        t.assertTrue(frame.survives(), "the item frame should hang on the stone");
        t.level().addFreshEntity(frame);
        return frame;
    }

    @HadronTest
    @Covers(HangingBreakEvent.class)
    void punchingAnItemFrameIsThePlayersBreak(TestContext t) {
        TestPlayer alice = t.player("alice");
        ItemFrame frame = hangFrame(t);
        List<HangingBreakEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(HangingBreakEvent e) {
                events.add(e);
            }
        });
        alice.attack(frame);
        t.assertEquals(1, events.size(), "the number of HangingBreakEvents");
        t.assertEquals(HangingBreakEvent.RemoveCause.ENTITY, events.get(0).getCause(), "the cause");
        assertPlayer(t, events.get(0).getActor(), alice, "Punching the item frame");
        t.assertTrue(frame.isRemoved(), "the item frame should be broken");
    }

    @HadronTest
    @Covers(HangingBreakEvent.class)
    void aCancelledHangingBreakKeepsTheFrameAndDropsNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        ItemFrame frame = hangFrame(t);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(HangingBreakEvent e) {
                e.setCancelled(true);
            }
        });
        alice.attack(frame);
        t.tick();
        t.assertFalse(frame.isRemoved(), "the item frame should still hang");
        t.assertEquals(0, near(t, ItemEntity.class).size(), "the number of items dropped");
    }
}
