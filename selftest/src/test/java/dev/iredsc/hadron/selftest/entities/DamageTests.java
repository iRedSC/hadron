package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.assertEntityFor;
import static dev.iredsc.hadron.selftest.entities.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.entities.Fixtures.horizontalSpeed;
import static dev.iredsc.hadron.selftest.entities.Fixtures.near;
import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.entity.EntityDeathEvent;
import dev.iredsc.hadron.api.event.entity.EntityKnockbackEvent;
import dev.iredsc.hadron.api.event.entity.EntityRemoveEvent;
import dev.iredsc.hadron.api.event.entity.ProjectileHitEvent;
import dev.iredsc.hadron.api.event.entity.VehicleDamageEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/** EntityDamageEvent, EntityKnockbackEvent, EntityDeathEvent and ProjectileHitEvent, and who a hit is remembered as. */
public final class DamageTests {
    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aPlayersHitIsThePlayersWithTheirWeapon(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.IRON_SWORD));
        List<EntityDamageEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDamageEvent e) {
                if (e.getEntity() == pig) events.add(e);
            }
        });
        t.tick(10);
        alice.attack(pig);
        t.assertEquals(1, events.size(), "the number of EntityDamageEvents for the pig");
        EntityDamageEvent e = events.get(0);
        assertPlayer(t, e.getActor(), alice, "A sword hit");
        t.assertEquals(Optional.of(alice.player()), e.getDamager(), "the damager");
        t.assertTrue(e.getWeapon().map(w -> w.is(Items.IRON_SWORD)).orElse(false),
                "the weapon should be the iron sword, but was " + e.getWeapon());
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aCancelledHitLeavesTheVictimUnhurtUnmovedAndTheSwordUnworn(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.IRON_SWORD));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDamageEvent e) {
                if (e.getEntity() == pig) e.setCancelled(true);
            }
        });
        t.tick(10);
        alice.attack(pig);
        t.assertEquals(pig.getMaxHealth(), pig.getHealth(), "the pig's health");
        t.assertEquals(0.0, horizontalSpeed(pig), "the pig's horizontal speed (knockback)");
        t.assertEquals(0, alice.player().getMainHandItem().getDamageValue(), "the sword's damage");
    }

    /**
     * Minecraft remembers a victim's last damage for 40 ticks and updates it only after the knockback, so a second
     * attacker's hit soon after the first must not be blamed on the first.
     */
    @HadronTest
    @Covers(EntityKnockbackEvent.class)
    void aSecondAttackersKnockbackIsTheirsNotTheFirsts(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        List<EntityKnockbackEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityKnockbackEvent e) {
                if (e.getEntity() == pig) events.add(e);
            }
        });
        t.tick(10);
        alice.attack(pig);
        t.tick(15); // past the pig's invulnerability, well within the 40 ticks its last damage is remembered
        pig.moveTo(t.pos(2, 0, 0).getX() + 0.5, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5);
        pig.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bob.attack(pig);
        t.assertEquals(2, events.size(), "the number of EntityKnockbackEvents for two hits");
        assertPlayer(t, events.get(0).getActor(), alice, "Alice's hit's knockback");
        assertPlayer(t, events.get(1).getActor(), bob, "Bob's hit's knockback");
    }

    @HadronTest
    @Covers(EntityKnockbackEvent.class)
    void aHitsKnockbackIsTheAttackersAndPushesTheVictim(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        List<EntityKnockbackEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityKnockbackEvent e) {
                if (e.getEntity() == pig) events.add(e);
            }
        });
        t.tick(10);
        alice.attack(pig);
        t.assertEquals(1, events.size(), "the number of EntityKnockbackEvents for the pig");
        assertPlayer(t, events.get(0).getActor(), alice, "A hit's knockback");
        t.assertTrue(horizontalSpeed(pig) > 0.1, "the pig should be knocked back, but its horizontal speed is "
                + horizontalSpeed(pig));
    }

    @HadronTest
    @Covers(EntityKnockbackEvent.class)
    void zeroKnockbackLeavesTheVictimInPlaceButHurt(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityKnockbackEvent e) {
                if (e.getEntity() == pig) e.setStrength(0);
            }
        });
        t.tick(10);
        alice.attack(pig);
        t.assertEquals(0.0, horizontalSpeed(pig), "the pig's horizontal speed");
        t.assertEquals(pig.getMaxHealth() - 1, pig.getHealth(), "the pig's health after a fist");
    }

    @HadronTest
    @Covers(EntityKnockbackEvent.class)
    void strongerKnockbackPushesFurther(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        Pig other = spawn(t, EntityType.PIG, t.pos(-2, 0, 0));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityKnockbackEvent e) {
                if (e.getEntity() == pig) e.setStrength(e.getStrength() * 4);
            }
        });
        t.tick(10);
        // Both speeds are read right after their own hit, before any tick decays them, so the only difference between
        // the two pigs is the listener's multiplier.
        alice.attack(other);
        double ordinary = horizontalSpeed(other);
        t.tick(10);
        alice.attack(pig);
        double stronger = horizontalSpeed(pig);
        t.assertTrue(ordinary > 0.1, "an ordinary hit should knock the other pig back, but its horizontal speed is "
                + ordinary);
        t.assertTrue(stronger > ordinary * 2, "four times the knockback should push the pig much faster than an "
                + "ordinary hit measured the same way, but its speed is " + stronger + " against " + ordinary);
    }

    @HadronTest
    @Covers(EntityDeathEvent.class)
    void changedDropsAndExperienceAreWhatTheMobLeaves(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        pig.setHealth(1);
        List<EntityDeathEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDeathEvent e) {
                if (e.getEntity() != pig) return;
                events.add(e);
                e.getDrops().clear();
                e.getDrops().add(new ItemStack(Items.DIAMOND, 2));
                e.setDroppedExp(37);
            }
        });
        t.tick(10);
        alice.attack(pig);
        t.assertEquals(1, events.size(), "the number of EntityDeathEvents for the pig");
        assertPlayer(t, events.get(0).getActor(), alice, "Killing the pig");
        int diamonds = 0;
        int other = 0;
        for (ItemEntity item : near(t, ItemEntity.class)) {
            if (item.getItem().is(Items.DIAMOND)) diamonds += item.getItem().getCount();
            else other += item.getItem().getCount();
        }
        t.assertEquals(2, diamonds, "the diamonds dropped");
        t.assertEquals(0, other, "the other items dropped (the pork should be gone)");
        int exp = near(t, ExperienceOrb.class).stream().mapToInt(ExperienceOrb::getValue).sum();
        t.assertEquals(37, exp, "the experience dropped");
    }

    /** A slow arrow from {@code alice} toward the pig, three-quarters of a second in flight. */
    private static Arrow slowArrowAt(TestContext t, TestPlayer alice, Pig pig) {
        Arrow arrow = new Arrow(t.level(), alice.player(), new ItemStack(Items.ARROW), null);
        arrow.moveTo(pig.getX(), pig.getY() + 0.45, pig.getZ() - 6, 0.0F, 0.0F);
        arrow.setNoGravity(true);
        arrow.shoot(0, 0, 1, 0.4F, 0.0F);
        return arrow;
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    @Covers(ProjectileHitEvent.class)
    @Covers(value = Actor.class, method = "attribution")
    @Covers(value = Attribution.class, method = "responsible")
    void anArrowsHitIsItsShootersAfterTicksInFlight(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 4));
        List<Actor> actors = new ArrayList<>();
        List<ProjectileHitEvent> hits = new ArrayList<>();
        int[] hitAt = {-1};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDamageEvent e) {
                if (e.getEntity() != pig) return;
                actors.add(e.getActor());
                hitAt[0] = t.ticksSpent();
            }

            @EventHandler
            void on(ProjectileHitEvent e) {
                if (e.getHitEntity().orElse(null) == pig) hits.add(e);
            }
        });
        Arrow arrow = slowArrowAt(t, alice, pig);
        int launched = t.ticksSpent();
        t.level().addFreshEntity(arrow);
        t.await("the arrow to hit the pig", () -> !actors.isEmpty(), 60);
        t.assertTrue(hitAt[0] - launched >= 5, "the arrow should have flown for a while, but hit after "
                + (hitAt[0] - launched) + " ticks");
        assertEntityFor(t, actors.get(0), arrow, alice.uuid(), "The arrow's hit");
        t.assertEquals(1, hits.size(), "the number of ProjectileHitEvents on the pig");
        assertEntityFor(t, hits.get(0).getActor(), arrow, alice.uuid(), "The ProjectileHitEvent");
        t.assertEquals(Optional.of(arrow), hits.get(0).getProjectile(), "the projectile that hit");
    }

    @HadronTest
    @Covers(ProjectileHitEvent.class)
    void aCancelledArrowHitLeavesTheTargetUnhurt(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 4));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ProjectileHitEvent e) {
                if (e.getHitEntity().orElse(null) == pig) {
                    asked[0]++;
                    e.setCancelled(true);
                }
            }
        });
        t.level().addFreshEntity(slowArrowAt(t, alice, pig));
        t.await("the arrow to reach the pig", () -> asked[0] > 0, 60);
        t.tick(5);
        t.assertEquals(pig.getMaxHealth(), pig.getHealth(), "the pig's health");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    @Covers(value = Actor.class, method = "attribution")
    @Covers(value = Attribution.class, method = "responsible")
    void anArrowStillAnswersToItsShooterAfterTheyLeave(TestContext t) {
        TestPlayer alice = t.player("alice");
        UUID aliceId = alice.uuid();
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 4));
        List<EntityDamageEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDamageEvent e) {
                if (e.getEntity() == pig) events.add(e);
            }
        });
        Arrow arrow = slowArrowAt(t, alice, pig);
        t.level().addFreshEntity(arrow);
        t.player("bob"); // keeps the world ticking once alice leaves
        alice.disconnect();
        t.await("the arrow to hit the pig", () -> !events.isEmpty(), 60);
        assertEntityFor(t, events.get(0).getActor(), arrow, aliceId, "The arrow's hit after its shooter left");
        t.assertEquals(Optional.empty(), events.get(0).getActingPlayer(), "the acting player (alice is offline)");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    @Covers(value = Actor.class, method = "attribution")
    @Covers(value = Attribution.class, method = "responsible")
    void anArrowRemembersItsShooterThroughASave(TestContext t) {
        TestPlayer alice = t.player("alice");
        UUID aliceId = alice.uuid();
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 4));
        List<Actor> actors = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDamageEvent e) {
                if (e.getEntity() == pig) actors.add(e.getActor());
            }
        });
        CompoundTag saved = new CompoundTag();
        t.assertTrue(slowArrowAt(t, alice, pig).save(saved), "the arrow should save");
        t.player("bob"); // keeps the world ticking once alice leaves
        alice.disconnect(); // so only what the arrow saved can name her
        Entity loaded = EntityType.loadEntityRecursive(saved, t.level(), e -> e);
        t.assertTrue(loaded instanceof Arrow, "the saved arrow should load as an arrow, but loaded " + loaded);
        t.level().addFreshEntity(loaded);
        t.await("the reloaded arrow to hit the pig", () -> !actors.isEmpty(), 60);
        assertEntityFor(t, actors.get(0), loaded, aliceId, "The reloaded arrow's hit");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    @Covers(value = Actor.class, method = "attribution")
    @Covers(value = Attribution.class, method = "responsible")
    void aTamedWolfsBiteIsItsOwners(TestContext t) {
        TestPlayer alice = t.player("alice");
        Wolf wolf = spawn(t, EntityType.WOLF, t.pos(2, 0, 2));
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 2));
        t.tick(); // the wolf ticks once untamed, so a cached actor from before taming would show
        wolf.tame(alice.player());
        List<Actor> actors = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDamageEvent e) {
                if (e.getEntity() == pig) actors.add(e.getActor());
            }
        });
        t.tick();
        wolf.doHurtTarget(pig);
        t.assertEquals(1, actors.size(), "the number of EntityDamageEvents for the pig");
        assertEntityFor(t, actors.get(0), wolf, alice.uuid(), "The tamed wolf's bite");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    @Covers(value = Actor.class, method = "attribution")
    @Covers(value = Attribution.class, method = "responsible")
    void aReloadedWolfStillAnswersToItsOwner(TestContext t) {
        TestPlayer alice = t.player("alice");
        UUID aliceId = alice.uuid();
        Wolf wolf = spawn(t, EntityType.WOLF, t.pos(2, 0, 2));
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 2));
        wolf.tame(alice.player());
        CompoundTag saved = new CompoundTag();
        wolf.save(saved);
        wolf.discard();
        alice.disconnect();
        Entity loaded = EntityType.loadEntityRecursive(saved, t.level(), e -> e);
        t.assertTrue(loaded instanceof Wolf, "the saved wolf should load as a wolf, but loaded " + loaded);
        t.level().addFreshEntity(loaded);
        List<Actor> actors = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDamageEvent e) {
                if (e.getEntity() == pig) actors.add(e.getActor());
            }
        });
        t.tick();
        ((Wolf) loaded).doHurtTarget(pig);
        t.assertEquals(1, actors.size(), "the number of EntityDamageEvents for the pig");
        assertEntityFor(t, actors.get(0), loaded, aliceId, "The reloaded wolf's bite");
    }

    /** Armor stands replace LivingEntity.hurt without calling it, so NeoForge's damage event never reaches them. */
    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aPlayersHitOnAnArmorStandIsTheirs(TestContext t) {
        TestPlayer alice = t.player("alice");
        ArmorStand stand = spawn(t, EntityType.ARMOR_STAND, t.pos(2, 0, 0));
        List<EntityDamageEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDamageEvent e) {
                if (e.getEntity() == stand) events.add(e);
            }
        });
        alice.attack(stand);
        t.assertEquals(1, events.size(), "the number of EntityDamageEvents for the armor stand");
        assertPlayer(t, events.get(0).getActor(), alice, "A hit on an armor stand");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void twoQuickHitsBreakAnArmorStand(TestContext t) {
        TestPlayer alice = t.player("alice");
        ArmorStand stand = spawn(t, EntityType.ARMOR_STAND, t.pos(2, 0, 0));
        alice.attack(stand);
        alice.attack(stand);
        t.assertTrue(stand.isRemoved(), "two quick hits should break an armor stand, the control for the cancel test");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aCancelledHitLeavesAnArmorStandStanding(TestContext t) {
        TestPlayer alice = t.player("alice");
        ArmorStand stand = spawn(t, EntityType.ARMOR_STAND, t.pos(2, 0, 0));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDamageEvent e) {
                if (e.getEntity() == stand) e.setCancelled(true);
            }
        });
        alice.attack(stand);
        alice.attack(stand);
        t.assertFalse(stand.isRemoved(), "a cancelled hit should leave the armor stand standing");
        t.assertTrue(near(t, ItemEntity.class).isEmpty(), "a cancelled hit should drop nothing");
    }

    @HadronTest
    @Covers(VehicleDamageEvent.class)
    void aPlayersHitOnABoatIsTheirsAndBreaksItInCreative(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.player().setGameMode(GameType.CREATIVE);
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        List<VehicleDamageEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(VehicleDamageEvent e) {
                if (e.getEntity() == boat) events.add(e);
            }
        });
        alice.attack(boat);
        t.assertEquals(1, events.size(), "the number of VehicleDamageEvents");
        assertPlayer(t, events.get(0).getActor(), alice, "Hitting the boat");
        t.assertEquals(Optional.of(alice.player()), events.get(0).getDamager(), "the damager");
        t.assertTrue(boat.isRemoved(), "a creative player's hit should break the boat");
    }

    @HadronTest
    @Covers(VehicleDamageEvent.class)
    void aCancelledHitLeavesTheBoat(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.player().setGameMode(GameType.CREATIVE);
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(VehicleDamageEvent e) {
                if (e.getEntity() == boat) e.setCancelled(true);
            }
        });
        alice.attack(boat);
        t.tick();
        t.assertFalse(boat.isRemoved(), "a cancelled hit should leave the boat");
        t.assertEquals(0f, boat.getDamage(), "the boat's wear");
        t.assertTrue(near(t, ItemEntity.class).isEmpty(), "a cancelled hit should drop nothing");
    }

    @HadronTest
    @Covers(EntityRemoveEvent.class)
    void shatteringAnEndCrystalIsThePlayersRemoval(TestContext t) {
        TestPlayer alice = t.player("alice");
        EndCrystal crystal = spawn(t, EntityType.END_CRYSTAL, t.pos(3, 0, 0));
        List<EntityRemoveEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityRemoveEvent e) {
                if (e.getEntity() == crystal) events.add(e);
            }
        });
        alice.attack(crystal);
        t.assertEquals(1, events.size(), "the number of EntityRemoveEvents");
        t.assertEquals(EntityRemoveEvent.Cause.KILLED, events.get(0).getCause(), "the cause");
        assertPlayer(t, events.get(0).getActor(), alice, "Shattering the crystal");
        t.assertTrue(crystal.isRemoved(), "the crystal should be gone");
    }

    @HadronTest
    @Covers(EntityRemoveEvent.class)
    void aRefusedShatterKeepsTheCrystalAndNothingExplodes(TestContext t) {
        TestPlayer alice = t.player("alice");
        EndCrystal crystal = spawn(t, EntityType.END_CRYSTAL, t.pos(3, 0, 0));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityRemoveEvent e) {
                if (e.getEntity() == crystal) e.setCancelled(true);
            }
        });
        float health = alice.player().getHealth();
        alice.attack(crystal);
        t.tick();
        t.assertFalse(crystal.isRemoved(), "a refused shatter should keep the crystal");
        t.assertEquals(health, alice.player().getHealth(), "alice's health: nothing should have exploded");
    }
}
