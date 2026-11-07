package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.entities.Fixtures.assertServer;
import static dev.iredsc.hadron.selftest.entities.Fixtures.console;
import static dev.iredsc.hadron.selftest.entities.Fixtures.near;
import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;
import static dev.iredsc.hadron.selftest.entities.Fixtures.spawnWithAi;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityAirChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntityCombustEvent;
import dev.iredsc.hadron.api.event.entity.EntityPotionEffectEvent;
import dev.iredsc.hadron.api.event.entity.EntityPotionEffectExpireEvent;
import dev.iredsc.hadron.api.event.entity.EntityRegainHealthEvent;
import dev.iredsc.hadron.api.event.entity.EntityStatusChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntityTargetEvent;
import dev.iredsc.hadron.api.event.entity.EntityTransformEvent;
import dev.iredsc.hadron.api.event.entity.EntityVelocityEvent;
import dev.iredsc.hadron.api.event.entity.EntityKnockbackEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;

/** Healing, fire, effects, AI, air, targets and conversions: who caused each, cancels undo, changes apply. */
public final class EntityStateTests {
    @HadronTest
    void aChangedHealIsTheHealthRegained(TestContext t) {
        t.player("alice"); // entities only tick, and commands only find them, near a player
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        pig.setHealth(4);
        List<Float> asked = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityRegainHealthEvent e) {
                if (e.getEntity() != pig) return;
                asked.add(e.getAmount());
                e.setAmount(5);
            }
        });
        pig.heal(1);
        t.assertEquals(List.of(1.0F), asked, "the amounts EntityRegainHealthEvent was asked about");
        t.assertEquals(9.0F, pig.getHealth(), "the pig's health");
    }

    @HadronTest
    void aCancelledHealLeavesTheHealth(TestContext t) {
        t.player("alice"); // entities only tick, and commands only find them, near a player
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        pig.setHealth(4);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityRegainHealthEvent e) {
                if (e.getEntity() == pig) e.setCancelled(true);
            }
        });
        pig.heal(3);
        t.assertEquals(4.0F, pig.getHealth(), "the pig's health");
    }

    private static ItemStack fireAspectSword(TestContext t) {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.enchant(t.server().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.FIRE_ASPECT), 2);
        return sword;
    }

    @HadronTest
    void aFireAspectHitSetsFireForTheAttackerForTheChosenTime(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        alice.hold(fireAspectSword(t));
        List<Actor> actors = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityCombustEvent e) {
                if (e.getEntity() != pig) return;
                actors.add(e.getActor());
                e.setDuration(200);
            }
        });
        t.tick(10);
        alice.attack(pig);
        t.assertFalse(actors.isEmpty(), "the fire aspect hit should fire an EntityCombustEvent");
        assertPlayer(t, actors.get(0), alice, "Setting the pig on fire");
        t.assertEquals(200, pig.getRemainingFireTicks(), "the pig's fire ticks");
    }

    @HadronTest
    void aCancelledCombustLeavesTheVictimUnburnt(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        alice.hold(fireAspectSword(t));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityCombustEvent e) {
                if (e.getEntity() == pig) e.setCancelled(true);
            }
        });
        t.tick(10);
        alice.attack(pig);
        t.assertFalse(pig.isOnFire(), "the pig should not be on fire, but has " + pig.getRemainingFireTicks()
                + " fire ticks");
        t.assertTrue(pig.getHealth() < pig.getMaxHealth(), "the hit itself should still land");
    }

    @HadronTest
    void aConsoleEffectIsTheServersAndCancelledIsNeverApplied(TestContext t) {
        t.player("alice"); // entities only tick, and commands only find them, near a player
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        List<EntityPotionEffectEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityPotionEffectEvent e) {
                if (e.getEntity() != pig) return;
                events.add(e);
                e.setCancelled(true);
            }
        });
        console(t, "effect give " + pig.getStringUUID() + " minecraft:speed 30 1");
        t.assertEquals(1, events.size(), "the number of EntityPotionEffectEvents");
        EntityPotionEffectEvent e = events.get(0);
        assertServer(t, e.getActor(), "A console /effect");
        t.assertEquals(EntityPotionEffectEvent.Action.ADDED, e.getAction(), "the action");
        t.assertEquals(MobEffects.MOVEMENT_SPEED, e.getEffect(), "the effect");
        t.assertFalse(pig.hasEffect(MobEffects.MOVEMENT_SPEED), "the cancelled speed should never be applied");
    }

    @HadronTest
    void aCancelledEffectRemovalKeepsTheEffect(TestContext t) {
        t.player("alice"); // entities only tick, and commands only find them, near a player
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        pig.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600));
        List<EntityPotionEffectEvent.Action> actions = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityPotionEffectEvent e) {
                if (e.getEntity() != pig) return;
                actions.add(e.getAction());
                e.setCancelled(true);
            }
        });
        console(t, "effect clear " + pig.getStringUUID() + " minecraft:speed");
        t.assertEquals(List.of(EntityPotionEffectEvent.Action.REMOVED), actions, "the actions asked about");
        t.assertTrue(pig.hasEffect(MobEffects.MOVEMENT_SPEED), "the pig should keep its speed");
    }

    @HadronTest
    void anEffectRunningOutIsReported(TestContext t) {
        t.player("alice"); // entities only tick, and commands only find them, near a player
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        pig.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 10));
        List<EntityPotionEffectExpireEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityPotionEffectExpireEvent e) {
                if (e.getEntity() == pig) events.add(e);
            }
        });
        t.await("the speed to run out", () -> !events.isEmpty(), 40);
        t.assertEquals(MobEffects.MOVEMENT_SPEED, events.get(0).getEffect().getEffect(), "the expired effect");
        t.assertFalse(pig.hasEffect(MobEffects.MOVEMENT_SPEED), "the speed should be gone");
    }

    @HadronTest
    void aConsoleNoAiChangeIsTheServersAndCancelledKeepsTheAi(TestContext t) {
        t.player("alice"); // entities only tick, and commands only find them, near a player
        Pig pig = spawnWithAi(t, EntityType.PIG, t.pos(2, 0, 0));
        List<EntityStatusChangeEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityStatusChangeEvent e) {
                if (e.getEntity() != pig) return;
                events.add(e);
                e.setCancelled(true);
            }
        });
        console(t, "data merge entity " + pig.getStringUUID() + " {NoAI:1b}");
        t.assertEquals(1, events.size(), "the number of EntityStatusChangeEvents");
        t.assertEquals(EntityStatusChangeEvent.NO_AI, events.get(0).getStatus(), "the status");
        t.assertTrue(events.get(0).isApplied(), "the status should be being applied");
        assertServer(t, events.get(0).getActor(), "A console /data merge");
        t.assertFalse(pig.isNoAi(), "the cancelled NoAI should leave the pig's AI on");
    }

    /** A pig in a one-block pool walled with glass, its eyes under water. */
    private static Pig pigUnderWater(TestContext t) {
        BlockPos pool = t.pos(3, 0, 0);
        for (BlockPos wall : List.of(pool.east(), pool.west(), pool.north(), pool.south())) {
            t.setBlock(wall, Blocks.GLASS);
            t.setBlock(wall.above(), Blocks.GLASS);
        }
        t.setBlock(pool, Blocks.WATER);
        t.setBlock(pool.above(), Blocks.WATER);
        return spawn(t, EntityType.PIG, pool);
    }

    @HadronTest
    void holdingBreathUnderWaterIsNatures(TestContext t) {
        t.player("alice"); // entities only tick, and commands only find them, near a player
        Pig pig = pigUnderWater(t);
        List<EntityAirChangeEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityAirChangeEvent e) {
                if (e.getEntity() == pig) events.add(e);
            }
        });
        t.await("the pig to lose air", () -> !events.isEmpty(), 20);
        EntityAirChangeEvent e = events.get(0);
        t.assertTrue(e.getAmount() < e.getOldAmount(), "air should go down under water, but went from "
                + e.getOldAmount() + " to " + e.getAmount());
        t.assertTrue(e.getActor() instanceof Actor.Nature, "Losing air should be Actor.Nature, but was " + e.getActor());
    }

    @HadronTest
    void cancelledAirLossKeepsTheBreath(TestContext t) {
        t.player("alice"); // entities only tick, and commands only find them, near a player
        Pig pig = pigUnderWater(t);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityAirChangeEvent e) {
                if (e.getEntity() == pig) e.setCancelled(true);
            }
        });
        t.tick(20);
        t.assertTrue(pig.isEyeInFluid(net.minecraft.tags.FluidTags.WATER), "the pig's eyes should be under water");
        t.assertEquals(pig.getMaxAirSupply(), pig.getAirSupply(), "the pig's air");
    }

    @HadronTest
    void aConsoleLaunchIsAVelocityChangeByTheServer(TestContext t) {
        t.player("alice"); // entities only tick, and commands only find them, near a player
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        List<EntityVelocityEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityVelocityEvent e) {
                if (e.getEntity() == pig && !(e instanceof EntityKnockbackEvent)) events.add(e);
            }
        });
        console(t, "data merge entity " + pig.getStringUUID() + " {Motion:[0.0d,1.5d,0.0d]}");
        t.assertTrue(pig.getDeltaMovement().y > 1, "the command should have launched the pig");
        t.assertEquals(1, events.size(), "the number of EntityVelocityEvents for an operator launching the pig");
        assertServer(t, events.get(0).getActor(), "A console launch");
    }

    private static void requireMonsters(TestContext t) {
        t.assertTrue(t.level().getDifficulty() != Difficulty.PEACEFUL, "the test server is on peaceful, so monsters "
                + "and conversions can't be tested");
    }

    @HadronTest
    void aZombieChoosingItsTargetIsTheZombies(TestContext t) {
        requireMonsters(t);
        TestPlayer alice = t.player("alice");
        Zombie zombie = spawnWithAi(t, EntityType.ZOMBIE, t.pos(5, 0, 0));
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET)); // no burning in daylight
        List<EntityTargetEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTargetEvent e) {
                if (e.getEntity() == zombie && e.getTarget().isPresent()) events.add(e);
            }
        });
        t.await("the zombie to target alice", () -> !events.isEmpty(), 200);
        t.assertEquals(Optional.of(alice.player()), events.get(0).getTarget(), "the zombie's target");
        t.assertTrue(events.get(0).getActor() instanceof Actor.EntityActor a && a.entity() == zombie,
                "A zombie choosing its target should be the zombie's EntityActor, but was " + events.get(0).getActor());
    }

    @HadronTest
    void aRedirectedTargetIsWhoTheMobTargets(TestContext t) {
        t.player("alice"); // entities only tick, and commands only find them, near a player
        Zombie zombie = spawn(t, EntityType.ZOMBIE, t.pos(4, 0, 0));
        Pig pig = spawn(t, EntityType.PIG, t.pos(-4, 0, 0));
        Pig decoy = spawn(t, EntityType.PIG, t.pos(0, 0, 4));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTargetEvent e) {
                if (e.getEntity() == zombie && e.getTarget().orElse(null) == pig) e.setTarget(decoy);
            }
        });
        zombie.setTarget(pig);
        t.assertEquals(decoy, zombie.getTarget(), "the zombie's target");
    }

    @HadronTest
    void aCancelledTargetLeavesTheMobWithoutOne(TestContext t) {
        t.player("alice"); // entities only tick, and commands only find them, near a player
        Zombie zombie = spawn(t, EntityType.ZOMBIE, t.pos(4, 0, 0));
        Pig pig = spawn(t, EntityType.PIG, t.pos(-4, 0, 0));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTargetEvent e) {
                if (e.getEntity() == zombie) e.setCancelled(true);
            }
        });
        zombie.setTarget(pig);
        t.assertEquals(null, zombie.getTarget(), "the zombie's target");
    }

    private static void strike(TestContext t, Pig pig) {
        console(t, "summon minecraft:lightning_bolt " + pig.getX() + " " + pig.getY() + " " + pig.getZ());
    }

    @HadronTest
    void lightningTurningAPigIntoAZombifiedPiglinIsReported(TestContext t) {
        requireMonsters(t);
        t.player("alice"); // entities only tick, and commands only find them, near a player
        Pig pig = spawn(t, EntityType.PIG, t.pos(5, 0, 5));
        List<EntityTransformEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTransformEvent e) {
                if (e.getEntity() == pig) events.add(e);
            }
        });
        strike(t, pig);
        t.await("the pig to be struck", () -> !events.isEmpty(), 20);
        t.assertEquals(EntityType.ZOMBIFIED_PIGLIN, events.get(0).getTransformedType(), "the new type");
        t.tick();
        t.assertTrue(pig.isRemoved(), "the pig should be gone once converted");
        t.assertEquals(1, near(t, ZombifiedPiglin.class).size(), "the number of zombified piglins");
    }

    @HadronTest
    void aCancelledTransformKeepsThePig(TestContext t) {
        requireMonsters(t);
        t.player("alice"); // entities only tick, and commands only find them, near a player
        Pig pig = spawn(t, EntityType.PIG, t.pos(5, 0, 5));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTransformEvent e) {
                if (e.getEntity() == pig) {
                    asked[0]++;
                    e.setCancelled(true);
                }
            }
        });
        strike(t, pig);
        t.await("the pig to be struck", () -> asked[0] > 0, 20);
        t.tick(2);
        t.assertFalse(pig.isRemoved(), "the pig should still be in the world");
        t.assertEquals(0, near(t, ZombifiedPiglin.class).size(), "the number of zombified piglins");
    }
}
