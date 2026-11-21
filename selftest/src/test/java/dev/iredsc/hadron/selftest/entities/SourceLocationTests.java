package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.ready;
import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Where a hit set out from ({@code EntityDamageEvent.getSourceLocation()}), for every kind of hit: a blow, a
 * projectile however far its shooter has gone since, a mob's beam or fangs, a blast, a machine, and harm nothing sent.
 * Each scene puts the victim, the attacker now and where the hit set out in different blocks, so a getter answering
 * any of the others fails.
 */
public final class SourceLocationTests {
    private static final String ATTACHMENTS = "neoforge:attachments";
    private static final String ORIGIN = "hadron:origin";

    private static Place spot(TestContext t, Vec3 at) {
        return Place.Spot.at(t.level(), at);
    }

    private static Optional<Place> unseen() {
        return Optional.of(Place.Unknown.anywhere(Place.Unknown.Reason.UNSEEN_ORIGIN));
    }

    /** Every damage event heard from now on for {@code victim}. */
    private static List<EntityDamageEvent> hurts(TestContext t, Entity victim) {
        List<EntityDamageEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDamageEvent e) {
                if (e.getEntity() == victim) events.add(e);
            }
        });
        return events;
    }

    private static EntityDamageEvent first(TestContext t, List<EntityDamageEvent> events, String what) {
        t.await(what, () -> !events.isEmpty(), 80);
        return events.get(0);
    }

    /**
     * A slow arrow from {@code owner}, launched six blocks short of the pig and flying straight at it for about
     * three-quarters of a second.
     */
    private static Arrow slowArrow(TestContext t, LivingEntity owner, Pig pig) {
        Arrow arrow = new Arrow(t.level(), owner, new ItemStack(Items.ARROW), null);
        aim(arrow, pig);
        return arrow;
    }

    private static void aim(AbstractArrow arrow, Pig pig) {
        arrow.moveTo(pig.getX(), pig.getY() + 0.45, pig.getZ() - 6, 0.0F, 0.0F);
        arrow.setNoGravity(true);
        arrow.shoot(0, 0, 1, 0.4F, 0.0F);
    }

    /** The scene for a shot: the shooter stands where the shot is launched, then walks ten blocks away. */
    private static Place launchAndWalkAway(TestContext t, TestPlayer shooter, AbstractArrow shot) {
        Vec3 launched = shot.position();
        shooter.moveTo(BlockPos.containing(launched));
        t.level().addFreshEntity(shot);
        shooter.moveTo(t.pos(-8, 0, -8));
        return spot(t, launched);
    }

    @HadronTest
    @Covers(value = EntityDamageEvent.class, method = "getSourceLocation")
    void aSwordBlowSetsOutFromTheAttackersBlock(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.IRON_SWORD));
        List<EntityDamageEvent> events = hurts(t, pig);
        t.tick(10);
        alice.attack(pig);
        Place attacker = spot(t, alice.player().position());
        t.assertFalse(attacker.equals(spot(t, pig.position())), "the scene: alice and the pig in different blocks");
        t.assertEquals(Optional.of(attacker), first(t, events, "the blow").getSourceLocation(),
                "where a sword blow set out from");
    }

    @HadronTest
    @Covers(value = EntityDamageEvent.class, method = "getSourceLocation")
    void aPlayersArrowSetsOutFromWhereItWasShotAfterTheShooterWalksAway(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 4));
        List<EntityDamageEvent> events = hurts(t, pig);
        Place launched = launchAndWalkAway(t, alice, slowArrow(t, alice.player(), pig));
        EntityDamageEvent hit = first(t, events, "the arrow to hit the pig");
        Place shooterNow = spot(t, alice.player().position());
        t.assertFalse(launched.equals(shooterNow), "the scene: alice walked away from where she shot");
        t.assertEquals(Optional.of(launched), hit.getSourceLocation(),
                "where an arrow set out from, its shooter ten blocks away by the time it hit (she is at "
                        + shooterNow + ")");
    }

    @HadronTest
    @Covers(value = EntityDamageEvent.class, method = "getSourceLocation")
    void aPlayersArrowSetsOutFromWhereItWasShotAfterTheShooterLogsOff(TestContext t) {
        TestPlayer alice = t.player("alice");
        t.player("bob"); // keeps the world ticking once alice leaves
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 4));
        List<EntityDamageEvent> events = hurts(t, pig);
        Place launched = launchAndWalkAway(t, alice, slowArrow(t, alice.player(), pig));
        alice.disconnect();
        t.assertEquals(Optional.of(launched), first(t, events, "the arrow to hit the pig").getSourceLocation(),
                "where an arrow set out from, its shooter offline by the time it hit");
    }

    @HadronTest
    @Covers(value = EntityDamageEvent.class, method = "getSourceLocation")
    void aSkeletonsArrowSetsOutFromWhereItWasShotAfterTheSkeletonIsGone(TestContext t) {
        t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 4));
        Skeleton skeleton = spawn(t, EntityType.SKELETON, t.pos(3, 0, -2));
        List<EntityDamageEvent> events = hurts(t, pig);
        Arrow arrow = slowArrow(t, skeleton, pig);
        Place launched = spot(t, arrow.position());
        t.level().addFreshEntity(arrow);
        skeleton.discard();
        t.assertEquals(Optional.of(launched), first(t, events, "the arrow to hit the pig").getSourceLocation(),
                "where a skeleton's arrow set out from, the skeleton removed by the time it hit");
    }

    @HadronTest
    @Covers(value = EntityDamageEvent.class, method = "getSourceLocation")
    void aThrownTridentSetsOutFromWhereItWasThrown(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 4));
        List<EntityDamageEvent> events = hurts(t, pig);
        ThrownTrident trident = new ThrownTrident(t.level(), alice.player(), new ItemStack(Items.TRIDENT));
        aim(trident, pig);
        Place thrown = launchAndWalkAway(t, alice, trident);
        t.assertEquals(Optional.of(thrown), first(t, events, "the trident to hit the pig").getSourceLocation(),
                "where a thrown trident set out from");
    }

    @HadronTest
    @Covers(value = EntityDamageEvent.class, method = "getSourceLocation")
    void anArrowNothingSawEnterTheWorldSetsOutFromAnUnknownPlace(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 4));
        List<EntityDamageEvent> events = hurts(t, pig);
        CompoundTag saved = new CompoundTag();
        t.assertTrue(slowArrow(t, alice.player(), pig).save(saved), "the arrow should save");
        saved.getCompound(ATTACHMENTS).remove(ORIGIN);
        Entity loaded = EntityType.loadEntityRecursive(saved, t.level(), e -> e);
        t.assertTrue(loaded instanceof Arrow, "the saved arrow should load as an arrow, but loaded " + loaded);
        // Joins as a chunk would load it, not as a fresh entity: nothing records where it entered.
        t.assertTrue(t.level().addWithUUID(Objects.requireNonNull(loaded)), "the arrow should join the world");
        t.assertEquals(unseen(), first(t, events, "the arrow to hit the pig").getSourceLocation(),
                "where an arrow with no recorded origin set out from");
    }

    @HadronTest
    @Covers(value = EntityDamageEvent.class, method = "getSourceLocation")
    void aCreepersBlastSetsOutFromWhereItWentOff(TestContext t) {
        t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(4, 0, 4));
        Creeper creeper = spawn(t, EntityType.CREEPER, t.pos(4, 0, 2));
        Place blast = spot(t, creeper.position());
        List<EntityDamageEvent> events = hurts(t, pig);
        creeper.ignite();
        t.assertEquals(Optional.of(blast), first(t, events, "the creeper to blow up").getSourceLocation(),
                "where a creeper's blast set out from");
    }

    @HadronTest
    @Covers(value = EntityDamageEvent.class, method = "getSourceLocation")
    void aBlastWithNoEntitySetsOutFromWhereItWentOff(TestContext t) {
        t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 0));
        List<EntityDamageEvent> events = hurts(t, pig);
        Vec3 bed = Vec3.atCenterOf(t.pos(0, 0, 0));
        pig.hurt(t.level().damageSources().badRespawnPointExplosion(bed), 1f);
        t.assertEquals(Optional.of(spot(t, bed)), first(t, events, "the blast").getSourceLocation(),
                "where a bed's blast in the wrong world set out from");
    }

    @HadronTest
    @Covers(value = EntityDamageEvent.class, method = "getSourceLocation")
    void evokerFangsSetOutFromWhereTheyRose(TestContext t) {
        t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 0));
        Evoker evoker = spawn(t, EntityType.EVOKER, t.pos(-3, 0, 0));
        List<EntityDamageEvent> events = hurts(t, pig);
        // The fangs rise in the next block over, close enough for their bite to reach the pig.
        EvokerFangs fangs = new EvokerFangs(t.level(), pig.getX() + 0.6, pig.getY(), pig.getZ(), 0f, 0, evoker);
        Place rose = spot(t, fangs.position());
        t.assertFalse(rose.equals(spot(t, pig.position())), "the scene: the fangs and the pig in different blocks");
        t.level().addFreshEntity(fangs);
        t.assertEquals(Optional.of(rose), first(t, events, "the fangs to bite").getSourceLocation(),
                "where an evoker's fangs set out from, the evoker three blocks away");
    }

    @HadronTest
    @Covers(value = EntityDamageEvent.class, method = "getSourceLocation")
    void aGuardiansBeamAndAWardensBoomSetOutFromTheMob(TestContext t) {
        t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 0));
        Guardian guardian = spawn(t, EntityType.GUARDIAN, t.pos(-3, 0, 0));
        Warden warden = spawn(t, EntityType.WARDEN, t.pos(3, 0, -5));
        List<EntityDamageEvent> events = hurts(t, pig);
        // The damage each attack deals, as Minecraft builds it: neither is a projectile entity.
        pig.hurt(t.level().damageSources().indirectMagic(guardian, guardian), 1f);
        pig.invulnerableTime = 0;
        pig.hurt(t.level().damageSources().sonicBoom(warden), 1f);
        t.assertEquals(2, events.size(), "the number of hits on the pig");
        t.assertEquals(Optional.of(spot(t, guardian.position())), events.get(0).getSourceLocation(),
                "where a guardian's beam set out from");
        t.assertEquals(Optional.of(spot(t, warden.position())), events.get(1).getSourceLocation(),
                "where a warden's sonic boom set out from");
    }

    @HadronTest
    @Covers(value = EntityDamageEvent.class, method = "getSourceLocation")
    void aMachinesHitNamingNoEntitySetsOutFromTheMachine(TestContext t) {
        TestPlayer bob = t.player("bob");
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 0));
        List<EntityDamageEvent> events = hurts(t, pig);
        BlockPos turret = t.pos(-4, 0, 2);
        Actor machine = new Actor.Machine(t.level(), turret, Attribution.of(bob.uuid()));
        // A turret dealing damage straight from its tick, as an unbridged mod's does, with the machine acting.
        Actors.acting(machine, () -> {
            pig.hurt(t.level().damageSources().generic(), 1f);
        });
        t.assertEquals(Optional.of(Place.Spot.at(t.level(), turret)),
                first(t, events, "the turret's hit").getSourceLocation(), "where a machine's hit set out from");
    }

    @HadronTest
    @Covers(value = EntityDamageEvent.class, method = "getSourceLocation")
    void aFallSetsOutFromNowhere(TestContext t) {
        t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 0));
        List<EntityDamageEvent> events = hurts(t, pig);
        pig.causeFallDamage(10f, 1f, t.level().damageSources().fall());
        EntityDamageEvent fall = first(t, events, "the fall");
        t.assertEquals(Optional.empty(), fall.getSourceLocation(), "where a fall set out from (nothing sent it)");
        t.assertEquals(Optional.empty(), fall.getAppliedAt(), "where a fall was applied");
    }

    @HadronTest
    @Covers(value = EntityDamageEvent.class, method = "getSourceLocation")
    void poisonTickingSaysWhereItWasAppliedNotWhereItSetOut(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 0));
        List<EntityDamageEvent> events = hurts(t, pig);
        pig.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 1), alice.player());
        EntityDamageEvent tick = first(t, events, "the poison to tick");
        t.assertEquals(Optional.empty(), tick.getSourceLocation(), "where a poison tick set out from");
        t.assertTrue(tick.getAppliedAt().isPresent(), "a poison tick says where it was applied");
    }

    @HadronTest
    @Covers(EntityDamageEvent.class)
    void aBridgeMustStateWhereAHitSetOutFromAndNeverNowhere(TestContext t) {
        t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 0));
        Actor nobody = Actor.Nature.INSTANCE;
        try {
            new EntityDamageEvent(pig, t.level().damageSources().generic(), 1f, nobody,
                    Optional.of(new Place.Nowhere(ResourceLocation.fromNamespaceAndPath("hadron", "account"), "bank",
                            Optional.empty())));
            t.fail("a hit set out from Nowhere should be refused");
        } catch (IllegalArgumentException e) {
            t.assertTrue(String.valueOf(e.getMessage()).contains("UNSEEN_ORIGIN"),
                    "the refusal should say what to pass instead: " + e.getMessage());
        }
        Place turret = Place.Spot.at(t.level(), t.pos(-4, 0, 2));
        t.assertEquals(Optional.of(turret), new EntityDamageEvent(pig, t.level().damageSources().generic(), 1f, nobody,
                Optional.of(turret)).getSourceLocation(), "the place a bridge stated");
    }
}
