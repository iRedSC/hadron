package dev.iredsc.hadron.selftest.platform;

import dev.iredsc.hadron.api.data.ListPersistentDataType;
import dev.iredsc.hadron.api.data.ListPersistentDataTypeProvider;
import dev.iredsc.hadron.api.data.NamespacedKey;
import dev.iredsc.hadron.api.data.PersistentData;
import dev.iredsc.hadron.api.data.PersistentDataAdapterContext;
import dev.iredsc.hadron.api.data.PersistentDataContainer;
import dev.iredsc.hadron.api.data.PersistentDataType;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** The rest of the persistent data API: container operations, keys, list types, custom types and moving data. */
public final class DataApiTests {
    private static NamespacedKey key(TestContext t, String name) {
        return new NamespacedKey(t.plugin(), name);
    }

    @HadronTest
    @Covers(value = PersistentDataContainer.class, method = "getKeys")
    @Covers(value = PersistentDataContainer.class, method = "getOrDefault")
    @Covers(value = PersistentDataContainer.class, method = "isEmpty")
    @Covers(value = PersistentDataContainer.class, method = "remove")
    @Covers(value = PersistentDataContainer.class, method = "has")
    @Covers(value = PersistentDataContainer.class, method = "copyTo")
    @Covers(value = PersistentData.class, method = "newContainer")
    void containerOperationsDoWhatTheySay(TestContext t) {
        PersistentDataContainer data = PersistentData.newContainer();
        t.assertTrue(data.isEmpty(), "a new container is empty");
        data.set(key(t, "name"), PersistentDataType.STRING, "shop");
        data.set(key(t, "count"), PersistentDataType.INTEGER, 4);
        t.assertFalse(data.isEmpty(), "a container with values isn't empty");
        t.assertEquals(Set.of(key(t, "name"), key(t, "count")), data.getKeys(), "the keys");
        t.assertTrue(data.has(key(t, "count"), PersistentDataType.INTEGER), "has with the type it was set with");
        t.assertFalse(data.has(key(t, "count"), PersistentDataType.STRING), "has with another type");
        t.assertEquals(9, data.getOrDefault(key(t, "missing"), PersistentDataType.INTEGER, 9), "getOrDefault, missing");
        t.assertEquals(4, data.getOrDefault(key(t, "count"), PersistentDataType.INTEGER, 9), "getOrDefault, present");
        PersistentDataContainer other = PersistentData.newContainer();
        other.set(key(t, "name"), PersistentDataType.STRING, "kept");
        data.copyTo(other, false);
        t.assertEquals("kept", other.get(key(t, "name"), PersistentDataType.STRING), "copyTo without replace keeps");
        t.assertEquals(4, other.get(key(t, "count"), PersistentDataType.INTEGER), "copyTo copies the rest");
        data.copyTo(other, true);
        t.assertEquals("shop", other.get(key(t, "name"), PersistentDataType.STRING), "copyTo with replace overwrites");
        data.remove(key(t, "name"));
        data.remove(key(t, "never-set"));
        t.assertFalse(data.has(key(t, "name")), "a removed key");
        t.assertEquals(Set.of(key(t, "count")), data.getKeys(), "the keys after a remove");
    }

    @HadronTest
    @Covers(value = NamespacedKey.class, method = "getNamespace")
    @Covers(value = NamespacedKey.class, method = "getKey")
    @Covers(value = NamespacedKey.class, method = "asString")
    @Covers(value = NamespacedKey.class, method = "fromString")
    void keysAreThePluginsNamespaceAndParseBack(TestContext t) {
        NamespacedKey k = new NamespacedKey(t.plugin(), "homes/main");
        t.assertEquals("hadronselftest", k.getNamespace(), "a plugin key's namespace is its lowercased name");
        t.assertEquals("homes/main", k.getKey(), "the key");
        t.assertEquals("hadronselftest:homes/main", k.asString(), "asString");
        t.assertEquals(k, NamespacedKey.fromString(k.asString()), "fromString parses asString back");
        t.assertEquals(null, NamespacedKey.fromString("no colon"), "fromString for something that isn't a key");
        t.assertEquals(null, NamespacedKey.fromString("Bad:Key"), "fromString for invalid characters");
    }

