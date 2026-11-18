package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.create;
import static dev.iredsc.hadron.selftest.entities.Fixtures.ready;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.golem;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.hurts;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.nextPoison;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;

/**
 * An effect applied with no source is nature's when only its own entity is on the stack (a wither rose it stands in),
 * and the entity's own when it gave it to itself (a potion it drinks, food it eats) (docs/events.md, "Attribution
 * reaches every tick").
 */
public final class LastingCauseOwnTests {
    private static EntityDamageEvent nextWither(TestContext t, List<EntityDamageEvent> heard) {
        heard.clear();
        t.await("the next wither damage", () -> heard.stream().anyMatch(e -> e.getDamageSource()
                .is(DamageTypes.WITHER)), 40);
        return heard.stream().filter(e -> e.getDamageSource().is(DamageTypes.WITHER)).findFirst().orElseThrow();
    }

    private static void assertNatureAt(TestContext t, BlockPos at, EntityDamageEvent e, String what) {
        t.assertEquals(Actor.Nature.INSTANCE, e.getActor(), "the actor of " + what);
        t.assertEquals(Optional.of(Place.Spot.at(t.level(), at)), e.getAppliedAt(), "where " + what + " was applied");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aWitherRoseWithersAMobForNature(TestContext t) {
        t.player("alice");
        ready(t);
        BlockPos rose = t.pos(2, 0, 0);
        t.setBlock(rose, Blocks.WITHER_ROSE);
        // With its AI on: a mob is checked against the blocks it stands in only while it moves itself.
        Pig pig = create(t, EntityType.PIG, rose);
        List<EntityDamageEvent> heard = hurts(t, pig);
        t.level().addFreshEntity(pig);
        // The rose withers the pig during the pig's own tick, when the pig is what is on the stack.
        assertNatureAt(t, rose, nextWither(t, heard), "a wither rose's wither on a pig");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aWitherRoseWithersAPlayerForNature(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        t.tick(60); // a player who just joined can't be hurt
        BlockPos rose = t.pos(2, 0, 0);
        t.setBlock(rose, Blocks.WITHER_ROSE);
        List<EntityDamageEvent> heard = hurts(t, alice.player());
        // The rose withers her as her own step is handled, when she is what is on the stack.
        alice.moveTo(rose);
        t.assertTrue(alice.player().hasEffect(MobEffects.WITHER), "the wither rose alice stepped into should wither "
                + "her");
        assertNatureAt(t, rose, nextWither(t, heard), "a wither rose's wither on a player");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aPotionAPlayerDrinksActsForThem(TestContext t) {
        TestPlayer dave = t.player("dave");
        ready(t);
        List<EntityDamageEvent> heard = hurts(t, dave.player());
        dave.hold(PotionContents.createItemStack(Items.POTION, Potions.LONG_POISON));
        dave.useItem();
        t.await("dave to finish his potion", () -> dave.player().hasEffect(MobEffects.POISON), 60);
        EntityDamageEvent tick = nextPoison(t, heard);
        t.assertTrue(tick.getActor() instanceof Actor.Player p && p.player() == dave.player(),
                "the poison dave drank should act as dave, but acted as " + tick.getActor());
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void poisonousFoodAPlayerEatsActsForThem(TestContext t) {
        TestPlayer erin = t.player("erin");
        ready(t);
        erin.player().getFoodData().setFoodLevel(10); // hungry enough to eat
        List<EntityDamageEvent> heard = hurts(t, erin.player());
        erin.hold(new ItemStack(Items.PUFFERFISH));
        erin.useItem();
        t.await("erin to finish her pufferfish", () -> erin.player().hasEffect(MobEffects.POISON), 60);
        EntityDamageEvent tick = nextPoison(t, heard);
        t.assertTrue(tick.getActor() instanceof Actor.Player p && p.player() == erin.player(),
                "the poison of the pufferfish erin ate should act as erin, but acted as " + tick.getActor());
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aPotionAMobDrinksInItsOwnTickActsForIt(TestContext t) {
        t.player("alice");
        IronGolem golem = golem(t, t.pos(2, 0, 0));
        List<EntityDamageEvent> heard = hurts(t, golem);
        ItemStack potion = PotionContents.createItemStack(Items.POTION, Potions.LONG_POISON);
        // As a mob drinking in its own tick (a wandering trader): it is already what is on the stack.
        Actors.acting(new Actor.EntityActor(golem, Attribution.none()),
                () -> Items.POTION.finishUsingItem(potion, t.level(), golem));
        EntityDamageEvent tick = nextPoison(t, heard);
        t.assertTrue(tick.getActor() instanceof Actor.EntityActor own && own.entity() == golem,
                "the poison the golem drank should act as the golem, but acted as " + tick.getActor());
    }
}
