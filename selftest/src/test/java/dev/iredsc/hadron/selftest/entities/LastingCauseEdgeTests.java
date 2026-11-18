package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.assertFor;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.golem;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.hurts;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.nextBurn;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.nextPoison;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.poison;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.reload;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.entity.EntityRegainHealthEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Where a lasting cause could be lost or given to the wrong actor: a save that can't all be read, a totem, a mod's own
 * effect instance, and fire lit again by what lit it (docs/events.md, "Attribution reaches every tick").
 */
public final class LastingCauseEdgeTests {
    /** The saved causes of {@code entity}'s effects, as they are in its save data. */
    private static CompoundTag savedCauses(TestContext t, CompoundTag entity) {
        CompoundTag attachments = entity.getCompound("neoforge:attachments");
        t.assertTrue(attachments.contains("hadron:effect_causes"), "the entity should save who applied its effects");
        return attachments.getCompound("hadron:effect_causes");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aSavedCauseForAnEffectThatIsGoneLeavesTheOthers(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos poisonedAt = t.pos(2, 0, 0);
        IronGolem golem = golem(t, poisonedAt);
        golem.addEffect(poison(600, 0), bob.player());
        // As after removing a mod: its effect's id is still in the save, and no longer registered.
        IronGolem loaded = reload(t, golem, tag -> {
            CompoundTag causes = savedCauses(t, tag);
            causes.put("removed_mod:gone", causes.getList("minecraft:poison", Tag.TAG_COMPOUND).copy());
        }, IronGolem.class);
        EntityDamageEvent tick = nextPoison(t, hurts(t, loaded));
        assertFor(t, bob, tick, "poison saved beside a cause for an effect that is gone");
        t.assertEquals(Optional.of(Place.Spot.at(t.level(), poisonedAt)), tick.getAppliedAt(),
                "where poison saved beside a cause for an effect that is gone was applied");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aSavedCauseThatCantBeReadLeavesTheOthersInItsChain(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        IronGolem golem = golem(t, t.pos(2, 0, 0));
        golem.addEffect(poison(600, 0), bob.player());
        golem.addEffect(poison(60, 1), alice.player()); // bob's is hidden under it, and saved with it
        IronGolem loaded = reload(t, golem, tag -> {
            CompoundTag top = savedCauses(t, tag).getList("minecraft:poison", Tag.TAG_COMPOUND).getCompound(0);
            t.assertTrue(top.getCompound("cause").contains("id"), "the poison on top should save who applied it");
            top.getCompound("cause").putString("id", "not a UUID");
        }, IronGolem.class);
        List<EntityDamageEvent> heard = hurts(t, loaded);
        t.assertEquals(Actor.Unknown.INSTANCE, nextPoison(t, heard).getActor(), "the actor of poison whose saved "
                + "cause can't be read (nobody proven: an attribution gap)");
        t.await("the poison on top to run out", () -> loaded.getEffect(MobEffects.POISON) != null
                && loaded.getEffect(MobEffects.POISON).getAmplifier() == 0, 80);
        assertFor(t, bob, nextPoison(t, heard), "the poison hidden under one whose saved cause can't be read");
    }

    @HadronTest
    @Covers(EntityRegainHealthEvent.class)
    void aTotemsRegenerationActsForItsHolder(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        pig.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        pig.setHealth(1);
        List<EntityRegainHealthEvent> heals = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityRegainHealthEvent e) {
                if (e.getEntity() == pig) heals.add(e);
            }
        });
        // Alice's fire kills the pig, so its totem is used while her fire acts, with her on the stack.
        Actors.acting(new Actor.Player(alice.player()), () -> pig.igniteForSeconds(30));
        t.await("the pig's totem to be used", () -> pig.hasEffect(MobEffects.REGENERATION), 40);
        t.assertTrue(pig.getOffhandItem().isEmpty(), "the pig's totem should be used up");
        heals.clear();
        t.await("the totem's regeneration to heal the pig", () -> !heals.isEmpty(), 100);
        Actor healer = heals.get(0).getActor();
        t.assertTrue(healer instanceof Actor.EntityActor own && own.entity() == pig, "the regeneration of the pig's "
                + "own totem should act as the pig, but acted as " + healer);
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aModsOwnEffectInstanceIsAppliedAsGivenAndKeepsItsCause(TestContext t) {
        TestPlayer bob = t.player("bob");
        IronGolem golem = golem(t, t.pos(2, 0, 0));
        int[] ticks = {0};
        MobEffectInstance own = new MobEffectInstance(MobEffects.POISON, 400, 0) {
            @Override
            public boolean tick(LivingEntity entity, Runnable onExpirationRunnable) {
                ticks[0]++;
                return super.tick(entity, onExpirationRunnable);
            }
        };
        List<EntityDamageEvent> heard = hurts(t, golem);
        golem.addEffect(own, bob.player());
        assertFor(t, bob, nextPoison(t, heard), "the poison of a mod's own effect instance");
        t.assertTrue(golem.getEffect(MobEffects.POISON) == own, "the golem's poison should be the instance the mod "
                + "applied, but is " + golem.getEffect(MobEffects.POISON));
        t.assertTrue(ticks[0] > 0, "the tick the mod's effect instance overrides should have run");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    @Covers(value = EntityDamageEvent.class, method = "getAppliedAt")
    void fireLitAgainByWhatLitItIsNoNewIgnition(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        BlockPos litAt = t.pos(2, 0, 0);
        BlockPos movedTo = t.pos(-2, 0, 0);
        Pig pig = spawn(t, EntityType.PIG, litAt);
        List<EntityDamageEvent> heard = hurts(t, pig);
        Actors.acting(new Actor.Player(alice.player()), () -> pig.igniteForTicks(40));
        // As lava does every tick: nature lights it, then lights it again where it stands. The second is no new
        // ignition, so taking the burning time back to alice's in the same tick still gives her the credit back.
        Actors.acting(Actor.Nature.INSTANCE, () -> pig.igniteForTicks(100));
        Actors.acting(Actor.Nature.INSTANCE, () -> pig.igniteForTicks(200));
        pig.setRemainingFireTicks(40);
        assertFor(t, alice, nextBurn(t, heard), "burning after nature's fire, lit twice, was taken back");

        Actors.acting(Actor.Nature.INSTANCE, () -> pig.igniteForTicks(300));
        Actors.acting(new Actor.Player(bob.player()), () -> pig.igniteForTicks(400));
        assertFor(t, bob, nextBurn(t, heard), "burning after bob lit what nature had lit");

        Actors.acting(Actor.Nature.INSTANCE, () -> pig.igniteForTicks(500));
        pig.setPos(movedTo.getX() + 0.5, movedTo.getY(), movedTo.getZ() + 0.5);
        Actors.acting(Actor.Nature.INSTANCE, () -> pig.igniteForTicks(600));
        EntityDamageEvent burn = nextBurn(t, heard);
        t.assertEquals(Actor.Nature.INSTANCE, burn.getActor(), "the actor of burning that nature lit");
        t.assertEquals(Optional.of(Place.Spot.at(t.level(), movedTo)), burn.getAppliedAt(),
                "where nature lit the pig again, in another block");
    }
}
