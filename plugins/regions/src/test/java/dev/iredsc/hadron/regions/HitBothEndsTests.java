package dev.iredsc.hadron.regions;

import static dev.iredsc.hadron.regions.RegionsTests.admin;
import static dev.iredsc.hadron.regions.RegionsTests.define;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A pvp node is decided at both ends of a hit: where the victim is and where the hit set out from, so a no-PvP zone
 * keeps hits out and keeps its own from leaving. Every other node is decided where it lands only.
 */
public final class HitBothEndsTests {
    /** Long enough for a player who just joined to lose the spawn protection that makes them immune to hits. */
    private static final int SPAWN_PROTECTION = 61;

    /** Every damage event for {@code victim}, as it ends up once every handler ran. */
    private static List<EntityDamageEvent> hurts(TestContext t, Entity victim) {
        List<EntityDamageEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            void on(EntityDamageEvent e) {
                if (e.getEntity() == victim) events.add(e);
            }
        });
        return events;
    }

    /** {@code shooter}'s slow arrow (alice, who defines the region, is moved out of its path), launched where {@code shooter} stands, flying straight along +z at chest height. */
    private static Arrow shoot(TestContext t, TestPlayer shooter) {
        Arrow arrow = new Arrow(t.level(), shooter.player(), new ItemStack(Items.ARROW), null);
        arrow.moveTo(shooter.player().getX(), shooter.player().getY() + 1.0, shooter.player().getZ(), 0f, 0f);
        arrow.setNoGravity(true);
        arrow.shoot(0, 0, 1, 0.4f, 0f);
        t.level().addFreshEntity(arrow);
        return arrow;
    }

    private static EntityDamageEvent first(TestContext t, List<EntityDamageEvent> events, String what) {
        t.await(what, () -> !events.isEmpty(), 80);
        return events.get(0);
    }

    private static <M extends Mob> M still(TestContext t, EntityType<M> type, BlockPos at) {
        M mob = type.create(t.level());
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        t.level().addFreshEntity(mob);
        return mob;
    }

    @HadronTest
    void anArrowShotOutOfANoPvpZoneCantHitAPlayerOutside(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "arrow-safe", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        alice.command("rg flag arrow-safe pvp deny");
        alice.moveTo(t.pos(6, 0, -6));
        TestPlayer bob = t.player("bob");
        TestPlayer carol = t.player("carol");
        bob.moveTo(t.pos(0, 0, 0));
        carol.moveTo(t.pos(0, 0, 6));
        t.tick(SPAWN_PROTECTION);
        List<EntityDamageEvent> hits = hurts(t, carol.player());
        shoot(t, bob);
        t.assertTrue(first(t, hits, "bob's arrow to reach carol").isCancelled(),
                "bob shot from inside a no-PvP zone; carol, outside it, should not be hurt");
        t.assertEquals(carol.player().getMaxHealth(), carol.player().getHealth(), "carol's health");
    }

    @HadronTest
    void anArrowShotOutOfANoPvpZoneIsRefusedAfterTheShooterWalksOut(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "arrow-walk", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        alice.command("rg flag arrow-walk pvp deny");
        alice.moveTo(t.pos(6, 0, -6));
        TestPlayer bob = t.player("bob");
        TestPlayer carol = t.player("carol");
        bob.moveTo(t.pos(0, 0, 0));
        carol.moveTo(t.pos(0, 0, 6));
        t.tick(SPAWN_PROTECTION);
        List<EntityDamageEvent> hits = hurts(t, carol.player());
        shoot(t, bob);
        bob.moveTo(t.pos(-8, 0, -8));
        EntityDamageEvent hit = first(t, hits, "bob's arrow to reach carol");
        t.assertTrue(store(t).at(t.level(), bob.player().blockPosition()).isEmpty(),
                "the scene: bob is outside every region when his arrow lands");
        t.assertTrue(hit.isCancelled(), "the arrow set out from inside the zone, wherever bob is now");
    }

    @HadronTest
    void anArrowShotIntoANoPvpZoneCantHitAPlayerInside(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "arrow-in", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        alice.command("rg flag arrow-in pvp deny");
        alice.moveTo(t.pos(6, 0, -6));
        TestPlayer bob = t.player("bob");
        TestPlayer carol = t.player("carol");
        bob.moveTo(t.pos(0, 0, -6));
        carol.moveTo(t.pos(0, 0, 0));
        t.tick(SPAWN_PROTECTION);
        List<EntityDamageEvent> hits = hurts(t, carol.player());
        shoot(t, bob);
        t.assertTrue(first(t, hits, "bob's arrow to reach carol").isCancelled(),
                "carol stands in a no-PvP zone; bob's arrow from outside should not hurt her");
    }

    @HadronTest
    void aVisitorInAProtectedRegionHitsACowAndAZombieOutsideIt(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "porch", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        alice.command("rg flag porch pvp deny");
        Cow cow = still(t, EntityType.COW, t.pos(3, 0, 0));
        Zombie zombie = still(t, EntityType.ZOMBIE, t.pos(3, 0, 1));
        TestPlayer bob = t.player("bob");
        bob.moveTo(t.pos(2, 0, 0));
        bob.hold(new ItemStack(Items.DIAMOND_SWORD));
        t.tick(20);
        bob.attack(cow);
        t.tick(20);
        bob.attack(zombie);
        t.tick(2);
        t.assertTrue(cow.getHealth() < cow.getMaxHealth(), "bob, standing in the region, should hurt the cow outside it");
        t.assertTrue(zombie.getHealth() < zombie.getMaxHealth(),
                "bob, standing in the region, should hurt the zombie outside it");
    }

    private static RegionStore store(TestContext t) {
        return ((RegionsPlugin) t.plugin()).regions();
    }
}
