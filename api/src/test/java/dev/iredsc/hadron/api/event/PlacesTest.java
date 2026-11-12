package dev.iredsc.hadron.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

/** Places can't be empty, and every entity event that names an entity of its own states that entity's place. */
class PlacesTest {
    private static final Place A = Place.Unknown.anywhere(Place.Unknown.Reason.COMMAND);
    private static final Place B = new Place.Nowhere(ResourceLocation.fromNamespaceAndPath("hadron", "account"), "bank",
            Optional.empty());

    @Test
    void placesAreNeverEmptyAndEachPlaceIsListedOnce() {
        Places places = Places.of(A, B, A);
        assertEquals(List.of(A, B), places);
        assertEquals(List.of(A, B), places.and(B, A));
        assertEquals(List.of(A), Places.of(A, List.of()));
        assertThrows(UnsupportedOperationException.class, () -> places.add(A));
        assertThrows(NullPointerException.class, () -> Places.of(null, List.of(A)));
    }

    @Test
    void linkedStorageIsNowhereAndAnEnderChestIsItsPlayers() {
        UUID alice = new UUID(1, 2);
        InventoryHolder ender = new InventoryHolder.Linked(InventoryHolder.Linked.ENDER_CHEST, alice.toString());
        assertEquals(new Place.Nowhere(InventoryHolder.Linked.ENDER_CHEST, alice.toString(), Optional.of(alice)),
                Place.of(ender).get(0));
        InventoryHolder tank = new InventoryHolder.Linked(ResourceLocation.fromNamespaceAndPath("enderstorage", "tank"),
                alice.toString());
        assertEquals(Optional.empty(), ((Place.Nowhere) Place.of(tank).get(0)).owner(), "only its mod's bridge knows a "
                + "network's owner, even where its frequency looks like a UUID");
        assertEquals(Place.Nowhere.account(ResourceHolder.Account.player(alice)),
                Place.of(ResourceHolder.Account.player(alice)).get(0));
    }

    @Test
    void whatNothingIdentifiedIsUnknownWithItsReason() {
        Place unknown = Place.Unknown.anywhere(Place.Unknown.Reason.UNIDENTIFIED_INVENTORY);
        assertEquals(List.of(unknown), Place.of(InventoryHolder.Unknown.INSTANCE));
        assertEquals(List.of(unknown), Place.of(new InventoryHolder.PerPlayer(InventoryHolder.Unknown.INSTANCE,
                new UUID(3, 4))), "whose copy, but not where");
    }

    // ---- Every entity leaf states the places of the entities it names ----------------------------------------------

    /** Getters naming who did it, or the event's own subject: never a place the leaf must add. */
    private static final Set<String> AGENTS = Set.of("getDamager", "getShooter", "getOwner", "getBreeder",
            "getViewer", "getActingPlayer", "getActor", "getEntity");

    /** Getters that are another name for {@code getEntity()}, the subject the family already places. */
    private static final Set<String> SUBJECT = Set.of("PlayerDeathEvent.getPlayer", "PlayerMoveEvent.getPlayer");

    /**
     * Each concrete entity event in {@code api}, and each public getter it has that names an entity (directly, in an
     * {@code Optional} or in a collection): the leaf that declares the getter, or one below it, declares
     * {@code getPlaces()}, so a leaf can't add an entity and forget its place. The in-game
     * {@code PlaceTests.everyEntityAnEntityEventNamesIsOneOfItsPlaces} checks the places themselves.
     */
    @Test
    void everyEntityLeafThatNamesAnEntityStatesItsPlaces() throws Exception {
        List<Class<?>> leaves = new ArrayList<>();
        for (String name : eventClasses()) {
            Class<?> c = Class.forName(name, false, PlacesTest.class.getClassLoader());
            if (EntityEvent.class.isAssignableFrom(c) && !Modifier.isAbstract(c.getModifiers())) leaves.add(c);
        }
        assertTrue(leaves.size() > 30, "the scan should find every entity event, but found " + leaves);
        List<String> forgotten = new ArrayList<>();
        int checked = 0;
        for (Class<?> leaf : leaves) {
            Class<?> placedBy = leaf.getMethod("getPlaces").getDeclaringClass();
            for (Method m : leaf.getMethods()) {
                if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers()) || !m.getName().startsWith("get")
                        || AGENTS.contains(m.getName()) || SUBJECT.contains(leaf.getSimpleName() + "." + m.getName())
                        || m.getName().equals("getPlayer") && m.getReturnType() == Optional.class
                        || !namesEntities(m.getGenericReturnType())) {
                    continue;
                }
                checked++;
                if (!m.getDeclaringClass().isAssignableFrom(placedBy)) {
                    forgotten.add(leaf.getSimpleName() + "." + m.getName() + "() names an entity, but getPlaces() is "
                            + "declared above it, in " + placedBy.getSimpleName() + ": override getPlaces() in "
                            + m.getDeclaringClass().getSimpleName() + " to add its place");
                }
            }
        }
        assertTrue(checked > 10, "the check should reach the leaves that name entities, but reached " + checked);
        assertTrue(forgotten.isEmpty(), String.join("\n", forgotten));
    }

    private static boolean namesEntities(Type type) {
        // What a move carries names entities too.
        if (type instanceof Class<?> c) return Entity.class.isAssignableFrom(c) || c == Carried.class;
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw
                && (raw == Optional.class || Collection.class.isAssignableFrom(raw))) {
            Type arg = p.getActualTypeArguments()[0];
            if (arg instanceof WildcardType w) arg = w.getUpperBounds()[0];
            return namesEntities(arg);
        }
        return false;
    }

    /** Every class under {@code dev.iredsc.hadron.api.event}, from wherever {@code api}'s classes are. */
    private static List<String> eventClasses() throws IOException, URISyntaxException {
        Path root = Path.of(EntityEvent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path events = root.resolve("dev/iredsc/hadron/api/event");
        assertTrue(Files.isDirectory(events), "api's classes should be a directory in a fast test, not " + root);
        try (Stream<Path> files = Files.walk(events)) {
            return files.map(root::relativize).map(Path::toString).filter(f -> f.endsWith(".class"))
                    .map(f -> f.substring(0, f.length() - ".class".length()).replace('/', '.')).toList();
        }
    }
}
