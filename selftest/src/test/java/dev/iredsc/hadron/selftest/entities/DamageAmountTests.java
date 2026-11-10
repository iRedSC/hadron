package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.entity.VehicleDamageEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/**
 * Hits whose amount isn't a finite number of at least 0, which only a mod's mistake makes: each still fires
 * {@link EntityDamageEvent}, so protection can refuse it, and what it deals is what the event reported.
 */
public final class DamageAmountTests {
    /** Listens to damage to {@code target}, handing each event to {@code then}; returns the events heard. */
    private static List<EntityDamageEvent> listen(TestContext t, LivingEntity target,
            Consumer<EntityDamageEvent> then) {
        List<EntityDamageEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDamageEvent e) {
                if (e.getEntity() != target) return;
                heard.add(e);
                then.accept(e);
            }
        });
        return heard;
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aNegativeHitOnAStandCanBeRefused(TestContext t) {
        TestPlayer alice = t.player("alice");
        ArmorStand stand = spawn(t, EntityType.ARMOR_STAND, t.pos(2, 0, 0));
        List<EntityDamageEvent> heard = listen(t, stand, e -> e.setCancelled(true));
        stand.hurt(t.level().damageSources().playerAttack(alice.player()), -1f);
        stand.hurt(t.level().damageSources().playerAttack(alice.player()), -1f);
        t.assertEquals(2, heard.size(), "damage events for two negative hits on a stand");
        t.assertEquals(0f, heard.get(0).getDamage(), "the damage a negative hit is reported as");
        t.assertFalse(stand.isRemoved(), "two refused hits should leave the stand standing");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aNegativeHitCanBeRefusedKnockbackAndAll(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        pig.setDeltaMovement(Vec3.ZERO);
        List<EntityDamageEvent> heard = listen(t, pig, e -> e.setCancelled(true));
        pig.hurt(t.level().damageSources().playerAttack(alice.player()), -1f);
        t.assertEquals(1, heard.size(), "damage events for a negative hit");
        t.assertEquals(Vec3.ZERO, pig.getDeltaMovement(), "the pig's movement after a refused negative hit");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aNaNHitCanBeRefusedAndUnrefusedDealsNothing(TestContext t) {
        t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        boolean[] refuse = {true};
        List<EntityDamageEvent> heard = listen(t, pig, e -> e.setCancelled(refuse[0]));
        pig.hurt(t.level().damageSources().generic(), Float.NaN);
        t.assertEquals(1, heard.size(), "damage events for a NaN hit");
        t.assertEquals(pig.getMaxHealth(), pig.getHealth(), "the pig's health after a refused NaN hit");

        refuse[0] = false;
        pig.invulnerableTime = 0;
        pig.hurt(t.level().damageSources().generic(), Float.NaN);
        t.assertEquals(0f, heard.get(1).getDamage(), "the damage a NaN hit is reported as");
        t.assertEquals(pig.getMaxHealth(), pig.getHealth(), "an unrefused NaN hit should deal the 0 it reported, "
                + "but the pig's health is " + pig.getHealth());
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void anInfiniteHitKillsWithoutNaNHealthWhetherOrNotAListenerSetsIt(TestContext t) {
        t.player("alice");
        Pig untouched = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        List<EntityDamageEvent> heard = listen(t, untouched, e -> {});
        untouched.hurt(t.level().damageSources().generic(), Float.POSITIVE_INFINITY);
        t.assertEquals(Float.MAX_VALUE, heard.get(0).getDamage(), "the damage an infinite hit is reported as");
        t.assertTrue(untouched.isDeadOrDying() && !Float.isNaN(untouched.getHealth()), "an infinite hit should "
                + "kill, but the pig's health is " + untouched.getHealth());

        Pig set = spawn(t, EntityType.PIG, t.pos(-2, 0, 0));
        listen(t, set, e -> e.setDamage(Float.MAX_VALUE)); // the amount already reported, set on purpose
        set.hurt(t.level().damageSources().generic(), Float.POSITIVE_INFINITY);
        t.assertTrue(set.isDeadOrDying() && !Float.isNaN(set.getHealth()), "a listener setting Float.MAX_VALUE "
                + "should kill, but the pig's health is " + set.getHealth());
    }

    @HadronTest
    @Covers(VehicleDamageEvent.class)
    void aNaNOrNegativeHitOnABoatCanBeRefusedAndUnrefusedDealsWhatWasReported(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        boolean[] refuse = {true};
        List<VehicleDamageEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(VehicleDamageEvent e) {
                if (e.getEntity() != boat) return;
                heard.add(e);
                e.setCancelled(refuse[0]);
            }
        });
        boat.hurt(t.level().damageSources().playerAttack(alice.player()), -1f);
        boat.hurt(t.level().damageSources().generic(), Float.NaN);
        t.assertEquals(2, heard.size(), "vehicle damage events for a negative and a NaN hit");
        t.assertEquals(0f, heard.get(1).getDamage(), "the damage a NaN hit is reported as");
        t.assertEquals(0f, boat.getDamage(), "the boat's wear after two refused hits");

        refuse[0] = false;
        boat.hurt(t.level().damageSources().generic(), Float.NaN);
        t.assertEquals(0f, boat.getDamage(), "an unrefused NaN hit should deal the 0 it reported, but the boat's wear "
                + "is " + boat.getDamage());
        t.assertFalse(boat.isRemoved(), "a hit dealing 0 shouldn't break the boat");
    }
}
