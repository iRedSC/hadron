package dev.iredsc.hadron.selftest.world;

import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.event.ActionEvent;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.block.BlockIgniteEvent;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.api.world.Area;
import dev.iredsc.hadron.api.world.Areas;
import dev.iredsc.hadron.api.world.WorldCreator;
import dev.iredsc.hadron.api.world.WorldGenerator;
import dev.iredsc.hadron.api.world.Worlds;
import dev.iredsc.hadron.checks.Nondeterministic;
import dev.iredsc.hadron.selftest.SelfTestPlugin;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;

/**
 * Watched areas: a block change with nobody behind it fires as {@link Actor.Unknown} inside one, and nothing outside,
 * while vanilla's own workings and a plugin's own tasks never look like an outsider's.
 */
public final class AreaTests {
    /** A box from pos(-4, 0, -4) to pos(4, 6, 4) of the test's world. */
    private static BoundingBox around(TestContext t) {
        return BoundingBox.fromCorners(t.pos(-4, 0, -4), t.pos(4, 6, 4));
    }

    private static List<BlockEvent> record(TestContext t, boolean cancel) {
        List<BlockEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockEvent e) {
                seen.add(e);
                if (cancel && e instanceof dev.iredsc.hadron.api.event.Cancellable c) c.setCancelled(true);
            }
        });
        return seen;
    }

    private static List<BlockEvent> unknown(List<BlockEvent> seen) {
        return seen.stream().filter(e -> e.getActor() instanceof Actor.Unknown).toList();
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aChangeWithNobodyBehindItInAWatchedAreaIsUnknown(TestContext t) {
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, false);
        // The test's own code has nobody on the actor stack, as an unbridged mod's queued work wouldn't.
        t.setBlock(t.pos(1, 1, 1), Blocks.STONE);
        t.assertEquals(1, seen.size(), "the number of block events for one write with nobody behind it");
        t.assertTrue(seen.get(0).getActor() instanceof Actor.Unknown,
                "a write with nobody behind it in a watched area should be Actor.Unknown, but was "
                        + seen.get(0).getActor());
        t.assertBlock(t.pos(1, 1, 1), Blocks.STONE);
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aPluginCanWatchInOnEnable(TestContext t) {
        Area area = ((SelfTestPlugin) t.plugin()).startupArea();
        t.assertTrue(area != null && area.isOpen(), "the area the plugin watched in onEnable should be open, but was "
                + area);
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void refusingAnUnknownChangeKeepsTheBlock(TestContext t) {
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, true);
        t.setBlock(t.pos(1, 1, 1), Blocks.STONE);
        t.assertEquals(1, seen.size(), "the number of block events for one refused write");
        t.assertBlock(t.pos(1, 1, 1), Blocks.AIR);
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void refusingAnUnknownBreakKeepsTheBlockAndDropsNothing(TestContext t) {
        t.setBlock(t.pos(1, 1, 1), Blocks.STONE);
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, true);
        t.level().destroyBlock(t.pos(1, 1, 1), true);
        t.assertEquals(1, seen.size(), "the number of block events for one refused break");
        t.assertTrue(seen.get(0).getActor() instanceof Actor.Unknown, "a break with nobody behind it in a watched "
                + "area should be Actor.Unknown, but was " + seen.get(0).getActor());
        t.assertBlock(t.pos(1, 1, 1), Blocks.STONE);
        t.assertTrue(t.level().getEntitiesOfClass(ItemEntity.class, new AABB(t.pos(1, 1, 1)).inflate(2)).isEmpty(),
                "a refused break should drop nothing");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aFireWithNobodyBehindItInAWatchedAreaIsUnknown(TestContext t) {
        t.setBlock(t.pos(1, 0, 1), Blocks.NETHERRACK);
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, true);
        t.setBlock(t.pos(1, 1, 1), Blocks.FIRE);
        t.assertEquals(1, seen.size(), "the number of block events for one fire with nobody behind it");
        t.assertTrue(seen.get(0).getActor() instanceof Actor.Unknown, "a fire with nobody behind it in a watched "
                + "area is an outsider's, not nature's, but was " + seen.get(0).getActor());
        t.assertBlock(t.pos(1, 1, 1), Blocks.AIR);
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void refusingAnUnknownIgnitionKeepsTheTntUnlit(TestContext t) {
        BlockPos tnt = t.pos(4, 0, 0);
        t.setBlock(tnt, Blocks.TNT);
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, true);
        // Nobody behind the power, from just outside the area.
        t.setBlock(tnt.east(), Blocks.REDSTONE_BLOCK);
        t.tick();
        t.assertBlock(tnt, Blocks.TNT);
        t.assertTrue(t.level().getEntitiesOfClass(PrimedTnt.class, new AABB(tnt).inflate(3)).isEmpty(),
                "a refused ignition should leave no primed TNT");
        t.assertTrue(!unknown(seen).isEmpty(), "lighting TNT in a watched area with nobody behind it is Unknown");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aGolemBuiltIntoAWatchedAreaByNobodyIsRefused(TestContext t) {
        BlockPos base = t.pos(0, 0, 0);
        t.setBlock(base, Blocks.IRON_BLOCK);
        t.setBlock(base.above(), Blocks.IRON_BLOCK);
        t.setBlock(base.above().west(), Blocks.IRON_BLOCK);
        t.setBlock(base.above().east(), Blocks.IRON_BLOCK);
        // Only one arm is watched; the pumpkin that completes the golem lands outside it.
        Areas.watch(t.plugin(), t.level(), BoundingBox.fromCorners(base.above().west(), base.above().west()));
        List<BlockEvent> seen = record(t, true);
        t.setBlock(base.above(2), Blocks.CARVED_PUMPKIN);
        t.assertBlock(base.above().west(), Blocks.IRON_BLOCK);
        t.assertTrue(t.level().getEntitiesOfClass(IronGolem.class, new AABB(base).inflate(4)).isEmpty(),
                "a golem whose build was refused should not spawn");
        t.assertEquals(1, unknown(seen).size(), "the Unknown events for the one watched block of the golem");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aGolemBuiltIntoAWatchedAreaByNobodyIsOneOperation(TestContext t) {
        BlockPos base = t.pos(0, 0, 0);
        t.setBlock(base, Blocks.IRON_BLOCK);
        t.setBlock(base.above(), Blocks.IRON_BLOCK);
        t.setBlock(base.above().west(), Blocks.IRON_BLOCK);
        t.setBlock(base.above().east(), Blocks.IRON_BLOCK);
        Areas.watch(t.plugin(), t.level(), BoundingBox.fromCorners(base.above().west(), base.above().east()));
        List<ActionEvent> seen = new ArrayList<>();
        List<Actor> handlersActedAs = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockEvent e) {
                seen.add(e);
                handlersActedAs.add(Actors.currentOr(Actor.Unknown.INSTANCE));
            }

            @EventHandler
            void on(EntitySpawnEvent e) {
                if (!(e.getEntity() instanceof IronGolem)) return;
                seen.add(e);
                handlersActedAs.add(Actors.currentOr(Actor.Unknown.INSTANCE));
            }
        });
        t.setBlock(base.above(2), Blocks.CARVED_PUMPKIN);
        List<Long> operations = seen.stream().filter(e -> e.getActor() instanceof Actor.Unknown)
                .map(e -> e.getOperation().id()).toList();
        t.assertEquals(4, operations.size(), "Unknown events for the three watched blocks and the golem: " + seen);
        t.assertEquals(1L, operations.stream().distinct().count(), "a build's blocks and creature should be one "
                + "operation, but were " + operations);
        Actor plugin = new Actor.Server("plugin " + t.plugin().getName());
        t.assertEquals(List.of(plugin, plugin, plugin, plugin), handlersActedAs,
                "who a handler acts as for an outsider's build and its golem: nobody is behind it, so its plugin");
    }

    /** Alice asleep in a bed at pos(1, 0, 0), at night, inside a watched area. Returns the overworld's time to put back. */
    private static long asleepInAWatchedArea(TestContext t, TestPlayer alice) {
        ServerLevel overworld = t.server().overworld(); // test worlds take their time from it
        long time = overworld.getDayTime();
        overworld.setDayTime(18000);
        BlockPos foot = t.pos(1, 0, 0);
        t.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.FOOT));
        t.setBlock(foot.east(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.HEAD));
        t.tick(); // the sky darkens on the world's tick
        Areas.watch(t.plugin(), t.level(), around(t));
        alice.useBlock(foot);
        t.assertTrue(alice.player().isSleeping(), "alice should be asleep");
        return time;
    }

    @HadronTest(timeout = 400)
    @Covers(value = Areas.class, method = "watch")
    void sleepersWakeInAWatchedAreaThatRefusesOutsiders(TestContext t) {
        TestPlayer alice = t.player("alice");
        long time = asleepInAWatchedArea(t, alice);
        try {
            List<BlockEvent> strangers = refuseStrangers(t);
            t.await("the night to pass", () -> !alice.player().isSleeping(), 300);
            t.assertFalse(t.getBlock(t.pos(1, 0, 0)).getValue(BedBlock.OCCUPIED), "the bed should be free once alice "
                    + "woke");
            t.assertTrue(strangers.isEmpty(), "waking in the morning fired as Actor.Unknown: " + strangers);
        } finally {
            t.server().overworld().setDayTime(time);
        }
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aSleeperLeavingTheGameFreesTheirBedInAWatchedArea(TestContext t) {
        TestPlayer alice = t.player("alice");
        long time = asleepInAWatchedArea(t, alice);
        try {
            List<BlockEvent> strangers = refuseStrangers(t);
            alice.disconnect();
            t.tick();
            t.assertFalse(t.getBlock(t.pos(1, 0, 0)).getValue(BedBlock.OCCUPIED), "the bed should be free once alice "
                    + "left");
            t.assertTrue(strangers.isEmpty(), "leaving the game asleep fired as Actor.Unknown: " + strangers);
        } finally {
            t.server().overworld().setDayTime(time);
        }
    }

    /** Records Unknown block events and refuses them, as protection would. */
    private static List<BlockEvent> refuseStrangers(TestContext t) {
        List<BlockEvent> strangers = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockEvent e) {
                if (!(e.getActor() instanceof Actor.Unknown)) return;
                strangers.add(e);
                if (e instanceof dev.iredsc.hadron.api.event.Cancellable c) c.setCancelled(true);
            }
        });
        return strangers;
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void containersLeftOpenOnLeavingTheGameCloseInAWatchedArea(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        BlockPos barrel = t.pos(1, 0, 0);
        BlockPos chest = t.pos(-1, 0, 0);
        BlockPos wire = t.pos(-2, 0, 0);
        t.setBlock(barrel, Blocks.BARREL);
        t.setBlock(chest, Blocks.TRAPPED_CHEST);
        t.setBlock(wire.below(), Blocks.STONE);
        t.setBlock(wire, Blocks.REDSTONE_WIRE);
        alice.useBlock(barrel);
        bob.useBlock(chest);
        t.assertTrue(t.getBlock(barrel).getValue(BarrelBlock.OPEN), "alice's barrel should be open");
        t.assertTrue(t.getBlock(wire).getValue(RedStoneWireBlock.POWER) > 0, "bob's open trapped chest should power "
                + "the wire");
        Areas.watch(t.plugin(), t.level(), BoundingBox.fromCorners(t.pos(-4, -1, -4), t.pos(4, 6, 4)));
        List<BlockEvent> strangers = refuseStrangers(t);
        alice.disconnect();
        bob.disconnect();
        t.tick();
        t.assertFalse(t.getBlock(barrel).getValue(BarrelBlock.OPEN), "the barrel should close once alice left");
        t.assertEquals(0, t.getBlock(wire).getValue(RedStoneWireBlock.POWER), "the wire's power once bob left");
        t.assertTrue(strangers.isEmpty(), "closing containers on leaving fired as Actor.Unknown: " + strangers);
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aRespawnAnchorUsedFromAnotherWorldInAWatchedAreaIsVanillas(TestContext t) {
        TestPlayer alice = t.player("alice");
        ServerLevel nether = t.server().getLevel(Level.NETHER);
        t.assertTrue(nether != null, "the test server should have a Nether");
        // Far from anything another test touches; a floor and headroom to stand up on beside the anchor.
        BlockPos anchor = new BlockPos(4000, 100, 4000);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                nether.setBlockAndUpdate(anchor.offset(x, -1, z), Blocks.OBSIDIAN.defaultBlockState());
                nether.setBlockAndUpdate(anchor.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                nether.setBlockAndUpdate(anchor.offset(x, 1, z), Blocks.AIR.defaultBlockState());
            }
        }
        nether.setBlockAndUpdate(anchor, Blocks.RESPAWN_ANCHOR.defaultBlockState()
                .setValue(RespawnAnchorBlock.CHARGE, 2));
        Area area = Areas.watch(t.plugin(), nether, new BoundingBox(anchor).inflatedBy(2));
        try {
            List<BlockEvent> strangers = refuseStrangers(t);
            alice.player().setRespawnPosition(Level.NETHER, anchor, 0, false, false);
            // As leaving the End does, from alice's tick there, with nobody on the Nether's stack.
            alice.player().findRespawnPositionAndUseSpawnBlock(false, DimensionTransition.DO_NOTHING);
            t.assertEquals(1, nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE),
                    "the anchor's charge after alice respawned at it");
            t.assertTrue(strangers.isEmpty(), "using a respawn anchor fired as Actor.Unknown: " + strangers);
        } finally {
            area.close();
            nether.setBlockAndUpdate(anchor, Blocks.AIR.defaultBlockState());
        }
    }

    /** Not a test: an event the test fires itself, with nobody behind it. */
    public static final class Nudge extends dev.iredsc.hadron.api.event.Event {}

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aPluginsOwnHandlerForAnEventWithNobodyBehindItIsTheServer(TestContext t) {
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, false);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(Nudge e) {
                t.setBlock(t.pos(1, 1, 1), Blocks.STONE);
            }
        });
        t.plugin().getServer().getPluginManager().callEvent(new Nudge());
        t.assertEquals(1, seen.size(), "the number of block events for the handler's write");
        t.assertEquals(new Actor.Server("plugin " + t.plugin().getName()), seen.get(0).getActor(),
                "who a plugin's handler acts as when nobody is behind the event");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aPluginReactingToAnOutsidersChangeActsAsItself(TestContext t) {
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, false);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockEvent e) {
                if (e.getPos().equals(t.pos(1, 1, 1))) t.setBlock(t.pos(2, 1, 1), Blocks.STONE);
            }
        });
        t.setBlock(t.pos(1, 1, 1), Blocks.STONE);
        t.assertEquals(2, seen.size(), "block events for the outsider's write and the handler's reaction: " + seen);
        t.assertEquals(new Actor.Server("plugin " + t.plugin().getName()), seen.get(1).getActor(),
                "a handler's own reaction is its plugin's, whoever was behind the event");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void anArrivalPortalOrEndPlatformBuiltInAWatchedAreaIsVanillas(TestContext t) {
        Areas.watch(t.plugin(), t.level(), BoundingBox.fromCorners(t.pos(-16, -2, -16), t.pos(16, 8, 16)));
        List<BlockEvent> strangers = refuseStrangers(t);
        // Built by a traveller's tick in the world they left, so nobody is on this world's stack.
        java.util.Optional<?> built = t.level().getPortalForcer().createPortal(t.pos(0, 1, 0), Direction.Axis.X);
        t.assertTrue(built.isPresent(), "the arrival portal should be built");
        BlockPos platform = t.pos(8, 3, 8);
        EndPlatformFeature.createEndPlatform(t.level(), platform, true);
        t.assertBlock(platform.below(), Blocks.OBSIDIAN);
        t.assertTrue(strangers.isEmpty(), "an arrival portal or End platform fired as Actor.Unknown: " + strangers);
    }

    @HadronTest(timeout = 400)
    @Covers(value = Areas.class, method = "watch")
    void frostWalkerInAWatchedAreaIsVanillas(TestContext t) {
        TestPlayer alice = t.player("alice");
        ItemStack boots = new ItemStack(Items.DIAMOND_BOOTS);
        boots.enchant(t.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.FROST_WALKER), 2);
        alice.player().setItemSlot(EquipmentSlot.FEET, boots);
        // A two-block bedrock path through water: walking it freezes the water beside it.
        BlockPos beside = t.pos(1, -1, 1);
        t.setBlock(beside, Blocks.WATER);
        t.setBlock(t.pos(-1, -1, 1), Blocks.WATER);
        Areas.watch(t.plugin(), t.level(), BoundingBox.fromCorners(t.pos(-4, -1, -4), t.pos(4, 4, 4)));
        List<BlockEvent> strangers = refuseStrangers(t);
        alice.moveTo(t.pos(0, 0, 1));
        t.await("the water beside alice to freeze", () -> t.getBlock(beside).is(Blocks.FROSTED_ICE), 40);
        t.assertTrue(strangers.isEmpty(), "Frost Walker fired as Actor.Unknown: " + strangers);
    }

    @HadronTest(timeout = 600)
    @Covers(value = Areas.class, method = "watch")
    void lavaSettingFireInAWatchedAreaIsNature(TestContext t) {
        t.player("alice"); // random ticks run only near a player
        t.server().overworld().setWeatherParameters(24000, 0, false, false);
        t.setBlock(t.pos(0, 0, 0), Blocks.LAVA);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) t.setBlock(t.pos(x, 2, z), Blocks.OAK_PLANKS);
        }
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> strangers = refuseStrangers(t);
        List<BlockIgniteEvent> fires = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockIgniteEvent e) {
                fires.add(e);
            }
        });
        GameRules.IntegerValue rule = t.server().getGameRules().getRule(GameRules.RULE_RANDOMTICKING);
        int old = rule.get();
        rule.set(1024, t.server());
        try {
            // Vanilla ticks lava twice per random pick, through its block and directly; several fires give both a turn.
            t.await("lava to set fire eight times", () -> fires.size() + strangers.size() >= 8, 400);
        } finally {
            rule.set(old, t.server());
        }
        t.assertTrue(strangers.isEmpty(), "lava's fire fired as Actor.Unknown: " + strangers);
        t.assertTrue(fires.get(0).getActor() instanceof Actor.Nature, "lava's fire should be nature's, but was "
                + fires.get(0).getActor());
    }

    @HadronTest
    @Covers(value = Area.class, method = "box")
    @SuppressWarnings("deprecation") // BoundingBox.move: the in-place move this test makes sure can't reach the area
    void movingTheBoxAfterwardsDoesntMoveTheArea(TestContext t) {
        BoundingBox box = around(t);
        Area area = Areas.watch(t.plugin(), t.level(), box);
        box.move(100, 0, 0);
        area.box().move(100, 0, 0);
        t.assertEquals(around(t), area.box(), "the area's box after the plugin moved its own copies");
        t.assertTrue(Areas.isWatched(t.level(), t.pos(1, 1, 1)), "the area still watches where it was made");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    @Covers(value = Areas.class, method = "isWatched")
    void outsideEveryAreaAChangeWithNobodyBehindItFiresNothing(TestContext t) {
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, false);
        BlockPos outside = t.pos(6, 1, 0);
        t.assertTrue(Areas.isWatched(t.level(), t.pos(4, 6, 4)), "both corners of a watched box are inside it");
        t.assertTrue(!Areas.isWatched(t.level(), outside), t.describe(outside) + " is outside the box");
        t.setBlock(outside, Blocks.STONE);
        t.assertEquals(0, seen.size(), "the number of block events for a write outside every area");
    }

    @HadronTest
    @Covers(value = Area.class, method = "close")
    @Covers(value = Area.class, method = "isOpen")
    @Covers(value = Area.class, method = "level")
    @Covers(value = Area.class, method = "box")
    void aClosedAreaWatchesNothing(TestContext t) {
        Area area = Areas.watch(t.plugin(), t.level(), around(t));
        t.assertEquals(t.level(), area.level(), "the area's world");
        t.assertEquals(around(t), area.box(), "the area's box");
        t.assertTrue(area.isOpen(), "a new area is open");
        area.close();
        area.close();
        t.assertTrue(!area.isOpen(), "a closed area says it is closed");
        t.assertTrue(!Areas.isWatched(t.level(), t.pos(1, 1, 1)), "nothing is watched once the only area closed");
        List<BlockEvent> seen = record(t, false);
        t.setBlock(t.pos(1, 1, 1), Blocks.STONE);
        t.assertEquals(0, seen.size(), "the number of block events after the area closed");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void overlappingAreasStayWatchedUntilTheLastCloses(TestContext t) {
        Area first = Areas.watch(t.plugin(), t.level(), around(t));
        Area second = Areas.watch(t.plugin(), t.level(), BoundingBox.fromCorners(t.pos(0, 0, 0), t.pos(2, 2, 2)));
        first.close();
        t.assertTrue(Areas.isWatched(t.level(), t.pos(1, 1, 1)), "the second area still watches pos(1, 1, 1)");
        t.assertTrue(!Areas.isWatched(t.level(), t.pos(-3, 1, -3)), "only the first area covered pos(-3, 1, -3)");
        second.close();
        t.assertTrue(!Areas.isWatched(t.level(), t.pos(1, 1, 1)), "nothing is watched once both closed");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aHugeAreaIsWatchedEverywhereInIt(TestContext t) {
        // Wider than the index lists chunk by chunk, so it takes the other path.
        Area area = Areas.watch(t.plugin(), t.level(),
                BoundingBox.fromCorners(t.pos(-2000, -64, -2000), t.pos(2000, 300, 2000)));
        t.assertTrue(Areas.isWatched(t.level(), t.pos(1999, 0, -1999)), "a corner of the huge area is watched");
        t.assertTrue(!Areas.isWatched(t.level(), t.pos(2001, 0, 0)), "just past its edge isn't");
        area.close();
        t.assertTrue(!Areas.isWatched(t.level(), t.pos(0, 0, 0)), "nothing is watched once it closed");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aPluginsOwnTaskInAWatchedAreaIsTheServer(TestContext t) {
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, false);
        Hadron.getScheduler().runTask(t.plugin(), () -> t.setBlock(t.pos(1, 1, 1), Blocks.STONE));
        t.await("the task to place the stone", () -> t.getBlock(t.pos(1, 1, 1)).is(Blocks.STONE), 5);
        t.assertEquals(1, seen.size(), "the number of block events for the task's write");
        t.assertEquals(new Actor.Server("plugin " + t.plugin().getName()), seen.get(0).getActor(),
                "who a plugin's own task acts as");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void anAreaClosesWhenItsWorldUnloads(TestContext t) {
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath("hadronselftest", "areas");
        try {
            ServerLevel level = Worlds.create(new WorldCreator(key).generator(WorldGenerator.voidWorld()));
            Area area = Areas.watch(t.plugin(), level, BoundingBox.fromCorners(BlockPos.ZERO, new BlockPos(4, 4, 4)));
            Worlds.unload(level, false);
            t.tick();
            t.assertTrue(!area.isOpen(), "an area in a world that unloaded is closed");
            try {
                Areas.watch(t.plugin(), level, area.box());
                t.fail("watching in a world that isn't loaded should be refused");
            } catch (IllegalArgumentException expected) {
                t.assertTrue(expected.getMessage().contains("Worlds.load"), "the refusal should say how to load the "
                        + "world, but said: " + expected.getMessage());
            }
        } finally {
            if (Worlds.isCreated(key)) Worlds.delete(key);
        }
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    @Nondeterministic(reason = "calls Areas from another thread, which takes wall time to run")
    void watchingOffTheServerThreadSaysHowToMoveOnIt(TestContext t) {
        CompletableFuture<String> message = CompletableFuture.supplyAsync(() -> {
            try {
                Areas.watch(t.plugin(), t.level(), around(t));
                return "no exception";
            } catch (IllegalStateException e) {
                return String.valueOf(e.getMessage());
            }
        });
        t.awaitAsync("the other thread to try", message::isDone, 40);
        t.assertTrue(message.join().contains("runTask"), "the refusal should say to use a sync task, but said: "
                + message.join());
    }

    /**
     * Vanilla working on its own inside a watched area: a furnace lighting, a piston pushing and finishing its move,
     * sand falling, water drying up. None of it has a mod behind it, so none of it is an outsider's, and protection
     * must never refuse it. The test's own writes that start it all run as the server, so no Unknown is allowed.
     */
    @HadronTest(timeout = 400)
    @Covers(value = Areas.class, method = "watch")
    void vanillaWorkingOnItsOwnInAWatchedAreaIsNeverUnknown(TestContext t) {
        BlockPos furnacePos = t.pos(-2, 1, 0);
        BlockPos piston = t.pos(2, 1, 0);
        BlockPos sand = t.pos(0, 5, 2);
        BlockPos water = t.pos(-3, 0, -3);
        t.level().setChunkForced(piston.getX() >> 4, piston.getZ() >> 4, true);
        t.awaitAsync("the test world to tick", () -> t.level().isPositionEntityTicking(piston), 300);
        t.setBlock(furnacePos, Blocks.FURNACE);
        FurnaceBlockEntity furnace = (FurnaceBlockEntity) t.level().getBlockEntity(furnacePos);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON));
        furnace.setItem(1, new ItemStack(Items.COAL));
        t.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        t.setBlock(piston.east(), Blocks.STONE);
        t.setBlock(water, Blocks.WATER);
        t.await("the water to spread", () -> t.getBlock(water.east()).is(Blocks.WATER), 40);

        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, false);
        try (Actors.Scope ignored = Actors.push(new Actor.Server("the test"))) {
            t.setBlock(piston.west(), Blocks.REDSTONE_BLOCK);
            t.setBlock(sand, Blocks.SAND);
            t.setBlock(water, Blocks.AIR);
        }
        t.await("the furnace to light", () -> t.getBlock(furnacePos).getValue(FurnaceBlock.LIT), 40);
        t.await("the piston to finish its push", () -> t.getBlock(piston.east(2)).is(Blocks.STONE), 40);
        t.await("the sand to land", () -> t.getBlock(t.pos(0, 0, 2)).is(Blocks.SAND), 100);
        t.await("the water to dry up", () -> t.getBlock(water.east()).isAir(), 100);

        List<BlockEvent> strangers = unknown(seen);
        t.assertTrue(strangers.isEmpty(), "vanilla working on its own fired as Actor.Unknown: " + strangers.stream()
                .map(e -> e.getClass().getSimpleName() + " at " + t.describe(e.getPos()) + " " + e.getBlocks())
                .toList());
    }
}
