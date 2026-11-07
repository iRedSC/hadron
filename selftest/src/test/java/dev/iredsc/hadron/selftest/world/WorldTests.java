package dev.iredsc.hadron.selftest.world;

import static dev.iredsc.hadron.selftest.world.Console.assertServer;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.world.LightningStrikeEvent;
import dev.iredsc.hadron.api.event.world.PortalCreateEvent;
import dev.iredsc.hadron.api.event.world.WorldLoadEvent;
import dev.iredsc.hadron.api.event.world.WorldSaveEvent;
import dev.iredsc.hadron.api.event.world.WorldUnloadEvent;
import dev.iredsc.hadron.api.world.WorldCreator;
import dev.iredsc.hadron.api.world.WorldGenerator;
import dev.iredsc.hadron.api.world.Worlds;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.AABB;

/** Lightning, portals, and worlds loading, unloading and saving. */
public final class WorldTests {
    // ---- Lightning

    @HadronTest
    void summonedLightningIsOneStrikeByTheServer(TestContext t) {
        List<LightningStrikeEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(LightningStrikeEvent e) {
                seen.add(e);
            }
        });
        Console.awaitTicking(t, t.pos(3, 0, 3));
        Console.run(t, "summon minecraft:lightning_bolt ~3 ~ ~3");
        t.assertEquals(1, seen.size(), "the number of LightningStrikeEvents for one bolt");
        assertServer(t, seen.get(0).getActor());
        t.assertEquals(1, bolts(t), "lightning bolts in the world");
    }

    @HadronTest
    void cancelledLightningStrikesNothing(TestContext t) {
        List<LightningStrikeEvent> tried = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(LightningStrikeEvent e) {
                tried.add(e);
                e.setCancelled(true);
            }
        });
        Console.awaitTicking(t, t.pos(3, 0, 3));
        Console.run(t, "summon minecraft:lightning_bolt ~3 ~ ~3");
        t.tick(2);
        t.assertEquals(1, tried.size(), "the bolts that tried to strike");
        t.assertEquals(0, bolts(t), "lightning bolts in the world");
        t.assertBlock(t.pos(3, 0, 3), Blocks.AIR);
    }

    private static int bolts(TestContext t) {
        return t.level().getEntitiesOfClass(LightningBolt.class, new AABB(t.pos(0, 0, 0)).inflate(8)).size();
    }

    // ---- Portals. Test worlds aren't the overworld or the nether, where fire lights a frame, so the frame is filled
    // ---- through PortalShape, as fire (or a mod) does.

    /** An empty obsidian frame two wide and three high, with its inside starting at pos(0, 1, 4). */
    private static BlockPos frame(TestContext t) {
        BlockPos inside = t.pos(0, 1, 4);
        for (int x = 0; x <= 1; x++) {
            t.setBlock(inside.offset(x, -1, 0), Blocks.OBSIDIAN);
            t.setBlock(inside.offset(x, 3, 0), Blocks.OBSIDIAN);
        }
        for (int y = 0; y <= 2; y++) {
            t.setBlock(inside.offset(-1, y, 0), Blocks.OBSIDIAN);
            t.setBlock(inside.offset(2, y, 0), Blocks.OBSIDIAN);
        }
        return inside;
    }

    private static void light(TestContext t, BlockPos inside) {
        PortalShape shape = PortalShape.findEmptyPortalShape(t.level(), inside, Direction.Axis.X).orElse(null);
        t.assertTrue(shape != null, "the obsidian frame should be a valid portal shape");
        shape.createPortalBlocks();
    }

    @HadronTest
    void fillingAFrameIsOnePortalCreate(TestContext t) {
        BlockPos inside = frame(t);
        List<PortalCreateEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PortalCreateEvent e) {
                seen.add(e);
            }
        });
        light(t, inside);
        t.assertBlock(inside, Blocks.NETHER_PORTAL);
        t.assertEquals(1, seen.size(), "the number of PortalCreateEvents for one frame");
        t.assertEquals(PortalCreateEvent.CreateReason.FIRE, seen.get(0).getReason(), "the reason");
        t.assertTrue(seen.get(0).getArea().map(a -> a.isInside(inside) && a.isInside(inside.offset(1, 2, 0)))
                .orElse(false), "the event's area should cover the portal, but was " + seen.get(0).getArea());
    }

    @HadronTest
    void cancelledPortalCreateLeavesTheFrameEmpty(TestContext t) {
        BlockPos inside = frame(t);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PortalCreateEvent e) {
                e.setCancelled(true);
            }
        });
        light(t, inside);
        for (BlockPos pos : BlockPos.betweenClosed(inside, inside.offset(1, 2, 0))) t.assertBlock(pos, Blocks.AIR);
    }

    // ---- Worlds

    @HadronTest
    void creatingAWorldLoadsItAndUnloadingItUnloadsIt(TestContext t) {
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath("hadronselftest", "lifecycle");
        List<ServerLevel> loaded = new ArrayList<>();
        List<ServerLevel> unloaded = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            // Earlier tests' worlds may finish closing during this one, so only this world's events count.
            void on(WorldLoadEvent e) {
                if (e.getLevel().dimension().location().equals(key)) loaded.add(e.getLevel());
            }

            @EventHandler
            void on(WorldUnloadEvent e) {
                if (e.getLevel().dimension().location().equals(key)) unloaded.add(e.getLevel());
            }
        });
        try {
            ServerLevel level = Worlds.create(new WorldCreator(key).generator(WorldGenerator.voidWorld()));
            t.assertEquals(List.of(level), loaded, "the worlds WorldLoadEvent reported");
            Worlds.unload(level, false);
            t.tick();
            t.assertEquals(List.of(level), unloaded, "the worlds WorldUnloadEvent reported");
        } finally {
            if (Worlds.isCreated(key)) Worlds.delete(key);
        }
    }

    @HadronTest
    void saveAllSavesThisWorld(TestContext t) {
        List<ServerLevel> saved = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(WorldSaveEvent e) {
                saved.add(e.getLevel());
            }
        });
        Console.run(t, "save-all");
        t.assertTrue(saved.contains(t.level()), "save-all should fire WorldSaveEvent for the test's world; it fired "
                + "for " + saved.stream().map(l -> l.dimension().location().toString()).toList());
        t.assertTrue(saved.contains(t.server().overworld()), "save-all should fire WorldSaveEvent for the overworld");
    }
}
