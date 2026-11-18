package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.assertFor;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.golem;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.hurts;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.nextPoison;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.poison;
import static dev.iredsc.hadron.selftest.entities.LastingCauseTests.reload;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.animal.IronGolem;

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
}
