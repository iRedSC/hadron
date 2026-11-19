package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.console;
import static dev.iredsc.hadron.selftest.entities.Fixtures.near;
import static dev.iredsc.hadron.selftest.entities.Fixtures.ready;
import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.entity.EntityMountEvent;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryMoveItemEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.MushroomCow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.entity.vehicle.MinecartHopper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Where an entity entered the world ({@code Actors.origin}, {@code EntityActor.origin()},
 * {@code EntityEvent.getOrigin()}) and who put it there: stated on every path in, and kept through a save, a reload
 * and a change of world.
 */
public final class EntityOriginTests {
    private static final String ATTACHMENTS = "neoforge:attachments";
    private static final String ORIGIN = "hadron:origin";

    private static Place.Spot spot(TestContext t, BlockPos pos) {
        return Place.Spot.at(t.level(), pos);
    }

    private static Place unseen() {
        return Place.Unknown.anywhere(Place.Unknown.Reason.UNSEEN_ORIGIN);
    }

    /** A chest of diamonds above {@code under}, for a hopper minecart there to empty. */
    private static void chestAbove(TestContext t, BlockPos under) {
        t.setBlock(under.above(), Blocks.CHEST);
        ((ChestBlockEntity) t.level().getBlockEntity(under.above())).setItem(0, new ItemStack(Items.DIAMOND, 8));
    }

