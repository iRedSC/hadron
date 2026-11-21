package dev.iredsc.hadron.regions;

import static dev.iredsc.hadron.regions.RegionsTests.admin;
import static dev.iredsc.hadron.regions.RegionsTests.define;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.entity.EntityCaptureEvent;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A region keeps the animals and villagers in it, and leaves monsters to whoever they attack. */
public final class MobRegionsTests {
    /** A mob standing still at {@code at}, so it is where the test put it when the test looks. */
    private static <M extends Mob> M spawn(TestContext t, EntityType<M> type, BlockPos at) {
        M mob = type.create(t.level());
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        t.level().addFreshEntity(mob);
        return mob;
    }

    @HadronTest
    void anOutsiderCantKillAMembersCowAndAMemberCan(TestContext t) {
        TestPlayer alice = admin(t);
        Cow cow = spawn(t, EntityType.COW, t.pos(1, 0, 1));
        define(t, alice, "cow-pen", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        TestPlayer bob = t.player("bob");
        bob.hold(new ItemStack(Items.DIAMOND_SWORD));
        t.tick(20);
        bob.attack(cow);
        t.tick(2);
        t.assertEquals(cow.getMaxHealth(), cow.getHealth(), "the cow's health after an outsider's hit");
        TestPlayer carol = t.player("carol");
        alice.command("rg addmember cow-pen carol");
        carol.hold(new ItemStack(Items.DIAMOND_SWORD));
        for (int hit = 0; hit < 10 && cow.isAlive(); hit++) {
            t.tick(20);
            carol.attack(cow);
        }
        t.assertFalse(cow.isAlive(), "a member should be able to kill the cow, but it has " + cow.getHealth()
                + " health left");
    }

    @HadronTest
    void anOutsiderCanKillAZombieInARegion(TestContext t) {
        TestPlayer alice = admin(t);
        Zombie zombie = spawn(t, EntityType.ZOMBIE, t.pos(1, 0, 1));
        define(t, alice, "zombie-pen", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        TestPlayer bob = t.player("bob");
        bob.hold(new ItemStack(Items.DIAMOND_SWORD));
        t.tick(20);
        bob.attack(zombie);
        t.tick(2);
        t.assertTrue(zombie.getLastDamageSource() != null && zombie.getLastDamageSource().getEntity() == bob.player(),
                "an outsider's hit should land on a zombie in a region, but its last damage was "
                        + zombie.getLastDamageSource());
    }

    @HadronTest
    void anOutsiderCantCaptureAVillagerIntoAnItem(TestContext t) {
        TestPlayer alice = admin(t);
        Villager villager = spawn(t, EntityType.VILLAGER, t.pos(3, 0, 0));
        Zombie zombie = spawn(t, EntityType.ZOMBIE, t.pos(3, 0, 1));
        Villager stray = spawn(t, EntityType.VILLAGER, t.pos(-4, 0, 0));
        define(t, alice, "village", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        EntityCaptureEvent.Storage catcher = new EntityCaptureEvent.Storage.Item(new ItemStack(Items.LEAD));
        EntityCaptureEvent caught = new EntityCaptureEvent(villager, catcher, new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(caught);
        t.assertTrue(caught.isCancelled(), "bob should not catch a villager in a region");
        t.assertFalse(villager.isRemoved(), "the villager should still be there");
        EntityCaptureEvent monster = new EntityCaptureEvent(zombie, catcher, new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(monster);
        t.assertFalse(monster.isCancelled(), "a zombie in a region is nobody's to keep");
        EntityCaptureEvent outside = new EntityCaptureEvent(stray, catcher, new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(outside);
        t.assertFalse(outside.isCancelled(), "a villager outside every region is no region's");
        alice.command("rg addmember village bob");
        EntityCaptureEvent members = new EntityCaptureEvent(villager, catcher, new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(members);
        t.assertFalse(members.isCancelled(), "a member may catch the region's villager");
    }

    @HadronTest
    void anOutsiderCantBucketAnAxolotlWhereTheyMayUse(TestContext t) {
        TestPlayer alice = admin(t);
        Axolotl axolotl = spawn(t, EntityType.AXOLOTL, t.pos(1, 0, 1));
        define(t, alice, "pond", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        // Using is allowed, so only the capture itself can refuse it.
        alice.command("rg flag pond use allow");
        TestPlayer bob = t.player("bob");
        bob.hold(new ItemStack(Items.WATER_BUCKET));
        bob.interact(axolotl);
        t.tick(2);
        t.assertFalse(axolotl.isRemoved(), "the axolotl should still be in the pond");
        t.assertHolding(bob, Items.WATER_BUCKET, 1);
    }

    @HadronTest
    void aZombieOutsideCantHurtACowInsideAndAWolfInsideCan(TestContext t) {
        TestPlayer alice = admin(t);
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        Zombie zombie = spawn(t, EntityType.ZOMBIE, t.pos(1, 0, 0));
        Wolf wolf = spawn(t, EntityType.WOLF, t.pos(3, 0, 0));
        define(t, alice, "edge-pen", t.pos(2, 0, -1), t.pos(4, 2, 1));
        EntityDamageEvent across = new EntityDamageEvent(cow, t.level().damageSources().mobAttack(zombie), 3f,
                new Actor.EntityActor(zombie, Attribution.none()), Optional.of(Place.Spot.at(t.level(),
                zombie.position())));
        t.plugin().getServer().getPluginManager().callEvent(across);
        t.assertTrue(across.isCancelled(), "a zombie outside should not hurt a cow inside across the edge");
        EntityDamageEvent within = new EntityDamageEvent(cow, t.level().damageSources().mobAttack(wolf), 3f,
                new Actor.EntityActor(wolf, Attribution.none()), Optional.of(Place.Spot.at(t.level(),
                wolf.position())));
        t.plugin().getServer().getPluginManager().callEvent(within);
        t.assertFalse(within.isCancelled(), "a wolf inside the region may hurt a cow in it");
        EntityDamageEvent fall = new EntityDamageEvent(cow, t.level().damageSources().fall(), 3f,
                Actor.Nature.INSTANCE, Optional.empty());
        t.plugin().getServer().getPluginManager().callEvent(fall);
        t.assertFalse(fall.isCancelled(), "a cow inside still takes a fall");
    }
}
