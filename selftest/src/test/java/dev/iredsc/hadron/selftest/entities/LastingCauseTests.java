package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

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
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/**
 * An effect and an entity's fire keep who caused them and where, saved with the entity, and act for that cause on
 * every tick after (docs/events.md, "Attribution reaches every tick").
 */
public final class LastingCauseTests {
    /** The damage poison deals: NeoForge gives it its own type, rather than vanilla's magic. */
    private static final ResourceKey<DamageType> POISON = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath("neoforge", "poison"));

    /** Every damage event for {@code victim} from now on. */
    private static List<EntityDamageEvent> hurts(TestContext t, Entity victim) {
        List<EntityDamageEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDamageEvent e) {
                if (e.getEntity() == victim) heard.add(e);
            }
        });
        return heard;
    }

    /** The next damage {@code victim} takes from poison, after whatever {@code heard} holds now. */
    private static EntityDamageEvent nextPoison(TestContext t, List<EntityDamageEvent> heard) {
        return nextPoison(t, heard, 60);
    }

    private static EntityDamageEvent nextPoison(TestContext t, List<EntityDamageEvent> heard, int ticks) {
        heard.clear();
        t.await("the next poison damage", () -> heard.stream().anyMatch(e -> e.getDamageSource().is(POISON)), ticks);
        return heard.stream().filter(e -> e.getDamageSource().is(POISON)).findFirst().orElseThrow();
    }

    private static EntityDamageEvent nextBurn(TestContext t, List<EntityDamageEvent> heard) {
        heard.clear();
        t.await("the next burning damage", () -> heard.stream().anyMatch(e -> e.getDamageSource()
                .is(DamageTypeTags.IS_FIRE)), 60);
        return heard.stream().filter(e -> e.getDamageSource().is(DamageTypeTags.IS_FIRE)).findFirst().orElseThrow();
    }

    /** A sturdy victim: poison never takes a golem's 100 health down to the 1 it stops at. */
    private static IronGolem golem(TestContext t, BlockPos at) {
        return spawn(t, EntityType.IRON_GOLEM, at);
    }

    private static MobEffectInstance poison(int ticks, int amplifier) {
        return new MobEffectInstance(MobEffects.POISON, ticks, amplifier);
    }

    private static void assertFor(TestContext t, TestPlayer who, EntityDamageEvent e, String what) {
        t.assertEquals(Optional.of(who.uuid()), e.getActor().actingFor(), what + " acts for");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    @Covers(value = Actor.class, method = "actingFor")
    void aPoisonerWhoLeftIsRemembered(TestContext t) {
        TestPlayer alice = t.player("alice"); // keeps the world ticking once bob leaves
        TestPlayer bob = t.player("bob");
        UUID bobId = bob.uuid();
        IronGolem golem = golem(t, t.pos(2, 0, 0));
        List<EntityDamageEvent> heard = hurts(t, golem);
        golem.addEffect(poison(400, 0), bob.player());
        assertFor(t, bob, nextPoison(t, heard), "poison while bob is online");
        bob.disconnect();
        EntityDamageEvent after = nextPoison(t, heard);
        t.assertTrue(after.getActor() instanceof Actor.Remembered, "the poison of a player who left should act as "
                + "Actor.Remembered, but acted as " + after.getActor());
        Actor.Remembered remembered = (Actor.Remembered) after.getActor();
        t.assertEquals(BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER), remembered.kind(),
                "what the remembered actor was");
        t.assertEquals(Optional.of(bobId), remembered.id(), "which player the remembered actor was");
        t.assertEquals(Optional.of(bobId), remembered.actingFor(), "who the remembered actor acts for");
        t.assertEquals(Optional.empty(), after.getActingPlayer(), "the acting player (bob is offline)");
        t.assertTrue(alice.player().isAlive(), "alice stays");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aSpentCloudsPoisonActsForWhoeverThrewIt(TestContext t) {
        TestPlayer bob = t.player("bob");
        IronGolem golem = golem(t, t.pos(2, 0, 0));
        AreaEffectCloud cloud = new AreaEffectCloud(t.level(), golem.getX(), golem.getY(), golem.getZ());
        cloud.setOwner(bob.player());
        cloud.setRadius(3);
        cloud.setWaitTime(0);
        cloud.setPotionContents(new PotionContents(Potions.STRONG_POISON));
        // As a lingering potion's cloud appears: while the potion bob threw acts for him.
        Actors.acting(new Actor.Player(bob.player()), () -> t.level().addFreshEntity(cloud));
        t.await("the cloud to poison the golem", () -> golem.hasEffect(MobEffects.POISON), 100);
        List<AreaEffectCloud> clouds = t.level().getEntitiesOfClass(AreaEffectCloud.class,
                golem.getBoundingBox().inflate(8));
        t.assertTrue(!clouds.isEmpty(), "the lingering potion should have left a cloud");
        UUID cloudId = clouds.get(0).getUUID();
        clouds.forEach(Entity::discard);
        List<EntityDamageEvent> heard = hurts(t, golem);
        EntityDamageEvent tick = nextPoison(t, heard);
        t.assertTrue(tick.getActor() instanceof Actor.Remembered r && r.id().equals(Optional.of(cloudId))
                        && r.kind().equals(BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.AREA_EFFECT_CLOUD)),
                "the poison of a spent cloud should act as the remembered cloud, but acted as " + tick.getActor());
        assertFor(t, bob, tick, "the spent cloud's poison");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aWeakerOrShorterPoisonLeavesTheCreditWhereItWas(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        IronGolem golem = golem(t, t.pos(2, 0, 0));
        List<EntityDamageEvent> heard = hurts(t, golem);
        golem.addEffect(poison(400, 1), bob.player());
        golem.addEffect(poison(800, 0), alice.player()); // weaker, though longer: hidden, not replacing
        golem.addEffect(poison(100, 1), alice.player()); // as strong, but shorter: changes nothing
        assertFor(t, bob, nextPoison(t, heard), "poison after weaker and shorter ones");
        golem.addEffect(poison(900, 1), alice.player()); // as strong and longer: replaces it
        assertFor(t, alice, nextPoison(t, heard), "poison after a longer one as strong");
        golem.addEffect(poison(100, 2), bob.player()); // stronger, though shorter: replaces it
        assertFor(t, bob, nextPoison(t, heard), "poison after a stronger one");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aHiddenPoisonResumesWithItsOwnCause(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        IronGolem golem = golem(t, t.pos(2, 0, 0));
        List<EntityDamageEvent> heard = hurts(t, golem);
        golem.addEffect(poison(600, 0), bob.player());
        golem.addEffect(poison(40, 1), alice.player()); // stronger and shorter: bob's waits underneath
        assertFor(t, alice, nextPoison(t, heard), "the stronger poison on top");
        t.await("alice's poison to run out", () -> golem.getEffect(MobEffects.POISON) != null
                && golem.getEffect(MobEffects.POISON).getAmplifier() == 0, 60);
        assertFor(t, bob, nextPoison(t, heard), "the hidden poison once it resumes");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void ownPoisonActsForItself(TestContext t) {
        TestPlayer carol = t.player("carol");
        List<EntityDamageEvent> heard = hurts(t, carol.player());
        carol.player().addEffect(poison(400, 0), carol.player()); // her own splash potion passes herself
        // A player who just joined can't be hurt for 60 ticks.
        EntityDamageEvent tick = nextPoison(t, heard, 120);
        t.assertTrue(tick.getActor() instanceof Actor.Player p && p.player() == carol.player(),
                "carol's own poison should act as carol, but acted as " + tick.getActor());
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void anEffectNobodyAppliedActsForNature(TestContext t) {
        t.player("alice");
        IronGolem golem = golem(t, t.pos(2, 0, 0));
        List<EntityDamageEvent> heard = hurts(t, golem);
        Actors.acting(Actor.Nature.INSTANCE, () -> golem.addEffect(poison(400, 0)));
        EntityDamageEvent tick = nextPoison(t, heard);
        t.assertEquals(Actor.Nature.INSTANCE, tick.getActor(), "the actor of poison nobody applied (the golem itself, "
                + "ticking, is not its cause)");
        t.assertEquals(Optional.of(Place.Spot.at(t.level(), golem.blockPosition())), tick.getAppliedAt(),
                "where poison nobody applied was applied");
    }

    @HadronTest
    @Covers(value = EntityDamageEvent.class, method = "getAppliedAt")
    void poisonSaysWhereItWasApplied(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos poisonedAt = t.pos(2, 0, 0);
        IronGolem golem = golem(t, poisonedAt);
        List<EntityDamageEvent> heard = hurts(t, golem);
        golem.addEffect(poison(400, 0), bob.player());
        BlockPos now = t.pos(-3, 0, 2);
        golem.setPos(now.getX() + 0.5, now.getY(), now.getZ() + 0.5);
        t.tick();
        t.assertEquals(now, golem.blockPosition(), "where the golem was moved");
        EntityDamageEvent tick = nextPoison(t, heard);
        t.assertEquals(Optional.of(Place.Spot.at(t.level(), poisonedAt)), tick.getAppliedAt(),
                "where the poison was applied, not where the golem is now");
        heard.clear();
        bob.attack(golem);
        t.await("bob's hit", () -> !heard.isEmpty(), 20);
        t.assertEquals(Optional.empty(), heard.get(0).getAppliedAt(), "where a plain hit was applied");
    }

    @HadronTest
    @Covers(EntityRegainHealthEvent.class)
    @Covers(value = EntityRegainHealthEvent.class, method = "getAppliedAt")
    void regenerationActsForItsGiverAndSaysWhere(TestContext t) {
        TestPlayer bob = t.player("bob");
        IronGolem golem = golem(t, t.pos(2, 0, 0));
        golem.setHealth(50);
        List<EntityRegainHealthEvent> heals = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityRegainHealthEvent e) {
                if (e.getEntity() == golem) heals.add(e);
            }
        });
        golem.heal(1);
        t.assertEquals(1, heals.size(), "heal events for a direct heal");
        t.assertEquals(Optional.empty(), heals.get(0).getAppliedAt(), "where a direct heal was applied");
        heals.clear();
        golem.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1), bob.player());
        t.await("regeneration to heal the golem", () -> !heals.isEmpty(), 60);
        t.assertEquals(Optional.of(bob.uuid()), heals.get(0).getActor().actingFor(), "regeneration acts for");
        t.assertEquals(Optional.of(Place.Spot.at(t.level(), t.pos(2, 0, 0))), heals.get(0).getAppliedAt(),
                "where the regeneration was applied");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aShorterIgnitionLeavesTheFiresCreditWhereItWas(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        BlockPos litAt = t.pos(2, 0, 0);
        Pig pig = spawn(t, EntityType.PIG, litAt);
        List<EntityDamageEvent> heard = hurts(t, pig);
        Actors.acting(new Actor.Player(bob.player()), () -> pig.igniteForSeconds(10));
        Actors.acting(new Actor.Player(alice.player()), () -> pig.igniteForSeconds(2));
        int shorter = pig.getRemainingFireTicks() - 5;
        Actors.acting(new Actor.Player(alice.player()), () -> pig.setRemainingFireTicks(shorter));
        EntityDamageEvent burn = nextBurn(t, heard);
        assertFor(t, bob, burn, "burning after shorter ignitions");
        t.assertEquals(Optional.of(Place.Spot.at(t.level(), litAt)), burn.getAppliedAt(), "where the pig was set alight");
        Actors.acting(new Actor.Player(alice.player()), () -> pig.igniteForSeconds(30));
        assertFor(t, alice, nextBurn(t, heard), "burning after a longer ignition");
    }

    /** Saves {@code entity}, removes it, and adds what loads back from {@code edit}ed save data. */
    private static <T extends Entity> T reload(TestContext t, T entity, Consumer<CompoundTag> edit, Class<T> type) {
        CompoundTag saved = new CompoundTag();
        t.assertTrue(entity.save(saved), "the entity should save");
        entity.discard();
        edit.accept(saved);
        Entity loaded = EntityType.loadEntityRecursive(saved, t.level(), e -> e);
        t.assertTrue(type.isInstance(loaded), "the saved entity should load as a " + type.getSimpleName() + ", but "
                + "loaded " + loaded);
        t.level().addFreshEntity(loaded);
        return type.cast(loaded);
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void effectCausesSurviveASave(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        BlockPos poisonedAt = t.pos(2, 0, 0);
        IronGolem golem = golem(t, poisonedAt);
        golem.addEffect(poison(600, 0), bob.player());
        golem.addEffect(poison(60, 1), alice.player()); // bob's is hidden under it, and saved with it
        IronGolem loaded = reload(t, golem, tag -> {}, IronGolem.class);
        List<EntityDamageEvent> heard = hurts(t, loaded);
        EntityDamageEvent top = nextPoison(t, heard);
        assertFor(t, alice, top, "the reloaded poison on top");
        t.assertEquals(Optional.of(Place.Spot.at(t.level(), poisonedAt)), top.getAppliedAt(),
                "where the reloaded poison was applied");
        t.await("alice's poison to run out", () -> loaded.getEffect(MobEffects.POISON) != null
                && loaded.getEffect(MobEffects.POISON).getAmplifier() == 0, 80);
        assertFor(t, bob, nextPoison(t, heard), "the reloaded hidden poison once it resumes");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void anEffectSavedWithoutItsCauseLoadsAsBefore(TestContext t) {
        TestPlayer bob = t.player("bob");
        IronGolem golem = golem(t, t.pos(2, 0, 0));
        golem.addEffect(poison(600, 0), bob.player());
        IronGolem loaded = reload(t, golem, tag -> {
            ListTag effects = tag.getList("active_effects", Tag.TAG_COMPOUND);
            t.assertTrue(!effects.isEmpty(), "the golem should save its poison");
            for (int i = 0; i < effects.size(); i++) {
                t.assertTrue(effects.getCompound(i).contains("hadron:causes"), "the poison should save its cause");
                effects.getCompound(i).remove("hadron:causes");
            }
        }, IronGolem.class);
        t.assertTrue(loaded.hasEffect(MobEffects.POISON), "the poison should load without its cause");
        List<EntityDamageEvent> heard = hurts(t, loaded);
        EntityDamageEvent tick = nextPoison(t, heard);
        t.assertEquals(Actor.Nature.INSTANCE, tick.getActor(), "the actor of poison saved before causes were kept");
        t.assertEquals(Optional.of(Place.Unknown.anywhere(Place.Unknown.Reason.UNSEEN_ORIGIN)), tick.getAppliedAt(),
                "where poison saved before causes were kept was applied");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aFiresCauseSurvivesASave(TestContext t) {
        TestPlayer bob = t.player("bob");
        BlockPos litAt = t.pos(2, 0, 0);
        Pig pig = spawn(t, EntityType.PIG, litAt);
        Actors.acting(new Actor.Player(bob.player()), () -> pig.igniteForSeconds(10));
        Pig loaded = reload(t, pig, tag -> {}, Pig.class);
        List<EntityDamageEvent> heard = hurts(t, loaded);
        EntityDamageEvent burn = nextBurn(t, heard);
        assertFor(t, bob, burn, "the reloaded pig's burning");
        t.assertEquals(Optional.of(Place.Spot.at(t.level(), litAt)), burn.getAppliedAt(),
                "where the reloaded pig was set alight");

        Pig old = spawn(t, EntityType.PIG, t.pos(-2, 0, 0));
        Actors.acting(new Actor.Player(bob.player()), () -> old.igniteForSeconds(10));
        Pig oldLoaded = reload(t, old, tag -> {
            CompoundTag attachments = tag.getCompound("neoforge:attachments");
            t.assertTrue(attachments.contains("hadron:fire_cause"), "the pig should save who set it alight");
            attachments.remove("hadron:fire_cause");
        }, Pig.class);
        List<EntityDamageEvent> oldHeard = hurts(t, oldLoaded);
        EntityDamageEvent oldBurn = nextBurn(t, oldHeard);
        t.assertEquals(Actor.Nature.INSTANCE, oldBurn.getActor(), "the actor of fire saved before causes were kept");
        t.assertEquals(Optional.of(Place.Unknown.anywhere(Place.Unknown.Reason.UNSEEN_ORIGIN)),
                oldBurn.getAppliedAt(), "where fire saved before causes were kept was set");
    }

}
