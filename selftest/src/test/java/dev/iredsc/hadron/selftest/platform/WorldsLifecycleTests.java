package dev.iredsc.hadron.selftest.platform;

import dev.iredsc.hadron.api.world.WorldCreator;
import dev.iredsc.hadron.api.world.WorldGenerator;
import dev.iredsc.hadron.api.world.Worlds;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Created worlds when something goes wrong: a mod's listener throws, the save can't be written, a folder can't be
 * removed, a mod takes the id. Nothing may be left half-built, and no world's blocks may be erased or reused by
 * mistake.
 */
public final class WorldsLifecycleTests {
    private static final String LOAD = "net.neoforged.neoforge.event.level.LevelEvent$Load";
    private static final String UNLOAD = "net.neoforged.neoforge.event.level.LevelEvent$Unload";
    private static final BlockPos MARKER = new BlockPos(2, 64, 2);

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("selftest", path);
    }

    private static WorldCreator voidWorld(ResourceLocation key) {
        return new WorldCreator(key).generator(WorldGenerator.voidWorld()).spawn(new BlockPos(0, 64, 0));
    }

    private static Path folder(TestContext t, ResourceLocation key) {
        return t.server().getWorldPath(LevelResource.ROOT).resolve("dimensions").resolve(key.getNamespace())
                .resolve(key.getPath()).toAbsolutePath().normalize();
    }

    private static void forget(TestContext t, ResourceLocation key) {
        try {
            if (Worlds.isCreated(key)) Worlds.delete(key);
        } catch (RuntimeException ignored) {
            // The test's own assertion says what went wrong; cleanup only tries.
        }
        t.tick(2);
    }

    private static void removeTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true);
                Files.deleteIfExists(p);
            }
        }
    }

    /** The files under {@code folder} this server process holds open, read from Linux's /proc. */
    private static List<String> openFilesUnder(Path folder) throws IOException {
        Path fds = Path.of("/proc/self/fd");
        if (!Files.isDirectory(fds)) throw new AssertionError("This test reads /proc/self/fd, which only Linux has");
        Path real = folder.toRealPath();
        List<String> open = new ArrayList<>();
        try (Stream<Path> list = Files.list(fds)) {
            for (Path fd : list.toList()) {
                try {
                    Path target = Files.readSymbolicLink(fd);
                    if (target.startsWith(real)) open.add(target.toString());
                } catch (IOException | UnsupportedOperationException closedMeanwhile) {
                    // The descriptor closed while listing.
                }
            }
        }
        return open;
    }

    @HadronTest
    @Covers(value = Worlds.class, method = "create")
    @Covers(value = Worlds.class, method = "get")
    @Covers(value = Worlds.class, method = "isCreated")
    void aModsLoadListenerThrowingLeavesNoHalfBuiltWorld(TestContext t) throws Exception {
        ResourceLocation key = id("load_throws");
        try {
            try (AutoCloseable mod = ModBus.listen(LOAD, e -> {
                if (ModBus.level(e) instanceof ServerLevel l && l.dimension().location().equals(key)) {
                    throw new IllegalStateException("a mod's bug on load");
                }
            })) {
                Worlds.create(voidWorld(key));
                t.fail("Worlds.create should fail when the world couldn't finish loading");
            } catch (RuntimeException expected) {
                t.assertTrue(String.valueOf(expected.getMessage()).contains(key.toString()), "the error should name "
                        + "the world, but was: " + expected.getMessage());
            }
            t.assertTrue(Worlds.get(key).isEmpty(), "a world that failed to load must not stay loaded half-built");
            t.assertFalse(Worlds.isCreated(key), "a world that failed to create must not be saved as created");
            t.tick(); // the half-built copy closes at the end of the tick
            ServerLevel world = Worlds.create(voidWorld(key));
            t.assertTrue(Worlds.get(key).orElse(null) == world, "creating it again, with the mod fixed, should work");
        } finally {
            forget(t, key);
        }
    }

    @HadronTest
    @Covers(value = Worlds.class, method = "create")
    void aDefinitionThatCantBeSavedRefusesTheWorld(TestContext t) throws Exception {
        ResourceLocation key = id("unsaved");
        // A folder where the store writes its temporary file, so saving worlds.json fails.
        Path blocker = t.server().getWorldPath(LevelResource.ROOT).resolve("hadron").resolve("worlds.json.tmp");
        Files.createDirectories(blocker);
        try {
            try {
                Worlds.create(voidWorld(key));
                t.fail("Worlds.create should fail when the world's definition can't be saved, or after a restart "
                        + "the world would come back with another seed over these chunks");
            } catch (IllegalStateException expected) {
                t.assertTrue(expected.getMessage().contains("worlds.json"), "the error should name the file it "
                        + "couldn't write, but was: " + expected.getMessage());
            }
            t.assertTrue(Worlds.get(key).isEmpty(), "a world whose definition wasn't saved must not be loaded");
            t.assertFalse(Worlds.isCreated(key), "a world whose definition wasn't saved must not be listed");
        } finally {
            removeTree(blocker);
            forget(t, key);
        }
    }

    @HadronTest(timeout = 300)
    @Covers(value = Worlds.class, method = "unload")
    @Covers(value = Worlds.class, method = "load")
    void aModsUnloadListenerThrowingStillClosesTheWorld(TestContext t) throws Exception {
        ResourceLocation key = id("unload_throws");
        try {
            ServerLevel world = Worlds.create(voidWorld(key));
            world.setBlockAndUpdate(MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
            world.save(null, true, false);
            Path folder = folder(t, key);
            t.assertFalse(openFilesUnder(folder).isEmpty(), "a saved world should hold its region files open");
            try (AutoCloseable mod = ModBus.listen(UNLOAD, e -> {
                if (ModBus.level(e) == world) throw new IllegalStateException("a mod's bug on unload");
            })) {
                Worlds.unload(world, true);
                t.tick(2);
            }
            t.assertEquals(List.of(), openFilesUnder(folder), "files the unloaded world still holds open");
            ServerLevel again = Worlds.load(key);
            t.assertTrue(again.getBlockState(MARKER).is(Blocks.DIAMOND_BLOCK), "the world should load again with "
                    + "its saved blocks, but has " + again.getBlockState(MARKER));
        } finally {
            forget(t, key);
        }
    }

    @HadronTest(timeout = 300)
    @Covers(value = Worlds.class, method = "delete")
    @Covers(value = Worlds.class, method = "create")
    void aFolderThatCantBeRemovedIsNeverReusedByANewWorld(TestContext t) throws Exception {
        ResourceLocation key = id("undeletable");
        Path folder = folder(t, key);
        Path locked = folder.resolve("locked");
        try {
            ServerLevel world = Worlds.create(voidWorld(key));
            world.setBlockAndUpdate(MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
            Worlds.unload(world, true);
            t.tick(2);
            Files.createDirectories(locked);
            Files.writeString(locked.resolve("kept.txt"), "can't be removed while its folder is read-only");
            t.assertTrue(locked.toFile().setWritable(false), "the test couldn't make a folder read-only");
            try {
                Worlds.delete(key);
                t.fail("Worlds.delete should say the folder couldn't be removed");
            } catch (IllegalStateException expected) {
                t.assertTrue(expected.getMessage().contains(locked.getParent().getFileName().toString()), "the error "
                        + "should name the folder to remove by hand, but was: " + expected.getMessage());
            }
            t.assertFalse(Worlds.isCreated(key), "a deleted world is forgotten even if its folder stays");
            try {
                ServerLevel reused = Worlds.create(voidWorld(key));
                t.fail("a new world must not open over the old one's chunks, but it did, and has "
                        + reused.getBlockState(MARKER) + " where the old world had a diamond block");
            } catch (IllegalStateException expected) {
                t.assertTrue(expected.getMessage().contains("delet"), "the error should say the old world is still "
                        + "being deleted, but was: " + expected.getMessage());
            }
            locked.toFile().setWritable(true);
            ServerLevel fresh = Worlds.create(voidWorld(key));
            t.assertTrue(fresh.getBlockState(MARKER).isAir(), "once the folder can be removed, the new world should "
                    + "start empty, but has " + fresh.getBlockState(MARKER));
        } finally {
            locked.toFile().setWritable(true);
            forget(t, key);
        }
    }

    @HadronTest
    @Covers(value = Worlds.class, method = "delete")
    void aWorldWhoseIdAModNowHoldsIsNotDeleted(TestContext t) throws Exception {
        ResourceLocation key = id("shadowed");
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, key);
        Path folder = folder(t, key);
        Map<ResourceKey<Level>, ServerLevel> levels = ModBus.worldMap(t.server());
        try {
            ServerLevel world = Worlds.create(voidWorld(key));
            world.setBlockAndUpdate(MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
            Worlds.unload(world, true);
            t.tick(2);
            t.assertTrue(Files.isDirectory(folder), "the unloaded world should have a folder");
            // A mod (or a datapack after a restart) now has a dimension with this id, over the same folder.
            levels.put(dimension, t.level());
            try {
                Worlds.delete(key);
                t.fail("Worlds.delete should refuse while a mod's dimension holds the id");
            } catch (IllegalArgumentException | IllegalStateException expected) {
                t.assertTrue(expected.getMessage().contains("mod or datapack"), "the error should say a mod or "
                        + "datapack holds the id, but was: " + expected.getMessage());
            } finally {
                levels.remove(dimension);
            }
            t.assertTrue(Files.isDirectory(folder), "the folder a mod's dimension uses must not be deleted");
            t.assertTrue(Worlds.isCreated(key), "a refused delete keeps the world created");
        } finally {
            levels.remove(dimension);
            forget(t, key);
        }
    }
}