    /** Every item move heard from now on that {@code cart} makes. */
    private static List<InventoryMoveItemEvent> hearMoves(TestContext t) {
        List<InventoryMoveItemEvent> moves = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(InventoryMoveItemEvent e) {
                moves.add(e);
            }
        });
        return moves;
    }

    private static Actor.EntityActor cartActor(TestContext t, InventoryMoveItemEvent move, Entity cart) {
        t.assertTrue(move.getActor() instanceof Actor.EntityActor e && e.entity().getUUID().equals(cart.getUUID()),
                "a hopper minecart emptying a chest should act as its own EntityActor, but acted as "
                        + move.getActor());
        return (Actor.EntityActor) move.getActor();
    }

    /** Saves {@code entity}, removes it, and loads it back as its chunk would: not as a fresh entity. */
    private static <T extends Entity> T reload(TestContext t, T entity, Consumer<CompoundTag> edit, Class<T> type) {
        CompoundTag saved = new CompoundTag();
        t.assertTrue(entity.save(saved), "the entity should save");
        entity.discard();
        edit.accept(saved);
        Entity loaded = EntityType.loadEntityRecursive(saved, t.level(), e -> e);
        t.assertTrue(type.isInstance(loaded), "the saved entity should load as a " + type.getSimpleName() + ", but "
                + "loaded " + loaded);
        t.assertTrue(t.level().addWithUUID(loaded), "the loaded entity should join the world");
        return type.cast(loaded);
    }

    @HadronTest
    @Covers(value = Actor.EntityActor.class, method = "origin")
    void aPlacedHopperMinecartActsForItsPlacerFromWhereItWasPlaced(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        BlockPos rail = t.pos(2, 0, 0);
        t.setBlock(rail, Blocks.RAIL);
        chestAbove(t, rail);
        alice.hold(new ItemStack(Items.HOPPER_MINECART));
        alice.useBlock(rail);
        List<MinecartHopper> carts = near(t, MinecartHopper.class);
        t.assertEquals(1, carts.size(), "the number of hopper minecarts alice placed");
        List<InventoryMoveItemEvent> moves = hearMoves(t);
        t.await("the minecart to take an item from the chest", () -> !moves.isEmpty(), 60);
        Actor.EntityActor actor = cartActor(t, moves.get(0), carts.get(0));
        // Nobody drives it and alice has walked off the stack: it still acts for whoever placed it.
        t.assertEquals(Optional.of(alice.uuid()), actor.actingFor(), "who a placed hopper minecart acts for");
        t.assertEquals(spot(t, rail), actor.origin(), "where the placed minecart entered the world");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void anUnownedHopperMinecartStatesWhereItWasSpawnedNotWhereItStands(TestContext t) {
        t.player("alice");
        ready(t);
        BlockPos spawnedAt = t.pos(-3, 0, 2);
        BlockPos under = t.pos(2, 0, 0);
        chestAbove(t, under);
        console(t, "summon minecraft:hopper_minecart " + spawnedAt.getX() + " " + spawnedAt.getY() + " "
                + spawnedAt.getZ());
        List<MinecartHopper> carts = near(t, MinecartHopper.class);
        t.assertEquals(1, carts.size(), "the number of summoned hopper minecarts");
        MinecartHopper cart = carts.get(0);
        t.assertEquals(spot(t, spawnedAt), Actors.origin(cart), "the origin of a minecart just summoned");
        // Rolled in from outside, under somebody's chest.
        cart.teleportTo(under.getX() + 0.5, under.getY(), under.getZ() + 0.5);
        List<InventoryMoveItemEvent> moves = hearMoves(t);
        t.await("the minecart to take an item from the chest", () -> !moves.isEmpty(), 60);
        Actor.EntityActor actor = cartActor(t, moves.get(0), cart);
        t.assertEquals(Optional.empty(), actor.actingFor(), "who a minecart the console summoned acts for");
        t.assertEquals(spot(t, spawnedAt), actor.origin(), "where a minecart now under the chest entered the world");
        t.assertEquals(spot(t, spawnedAt), Actors.origin(cart), "Actors.origin for the same minecart");
    }

    @HadronTest
    @Covers(value = EntityEvent.class, method = "getOrigin")
    void anEventStatesItsEntitysOriginFromItsSpawnOn(TestContext t) {
        t.player("alice");
        ready(t);
        List<Place> atSpawn = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntitySpawnEvent e) {
                if (e.getEntity() instanceof Pig) atSpawn.add(e.getOrigin());
            }
        });
        BlockPos at = t.pos(2, 0, 1);
        Pig pig = spawn(t, EntityType.PIG, at);
        t.assertEquals(List.of(spot(t, at)), atSpawn, "getOrigin() in the pig's own spawn event");
        pig.teleportTo(at.getX() - 3.5, at.getY(), at.getZ() + 0.5);
        t.assertEquals(spot(t, at), Actors.origin(pig), "the pig's origin once it has moved");
    }

    /** A plugin that relocates spawns: every hopper minecart spawning from now on is moved to {@code to}. */
    private static void moveSpawningCarts(TestContext t, BlockPos to) {
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntitySpawnEvent e) {
                if (e.getEntity() instanceof MinecartHopper) {
                    e.getEntity().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
                }
            }
        });
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void aSpawnAListenerMovesEntersTheWorldWhereItWasMovedTo(TestContext t) {
        t.player("alice");
        ready(t);
        BlockPos moved = t.pos(-3, 0, 2);
        moveSpawningCarts(t, moved);
        MinecartHopper cart = spawn(t, EntityType.HOPPER_MINECART, t.pos(2, 0, 0));
        t.assertEquals(moved, cart.blockPosition(), "the block the minecart entered the world in, once moved");
        t.assertEquals(spot(t, moved), Actors.origin(cart), "the origin of a minecart a spawn listener moved");
        MinecartHopper loaded = reload(t, cart, tag -> {}, MinecartHopper.class);
        t.assertEquals(spot(t, moved), Actors.origin(loaded), "the moved minecart's origin after a save and reload");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void aMovedSpawnAndTheCompanionItsListenerSpawnsEachEnterWhereTheyStand(TestContext t) {
        t.player("alice");
        ready(t);
        BlockPos moved = t.pos(-3, 0, 2);
        BlockPos beside = t.pos(3, 0, -2);
        List<Pig> companions = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntitySpawnEvent e) {
                if (!(e.getEntity() instanceof MinecartHopper)) return;
                e.getEntity().moveTo(moved.getX() + 0.5, moved.getY(), moved.getZ() + 0.5);
                companions.add(spawn(t, EntityType.PIG, beside));
            }
        });
        MinecartHopper cart = spawn(t, EntityType.HOPPER_MINECART, t.pos(2, 0, 0));
        t.assertEquals(1, companions.size(), "the number of companions the spawn listener spawned");
        t.assertEquals(spot(t, moved), Actors.origin(cart), "the origin of a minecart its spawn listener moved "
                + "before spawning a companion");
        t.assertEquals(spot(t, beside), Actors.origin(companions.get(0)), "the origin of the companion spawned "
                + "from inside the minecart's spawn event");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void originAndPlacerSurviveASaveAndReload(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        BlockPos rail = t.pos(2, 0, 0);
        t.setBlock(rail, Blocks.RAIL);
        alice.hold(new ItemStack(Items.HOPPER_MINECART));
        alice.useBlock(rail);
        MinecartHopper cart = near(t, MinecartHopper.class).get(0);
        BlockPos under = t.pos(-2, 0, 2);
        chestAbove(t, under);
        cart.teleportTo(under.getX() + 0.5, under.getY(), under.getZ() + 0.5);
        // Its chunk unloading and loading again, or a restart: a new object read from what was saved.
        MinecartHopper loaded = reload(t, cart, tag -> {}, MinecartHopper.class);
        t.assertEquals(spot(t, rail), Actors.origin(loaded), "the reloaded minecart's origin");
        List<InventoryMoveItemEvent> moves = hearMoves(t);
        t.await("the reloaded minecart to take an item from the chest", () -> !moves.isEmpty(), 60);
        Actor.EntityActor actor = cartActor(t, moves.get(0), loaded);
        t.assertEquals(Optional.of(alice.uuid()), actor.actingFor(), "who the reloaded minecart acts for");
        t.assertEquals(spot(t, rail), actor.origin(), "where the reloaded minecart entered the world");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void originAndPlacerSurviveAChangeOfWorld(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        ServerLevel nether = t.server().getLevel(Level.NETHER);
        t.assertTrue(nether != null, "the test server has a Nether");
        BlockPos rail = t.pos(2, 0, 0);
        t.setBlock(rail, Blocks.RAIL);
        alice.hold(new ItemStack(Items.HOPPER_MINECART));
        alice.useBlock(rail);
        MinecartHopper cart = near(t, MinecartHopper.class).get(0);
        nether.setChunkForced(0, 0, true);
        try {
            nether.getChunk(0, 0);
            Entity away = cart.changeDimension(new DimensionTransition(nether, new Vec3(0.5, 100, 0.5), Vec3.ZERO,
                    0, 0, DimensionTransition.DO_NOTHING));
            t.assertTrue(away != null && away != cart, "the minecart went to the Nether as a new entity");
            // Still the world and block it was placed in, not the Nether it is in now.
            t.assertEquals(spot(t, rail), Actors.origin(away), "the origin of a minecart gone through a portal");
            t.assertEquals(Optional.of(alice.uuid()), Actors.remembered(away).actingFor(),
                    "who a minecart gone through a portal acts for");
            away.discard();
        } finally {
            nether.setChunkForced(0, 0, false);
        }
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void anEntitySavedBeforeOriginsWereKeptHasAnUnseenOrigin(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        Pig old = reload(t, pig, tag -> tag.getCompound(ATTACHMENTS).remove(ORIGIN), Pig.class);
        t.assertEquals(unseen(), Actors.origin(old), "the origin of a pig saved without one");
        t.assertEquals(unseen(), Actors.origin(alice.player()), "a player's origin, which nothing records");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void anOriginThatCannotBeReadIsUnseenAndTakesNothingElseWithIt(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        Actors.remember(pig, Attribution.of(alice.uuid()));
        Pig loaded = reload(t, pig, tag -> tag.getCompound(ATTACHMENTS).putString(ORIGIN, "not a place"), Pig.class);
        t.assertEquals(unseen(), Actors.origin(loaded), "the origin of a pig whose saved origin can't be read");
        t.assertEquals(Optional.of(alice.uuid()), Actors.remembered(loaded).actingFor(),
                "who is behind the pig, saved beside the unreadable origin");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void anEntityBuiltFromSavedDataEntersWhereItIsPutBackIn(TestContext t) {
        t.player("alice");
        ready(t);
        BlockPos first = t.pos(2, 0, 0);
        BlockPos second = t.pos(-3, 0, 1);
        Pig pig = spawn(t, EntityType.PIG, first);
        // A structure block's copy, a mob jar's contents: saved data, origin and all, spawned again elsewhere.
        CompoundTag saved = new CompoundTag();
        pig.save(saved);
        pig.discard();
        Entity again = EntityType.loadEntityRecursive(saved, t.level(), e -> e);
        again.moveTo(second.getX() + 0.5, second.getY(), second.getZ() + 0.5);
        t.level().addFreshEntity(again);
        t.assertEquals(spot(t, second), Actors.origin(again), "the origin of a pig spawned again from saved data");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void aConvertedMobEnteredTheWorldWhereTheOldOneDid(TestContext t) {
        t.player("alice");
        ready(t);
        BlockPos at = t.pos(2, 0, 0);
        Zombie zombie = spawn(t, EntityType.ZOMBIE, at);
        zombie.teleportTo(at.getX() - 3.5, at.getY(), at.getZ() + 0.5);
        ZombieVillager converted = zombie.convertTo(EntityType.ZOMBIE_VILLAGER, false);
        t.assertTrue(converted != null, "the zombie should convert");
        t.assertEquals(spot(t, at), Actors.origin(converted), "the origin of a mob converted from another");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void aPigLightningTurnsKeepsWhereThePigEnteredTheWorld(TestContext t) {
        t.assertTrue(t.level().getDifficulty() != Difficulty.PEACEFUL, "the test server is on peaceful, so lightning "
                + "turns no pig");
        t.player("alice"); // entities only tick, and commands only find them, near a player
        ready(t);
        BlockPos at = t.pos(5, 0, 5);
        Pig pig = spawn(t, EntityType.PIG, at);
        // It came from elsewhere: struck here, its zombified piglin is still from there.
        pig.teleportTo(at.getX() - 3.5, at.getY(), at.getZ() + 0.5);
        console(t, "summon minecraft:lightning_bolt " + pig.getX() + " " + pig.getY() + " " + pig.getZ());
        t.await("lightning to turn the pig", () -> !near(t, ZombifiedPiglin.class).isEmpty(), 20);
        t.assertEquals(spot(t, at), Actors.origin(near(t, ZombifiedPiglin.class).get(0)),
                "the origin of a zombified piglin lightning made of a pig from elsewhere");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void aShearedMooshroomsCowKeepsWhereTheMooshroomEnteredTheWorld(TestContext t) {
        t.player("alice");
        ready(t);
        BlockPos at = t.pos(2, 0, 0);
        MushroomCow mooshroom = spawn(t, EntityType.MOOSHROOM, at);
        mooshroom.teleportTo(at.getX() - 3.5, at.getY(), at.getZ() + 0.5);
        mooshroom.shear(SoundSource.PLAYERS);
        List<Cow> cows = near(t, Cow.class).stream().filter(c -> !(c instanceof MushroomCow)).toList();
        t.assertEquals(1, cows.size(), "the number of cows a sheared mooshroom left");
        t.assertEquals(spot(t, at), Actors.origin(cows.get(0)), "the origin of the cow a mooshroom from elsewhere "
                + "was sheared into");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void aMobWithNoRecordedOriginThatConvertsStaysUnseen(TestContext t) {
        t.player("alice");
        ready(t);
        Zombie zombie = spawn(t, EntityType.ZOMBIE, t.pos(2, 0, 0));
        Zombie old = reload(t, zombie, tag -> tag.getCompound(ATTACHMENTS).remove(ORIGIN), Zombie.class);
        t.assertEquals(unseen(), Actors.origin(old), "the origin of a zombie saved without one");
        // As when it drowns: where it converts is not where it entered the world, which nothing knows.
        Drowned drowned = old.convertTo(EntityType.DROWNED, true);
        t.assertTrue(drowned != null, "the zombie should convert");
        t.assertEquals(unseen(), Actors.origin(drowned), "the origin of a drowned made of a zombie that had none");
    }

    /** Every origin a mount event states for a drowned from now on. */
    private static List<Place> hearDrownedMounts(TestContext t) {
        List<Place> origins = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMountEvent e) {
                if (e.getEntity() instanceof Drowned) origins.add(e.getOrigin());
            }
        });
        return origins;
    }

    @HadronTest
    @Covers(value = EntityEvent.class, method = "getOrigin")
    void aConvertedRidersMountEventStatesTheOldMobsOrigin(TestContext t) {
        t.player("alice");
        ready(t);
        BlockPos at = t.pos(2, 0, 0);
        Zombie zombie = spawn(t, EntityType.ZOMBIE, at);
        Minecart cart = spawn(t, EntityType.MINECART, t.pos(-3, 0, 2));
        t.assertTrue(zombie.startRiding(cart, true), "the zombie should ride the minecart");
        List<Place> origins = hearDrownedMounts(t);
        // Vanilla seats the new mob on the old one's vehicle before the conversion returns.
        Drowned drowned = zombie.convertTo(EntityType.DROWNED, true);
        t.assertTrue(drowned != null && drowned.getVehicle() == cart, "the drowned should ride the zombie's "
                + "minecart, but rides " + (drowned == null ? null : drowned.getVehicle()));
        t.assertEquals(List.of(spot(t, at)), origins, "getOrigin() in the mount event of a drowned converted from "
                + "a riding zombie");
    }

    @HadronTest
    @Covers(value = EntityEvent.class, method = "getOrigin")
    void aConvertedRidersMountEventStaysUnseenWhenTheOldMobHadNoOrigin(TestContext t) {
        t.player("alice");
        ready(t);
        Zombie zombie = spawn(t, EntityType.ZOMBIE, t.pos(2, 0, 0));
        Zombie old = reload(t, zombie, tag -> tag.getCompound(ATTACHMENTS).remove(ORIGIN), Zombie.class);
        Minecart cart = spawn(t, EntityType.MINECART, t.pos(-3, 0, 2));
        t.assertTrue(old.startRiding(cart, true), "the zombie should ride the minecart");
        List<Place> origins = hearDrownedMounts(t);
        Drowned drowned = old.convertTo(EntityType.DROWNED, true);
        t.assertTrue(drowned != null && drowned.getVehicle() == cart, "the drowned should ride the zombie's "
                + "minecart, but rides " + (drowned == null ? null : drowned.getVehicle()));
        t.assertEquals(List.of(unseen()), origins, "getOrigin() in the mount event of a drowned converted from a "
                + "riding zombie that had no origin");
        t.assertEquals(unseen(), Actors.origin(drowned), "the origin of that drowned afterwards");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void addingAnEntityAlreadyInTheWorldLeavesItsOriginWhereItWas(TestContext t) {
        t.player("alice");
        ready(t);
        BlockPos at = t.pos(2, 0, 0);
        MinecartHopper cart = spawn(t, EntityType.HOPPER_MINECART, at);
        cart.teleportTo(at.getX() - 3.5, at.getY(), at.getZ() + 0.5);
        List<Place> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntitySpawnEvent e) {
                if (e.getEntity() == cart) heard.add(e.getOrigin());
            }
        });
        // A mod adding it again: Minecraft refuses, since it is in the world already, and it enters nowhere new.
        t.assertTrue(!t.level().addFreshEntity(cart), "adding a hopper minecart already in the world should fail");
        t.assertEquals(List.of(spot(t, at)), heard, "getOrigin() in the spawn event of that second, failed add");
        t.assertEquals(spot(t, at), Actors.origin(cart), "the origin of a live hopper minecart after a second, "
                + "failed addFreshEntity");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void anEntityGeneratedWithTheWorldEntersWhereItWasGenerated(TestContext t) {
        t.player("alice");
        ready(t);
        BlockPos at = t.pos(3, 0, 3);
        // World generation never adds a fresh entity: it saves each into its chunk, which loads it later.
        ProtoChunk generating = new ProtoChunk(new net.minecraft.world.level.ChunkPos(at), UpgradeData.EMPTY, t.level(),
                t.level().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME), null);
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        generating.addEntity(pig);
        t.assertEquals(1, generating.getEntities().size(), "the number of entities saved into the generating chunk");
        Entity loaded = EntityType.loadEntityRecursive(generating.getEntities().get(0), t.level(), e -> e);
        t.assertEquals(spot(t, at), Actors.origin(loaded), "the origin of a pig generated with its chunk");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "origin")
    void anOriginAddsUnderAHundredBytesToASavedEntity(TestContext t) throws IOException {
        t.player("alice");
        ready(t);
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        CompoundTag with = new CompoundTag();
        pig.save(with);
        CompoundTag without = with.copy();
        without.remove(ATTACHMENTS); // the pig has no other attachment, so this is the origin and its wrapper
        int added = bytes(with) - bytes(without);
        t.assertTrue(with.getCompound(ATTACHMENTS).contains(ORIGIN), "the saved pig should hold " + ORIGIN + ", but "
                + "held " + with.getCompound(ATTACHMENTS));
        t.assertEquals(1, with.getCompound(ATTACHMENTS).size(), "the number of attachments on a pig nobody is behind");
        // 24 for NeoForge's attachments compound (shared with any other attachment), 53 for the origin's key, its
        // compound and its block, and the dimension's id.
        int expected = 24 + 53 + t.level().dimension().location().toString().length();
        t.assertEquals(expected, added, "the bytes an origin adds to a saved entity, uncompressed");
        t.assertTrue(added < 100, "an origin should add under 100 bytes to a saved entity, but added " + added);
    }

    private static int bytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(out));
        return out.size();
    }
}