    @HadronTest
    @Covers(value = ListPersistentDataTypeProvider.class, method = "bytes")
    @Covers(value = ListPersistentDataTypeProvider.class, method = "shorts")
    @Covers(value = ListPersistentDataTypeProvider.class, method = "integers")
    @Covers(value = ListPersistentDataTypeProvider.class, method = "longs")
    @Covers(value = ListPersistentDataTypeProvider.class, method = "floats")
    @Covers(value = ListPersistentDataTypeProvider.class, method = "doubles")
    @Covers(value = ListPersistentDataTypeProvider.class, method = "booleans")
    @Covers(value = ListPersistentDataTypeProvider.class, method = "strings")
    @Covers(value = ListPersistentDataTypeProvider.class, method = "uuids")
    @Covers(value = ListPersistentDataTypeProvider.class, method = "byteArrays")
    @Covers(value = ListPersistentDataTypeProvider.class, method = "integerArrays")
    @Covers(value = ListPersistentDataTypeProvider.class, method = "longArrays")
    @Covers(value = ListPersistentDataTypeProvider.class, method = "dataContainers")
    @Covers(value = ListPersistentDataTypeProvider.class, method = "listTypeFrom")
    @Covers(value = ListPersistentDataType.class, method = "elementType")
    void everyListTypeStoresAndReadsBackItsList(TestContext t) {
        PersistentDataContainer data = PersistentData.of(t.level()); // a real holder, so values go through NBT
        ListPersistentDataTypeProvider list = PersistentDataType.LIST;
        roundTrip(t, data, "bytes", list.bytes(), List.of((byte) 1, (byte) -2));
        roundTrip(t, data, "shorts", list.shorts(), List.of((short) 300));
        roundTrip(t, data, "integers", list.integers(), List.of(1, 2, 3));
        roundTrip(t, data, "longs", list.longs(), List.of(9000000000L));
        roundTrip(t, data, "floats", list.floats(), List.of(0.5f));
        roundTrip(t, data, "doubles", list.doubles(), List.of(2.25));
        roundTrip(t, data, "booleans", list.booleans(), List.of(true, false));
        roundTrip(t, data, "strings", list.strings(), List.of("a", "b"));
        roundTrip(t, data, "uuids", list.uuids(), List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")));
        data.set(key(t, "byte_arrays"), list.byteArrays(), List.of(new byte[] {1, 2}));
        t.assertTrue(Arrays.equals(new byte[] {1, 2}, data.get(key(t, "byte_arrays"), list.byteArrays()).get(0)),
                "byte_arrays");
        data.set(key(t, "int_arrays"), list.integerArrays(), List.of(new int[] {7}));
        t.assertTrue(Arrays.equals(new int[] {7}, data.get(key(t, "int_arrays"), list.integerArrays()).get(0)),
                "integer_arrays");
        data.set(key(t, "long_arrays"), list.longArrays(), List.of(new long[] {8L}));
        t.assertTrue(Arrays.equals(new long[] {8L}, data.get(key(t, "long_arrays"), list.longArrays()).get(0)),
                "long_arrays");
        PersistentDataContainer element = PersistentData.newContainer();
        element.set(key(t, "inside"), PersistentDataType.STRING, "nested");
        data.set(key(t, "containers"), list.dataContainers(), List.of(element));
        t.assertEquals("nested", data.get(key(t, "containers"), list.dataContainers()).get(0)
                .get(key(t, "inside"), PersistentDataType.STRING), "dataContainers");
        ListPersistentDataType<List<String>, List<String>> nested = list.listTypeFrom(list.strings());
        roundTrip(t, data, "nested", nested, List.of(List.of("x"), List.of("y", "z")));
        t.assertTrue(nested.elementType() == list.strings(), "a list type's element type");
        t.assertTrue(list.integers().elementType() == PersistentDataType.INTEGER, "integers' element type");
    }

    private static <P, C> void roundTrip(TestContext t, PersistentDataContainer data, String name,
            PersistentDataType<P, C> type, C value) {
        data.set(key(t, name), type, value);
        t.assertEquals(value, data.get(key(t, name), type), "the " + name + " list read back");
    }

    enum Rank { MEMBER, ADMIN }

    /** PersistentDataType's Javadoc example, word for word: a plugin's own type kept as a constant compiles. */
    static final PersistentDataType<String, Rank> RANK =
            PersistentDataType.adapt(PersistentDataType.STRING, Rank.class, Rank::valueOf, Rank::name);

    /** A plugin's own type, as Bukkit plugins write them: a rank stored as its name. */
    static final class RankType implements PersistentDataType<String, Rank> {
        int toCalls;
        int fromCalls;

        @Override
        public Class<String> getPrimitiveType() {
            return String.class;
        }

        @Override
        public Class<Rank> getComplexType() {
            return Rank.class;
        }

        @Override
        public String toPrimitive(Rank complex, PersistentDataAdapterContext context) {
            toCalls++;
            return complex.name();
        }

        @Override
        public Rank fromPrimitive(String primitive, PersistentDataAdapterContext context) {
            fromCalls++;
            return Rank.valueOf(primitive);
        }
    }

    /** A type stored as a nested container it builds with the adapter context, as Bukkit's location types do. */
    static final class PairType implements PersistentDataType<PersistentDataContainer, int[]> {
        final NamespacedKey a;
        final NamespacedKey b;

        PairType(NamespacedKey a, NamespacedKey b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public Class<PersistentDataContainer> getPrimitiveType() {
            return PersistentDataContainer.class;
        }

        @Override
        public Class<int[]> getComplexType() {
            return int[].class;
        }

        @Override
        public PersistentDataContainer toPrimitive(int[] complex, PersistentDataAdapterContext context) {
            PersistentDataContainer c = context.newPersistentDataContainer();
            c.set(a, PersistentDataType.INTEGER, complex[0]);
            c.set(b, PersistentDataType.INTEGER, complex[1]);
            return c;
        }

        @Override
        public int[] fromPrimitive(PersistentDataContainer primitive, PersistentDataAdapterContext context) {
            return new int[] {primitive.get(a, PersistentDataType.INTEGER), primitive.get(b, PersistentDataType.INTEGER)};
        }
    }

    @HadronTest
    @Covers(value = PersistentDataType.class, method = "getPrimitiveType")
    @Covers(value = PersistentDataType.class, method = "getComplexType")
    @Covers(value = PersistentDataType.class, method = "toPrimitive")
    @Covers(value = PersistentDataType.class, method = "fromPrimitive")
    @Covers(value = PersistentDataType.class, method = "adapt")
    @Covers(value = PersistentDataContainer.class, method = "getAdapterContext")
    @Covers(value = PersistentDataAdapterContext.class, method = "newPersistentDataContainer")
    void customTypesAreStoredThroughTheirPrimitive(TestContext t) {
        PersistentDataContainer data = PersistentData.of(t.level());
        RankType rank = new RankType();
        data.set(key(t, "rank"), rank, Rank.ADMIN);
        t.assertEquals(Rank.ADMIN, data.get(key(t, "rank"), rank), "a custom type read back");
        t.assertTrue(rank.toCalls == 1 && rank.fromCalls == 1, "set and get should go through the type's "
                + "toPrimitive and fromPrimitive once each, but called them " + rank.toCalls + " and " + rank.fromCalls);
        t.assertEquals("ADMIN", data.get(key(t, "rank"), PersistentDataType.STRING), "what it is stored as");
        PersistentDataType<String, Rank> adapted = RANK;
        t.assertTrue(adapted.getPrimitiveType() == String.class && adapted.getComplexType() == Rank.class,
                "an adapted type's classes");
        t.assertEquals(Rank.ADMIN, data.get(key(t, "rank"), adapted), "the same value read with an adapted type");
        t.assertEquals("MEMBER", adapted.toPrimitive(Rank.MEMBER, data.getAdapterContext()), "adapt's toPrimitive");
        t.assertEquals(Rank.MEMBER, adapted.fromPrimitive("MEMBER", data.getAdapterContext()), "adapt's fromPrimitive");
        PairType pair = new PairType(key(t, "x"), key(t, "z"));
        data.set(key(t, "pair"), pair, new int[] {3, -4});
        t.assertTrue(Arrays.equals(new int[] {3, -4}, data.get(key(t, "pair"), pair)), "a type stored as a nested "
                + "container built with the adapter context");
        PersistentDataContainer made = data.getAdapterContext().newPersistentDataContainer();
        t.assertTrue(made.isEmpty(), "the adapter context makes empty containers");
    }

    @HadronTest
    @Covers(value = PersistentData.class, method = "move")
    void moveTakesAPositionsDataToItsNewPlace(TestContext t) {
        BlockPos from = t.pos(1, 0, 0);
        BlockPos to = t.pos(4, 0, 0);
        t.setBlock(from, Blocks.DIRT);
        PersistentData.of(t.level(), from).set(key(t, "mark"), PersistentDataType.STRING, "placed by alice");
        // A contraption mod moving the block: data first, then the block, as bridges must.
        PersistentData.move(t.level(), from, to);
        t.setBlock(to, Blocks.DIRT);
        t.setBlock(from, Blocks.AIR);
        t.assertEquals("placed by alice", PersistentData.of(t.level(), to).get(key(t, "mark"), PersistentDataType.STRING),
                "the mark at the new position");
        t.assertFalse(PersistentData.of(t.level(), from).has(key(t, "mark")), "the mark at the old position");
    }
}
