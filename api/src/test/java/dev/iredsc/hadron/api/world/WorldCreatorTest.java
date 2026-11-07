package dev.iredsc.hadron.api.world;

import dev.iredsc.hadron.test.Covers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** A world id is also its folder name, so anything that could escape or nest the folder is refused. */
class WorldCreatorTest {
    private static ResourceLocation id(String text) {
        return ResourceLocation.parse(text);
    }

    @Covers(value = WorldCreator.class, method = "key")
    @ParameterizedTest
    @ValueSource(strings = {"myplugin:mining", "myplugin:a.b", "my.plugin:x-1", "a:_"})
    void aPlainNameIsAccepted(String text) {
        assertEquals(id(text), new WorldCreator(id(text)).key());
    }

    @ParameterizedTest
    @CsvSource({
        "myplugin:a/b, myplugin:a_b",
        "myplugin:., myplugin:world",
        "myplugin:.., myplugin:world",
        "..:mine, myplugin:mine",
        ".:mine, myplugin:mine",
    })
    void anIdThatIsntOneFolderIsRefusedWithAFixedOne(String bad, String suggested) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new WorldCreator(id(bad)));
        assertTrue(e.getMessage().contains("like " + suggested), e.getMessage());
        // The suggestion itself must pass.
        new WorldCreator(id(suggested));
    }

    @Test
    void anEmptyPathIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorldCreator(ResourceLocation.fromNamespaceAndPath("myplugin", "")));
    }

    @Covers(value = WorldCreator.class, method = "checkedKey")
    @Test
    void savedIdsGetTheSameCheck() {
        assertEquals(id("a:b"), WorldCreator.checkedKey(id("a:b")));
        assertThrows(IllegalArgumentException.class, () -> WorldCreator.checkedKey(id("a:b/c")));
    }

    @Test
    void nullIsRefused() {
        assertThrows(NullPointerException.class, () -> new WorldCreator(null));
    }

    @Covers(value = WorldCreator.class, method = "name")
    @Test
    void nameIsBukkitsSpellingOfTheConstructor() {
        assertEquals(id("a:b"), WorldCreator.name(id("a:b")).key());
    }

    @Covers(value = WorldCreator.class, method = "type")
    @Covers(value = WorldCreator.class, method = "hasType")
    @Covers(value = WorldCreator.class, method = "seed")
    @Covers(value = WorldCreator.class, method = "spawn")
    @Covers(value = WorldCreator.class, method = "generator")
    @Test
    void unsetOptionsFallBackToDefaults() {
        WorldCreator c = new WorldCreator(id("a:b"));
        assertSame(BuiltinDimensionTypes.OVERWORLD, c.type());
        assertFalse(c.hasType());
        assertEquals(OptionalLong.empty(), c.seed());
        assertEquals(Optional.empty(), c.spawn());
        assertEquals(Optional.empty(), c.generator());
        assertFalse(c.keepSpawnLoaded());
    }

    @Covers(value = WorldCreator.class, method = "type")
    @Covers(value = WorldCreator.class, method = "seed")
    @Covers(value = WorldCreator.class, method = "keepSpawnLoaded")
    @Test
    void setOptionsAreKept() {
        WorldCreator c = new WorldCreator(id("a:b")).type(BuiltinDimensionTypes.NETHER).seed(42).keepSpawnLoaded(true);
        assertSame(BuiltinDimensionTypes.NETHER, c.type());
        assertTrue(c.hasType());
        assertEquals(OptionalLong.of(42), c.seed());
        assertTrue(c.keepSpawnLoaded());
        assertThrows(NullPointerException.class, () -> c.type(null));
        assertThrows(NullPointerException.class, () -> c.generator(null));
    }

    @Covers(value = WorldCreator.class, method = "generator")
    @Test
    void aSetGeneratorIsKept() {
        WorldGenerator flat = WorldGenerator.voidWorld();
        assertEquals(Optional.of(flat), new WorldCreator(id("a:b")).generator(flat).generator());
    }

    @Covers(value = WorldCreator.class, method = "spawn")
    @Test
    void aMutableSpawnIsCopiedSoLaterChangesDontMoveIt() {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(1, 2, 3);
        WorldCreator c = new WorldCreator(id("a:b")).spawn(pos);
        pos.set(9, 9, 9);
        assertEquals(new BlockPos(1, 2, 3), c.spawn().orElseThrow());
        assertNotSame(pos, c.spawn().orElseThrow());
    }

    @Covers(value = WorldCreator.class, method = "toString")
    @Test
    void itsTextNamesTheKeyAndEverySetting() {
        String text = new WorldCreator(id("a:b")).seed(42).keepSpawnLoaded(true).toString();
        assertTrue(text.startsWith("WorldCreator[a:b, "), text);
        assertTrue(text.contains(", seed=42, "), text);
        assertTrue(text.contains("keepSpawnLoaded=true"), text);
    }
}
