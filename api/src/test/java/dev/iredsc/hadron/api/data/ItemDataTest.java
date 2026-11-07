package dev.iredsc.hadron.api.data;

import dev.iredsc.hadron.test.Covers;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.test.WithMinecraft;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/** Persistent data on item stacks, the one holder that needs no world: every type, and how mistakes are refused. */
@WithMinecraft
class ItemDataTest {
    private static final NamespacedKey KEY = new NamespacedKey("test", "value");
    private static final NamespacedKey OTHER = new NamespacedKey("test", "other");

    private static PersistentDataContainer data() {
        return PersistentData.of(new ItemStack(Items.DIAMOND));
    }

    private static <P, C> C roundTrip(PersistentDataType<P, C> type, C value) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        PersistentData.of(stack).set(KEY, type, value);
        // A fresh container on the same stack: the value lives in the stack, not the container object.
        PersistentDataContainer again = PersistentData.of(stack);
        assertTrue(again.has(KEY, type));
        return again.get(KEY, type);
    }
    @Test
    void everyScalarTypeReadsBackWhatWasSet() {
        assertEquals((byte) -7, roundTrip(PersistentDataType.BYTE, (byte) -7));
        assertEquals((short) 30000, roundTrip(PersistentDataType.SHORT, (short) 30000));
        assertEquals(Integer.MIN_VALUE, roundTrip(PersistentDataType.INTEGER, Integer.MIN_VALUE));
        assertEquals(Long.MAX_VALUE, roundTrip(PersistentDataType.LONG, Long.MAX_VALUE));
        assertEquals(1.5f, roundTrip(PersistentDataType.FLOAT, 1.5f));
        assertEquals(-0.25, roundTrip(PersistentDataType.DOUBLE, -0.25));
        assertEquals(true, roundTrip(PersistentDataType.BOOLEAN, true));
        assertEquals(false, roundTrip(PersistentDataType.BOOLEAN, false));
        assertEquals("héllo", roundTrip(PersistentDataType.STRING, "héllo"));
        UUID id = new UUID(0x1234_5678L, 0x9abc_def0L);
        assertEquals(id, roundTrip(PersistentDataType.UUID, id));
    }
    @Test
    void arraysReadBackAndAreCopiedBothWays() {
        assertArrayEquals(new byte[] {1, 2}, roundTrip(PersistentDataType.BYTE_ARRAY, new byte[] {1, 2}));
        assertArrayEquals(new long[] {3L, 4L}, roundTrip(PersistentDataType.LONG_ARRAY, new long[] {3L, 4L}));
        PersistentDataContainer data = data();
        int[] stored = {1, 2, 3};
        data.set(KEY, PersistentDataType.INTEGER_ARRAY, stored);
        stored[0] = 99; // changing the array after set doesn't change what's stored
        int[] read = data.get(KEY, PersistentDataType.INTEGER_ARRAY);
        assertArrayEquals(new int[] {1, 2, 3}, read);
        read[1] = 99; // nor does changing what get returned
        assertArrayEquals(new int[] {1, 2, 3}, data.get(KEY, PersistentDataType.INTEGER_ARRAY));
    }
    @Test
    void booleansAreStoredAsBytesAsInBukkit() {
        PersistentDataContainer data = data();
        data.set(KEY, PersistentDataType.BOOLEAN, true);
        assertEquals((byte) 1, data.get(KEY, PersistentDataType.BYTE));
        data.set(KEY, PersistentDataType.BYTE, (byte) 5);
        assertEquals(true, data.get(KEY, PersistentDataType.BOOLEAN));
    }
    @Test
    void aUuidNeedsExactlyFourInts() {
        PersistentDataContainer data = data();
        data.set(KEY, PersistentDataType.INTEGER_ARRAY, new int[] {1, 2, 3});
        assertFalse(data.has(KEY, PersistentDataType.UUID));
        data.set(KEY, PersistentDataType.INTEGER_ARRAY, new int[] {1, 2, 3, 4});
        assertTrue(data.has(KEY, PersistentDataType.UUID));
    }

    @Covers(value = PersistentDataContainer.class, method = "get")
    @Test
    void readingWithTheWrongTypeSaysBothTypes() {
        PersistentDataContainer data = data();
        data.set(KEY, PersistentDataType.STRING, "x");
        assertFalse(data.has(KEY, PersistentDataType.INTEGER));
        assertTrue(data.has(KEY));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> data.get(KEY, PersistentDataType.INTEGER));
        assertTrue(e.getMessage().contains("stored as STRING"), e.getMessage());
        assertTrue(e.getMessage().contains("read as INTEGER"), e.getMessage());
    }

    @Covers(value = PersistentDataContainer.class, method = "get")
    @Covers(value = PersistentDataContainer.class, method = "getOrDefault")
    @Test
    void aMissingKeyReadsAsNullOrTheDefault() {
        PersistentDataContainer data = data();
        assertNull(data.get(KEY, PersistentDataType.STRING));
        assertEquals("d", data.getOrDefault(KEY, PersistentDataType.STRING, "d"));
        assertFalse(data.has(KEY));
        assertTrue(data.isEmpty());
    }

    @Covers(value = PersistentDataContainer.class, method = "set")
    @Test
    void storingNullIsRefusedPointingAtRemove() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> data().set(KEY, PersistentDataType.STRING, null));
        assertTrue(e.getMessage().contains("remove(key)"), e.getMessage());
    }

    @Covers(value = PersistentData.class, method = "of")
    @Test
    void writingToAnEmptyStackIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PersistentData.of(ItemStack.EMPTY).set(KEY, PersistentDataType.INTEGER, 1));
        assertTrue(e.getMessage().contains("empty item stack"), e.getMessage());
        assertNull(PersistentData.of(ItemStack.EMPTY).get(KEY, PersistentDataType.INTEGER));
    }

    @Covers(value = PersistentDataContainer.class, method = "remove")
    @Test
    void removingTheLastValueLeavesTheStackAsIfNeverTouched() {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        PersistentData.of(stack).set(KEY, PersistentDataType.INTEGER, 1);
        assertFalse(ItemStack.isSameItemSameComponents(stack, new ItemStack(Items.DIAMOND)));
        PersistentData.of(stack).remove(KEY);
        assertTrue(ItemStack.isSameItemSameComponents(stack, new ItemStack(Items.DIAMOND)),
                "a stack with its data removed must stack with a fresh one again");
    }

    @Covers(value = PersistentData.class, method = "of")
    @Test
    void hadronsDataSitsUnderItsOwnCustomDataKey() {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        PersistentData.of(stack).set(KEY, PersistentDataType.INTEGER, 7);
        assertEquals(7, stack.get(DataComponents.CUSTOM_DATA).copyTag().getCompound("hadron").getCompound("test")
                .getInt("value"));
    }

    @Covers(value = PersistentDataContainer.class, method = "getKeys")
    @Test
    void keysListEveryStoredKey() {
        PersistentDataContainer data = data();
        data.set(KEY, PersistentDataType.INTEGER, 1);
        data.set(new NamespacedKey("other", "x"), PersistentDataType.INTEGER, 2);
        assertEquals(Set.of(KEY, new NamespacedKey("other", "x")), data.getKeys());
        assertThrows(UnsupportedOperationException.class, () -> data.getKeys().clear());
    }
    @Covers(value = ListPersistentDataTypeProvider.class, method = "listTypeFrom")
    @Test
    void listsOfEveryKindReadBack() {
        assertEquals(List.of("a", "b"), roundTrip(PersistentDataType.LIST.strings(), List.of("a", "b")));
        assertEquals(List.of(1, 2, 3), roundTrip(PersistentDataType.LIST.integers(), List.of(1, 2, 3)));
        assertEquals(List.of(), roundTrip(PersistentDataType.LIST.longs(), List.of()));
        assertEquals(List.of(true, false), roundTrip(PersistentDataType.LIST.booleans(), List.of(true, false)));
        var nested = PersistentDataType.LIST.listTypeFrom(PersistentDataType.LIST.strings());
        assertEquals(List.of(List.of("x"), List.of()), roundTrip(nested, List.of(List.of("x"), List.of())));
    }

    @Covers(value = PersistentDataContainer.class, method = "has")
    @Test
    void aListOfStringsIsNotAListOfInts() {
        PersistentDataContainer data = data();
        data.set(KEY, PersistentDataType.LIST.strings(), List.of("a"));
        assertFalse(data.has(KEY, PersistentDataType.LIST.integers()));
        assertThrows(IllegalArgumentException.class, () -> data.get(KEY, PersistentDataType.LIST.integers()));
    }

    @Covers(value = PersistentDataContainer.class, method = "get")
    @Test
    void aStoredListNamesItsElementsOrSaysItIsEmpty() {
        PersistentDataContainer data = data();
        data.set(KEY, PersistentDataType.LIST.strings(), List.of("a"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> data.get(KEY, PersistentDataType.INTEGER));
        assertTrue(e.getMessage().contains("stored as LIST of STRING,"), e.getMessage());
        data.set(KEY, PersistentDataType.LIST.strings(), List.of());
        e = assertThrows(IllegalArgumentException.class, () -> data.get(KEY, PersistentDataType.INTEGER));
        assertTrue(e.getMessage().contains("stored as an empty LIST,"), e.getMessage());
    }

    @Covers(value = PersistentDataContainer.class, method = "has")
    @Test
    void containerArraysFitOnlyListsOfContainers() {
        PersistentDataContainer data = data();
        data.set(KEY, PersistentDataType.LIST.strings(), List.of("a"));
        assertFalse(data.has(KEY, PersistentDataType.TAG_CONTAINER_ARRAY));
        data.set(KEY, PersistentDataType.LIST.strings(), List.of());
        assertTrue(data.has(KEY, PersistentDataType.TAG_CONTAINER_ARRAY), "an empty list is an empty array of any kind");
    }

    @Covers(value = PersistentDataContainer.class, method = "has")
    @Test
    void adaptedAndBukkitStyleTypesFitOnlyTheirOwnPrimitive() {
        PersistentDataType<Long, Instant> instant = PersistentDataType.adapt(PersistentDataType.LONG, Instant.class,
                Instant::ofEpochMilli, Instant::toEpochMilli);
        PersistentDataContainer data = data();
        data.set(KEY, PersistentDataType.INTEGER, 1);
        assertFalse(data.has(KEY, instant), "an INTEGER isn't the LONG an Instant is stored as");
        assertFalse(data.has(KEY, BUKKIT_STYLE), "an INTEGER isn't the STRING this type is stored as");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> data.get(KEY, instant));
        assertTrue(e.getMessage().contains("read as Instant (stored as LONG)."), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> data.get(KEY, BUKKIT_STYLE));
        assertTrue(e.getMessage().contains("read as Tagged (stored as String)."), e.getMessage());
    }

    @Covers(value = PersistentDataContainer.class, method = "isEmpty")
    @Test
    void aContainerHoldingAValueIsNotEmpty() {
        PersistentDataContainer data = data();
        data.set(KEY, PersistentDataType.INTEGER, 1);
        assertFalse(data.isEmpty());
        PersistentDataContainer built = PersistentData.newContainer();
        built.set(KEY, PersistentDataType.INTEGER, 1);
        assertFalse(built.isEmpty());
    }

    @Covers(value = PersistentDataType.class, method = "adapt")
    @Test
    void anAdaptedTypeStoresAsItsBase() {
        PersistentDataType<Long, Instant> instant = PersistentDataType.adapt(PersistentDataType.LONG, Instant.class,
                Instant::ofEpochMilli, Instant::toEpochMilli);
        PersistentDataContainer data = data();
        data.set(KEY, instant, Instant.ofEpochMilli(1234));
        assertEquals(Instant.ofEpochMilli(1234), data.get(KEY, instant));
        assertEquals(1234L, data.get(KEY, PersistentDataType.LONG));
        assertEquals(Long.class, instant.getPrimitiveType());
        assertEquals(Instant.class, instant.getComplexType());
    }

    @Covers(value = PersistentDataType.class, method = "adapt")
    @Test
    void anAdaptedTypeThatReturnsNullSaysWhichFunction() {
        PersistentDataType<String, Object> nullTo = PersistentDataType.adapt(PersistentDataType.STRING, Object.class,
                s -> s, o -> null);
        NullPointerException e = assertThrows(NullPointerException.class, () -> data().set(KEY, nullTo, "x"));
        assertTrue(e.getMessage().contains("'to' function"), e.getMessage());
        PersistentDataType<String, Object> nullFrom = PersistentDataType.adapt(PersistentDataType.STRING,
                Object.class, s -> null, o -> (String) o);
        PersistentDataContainer data = data();
        data.set(KEY, nullFrom, "x");
        e = assertThrows(NullPointerException.class, () -> data.get(KEY, nullFrom));
        assertTrue(e.getMessage().contains("'from' function"), e.getMessage());
    }

    /** A Bukkit-style type, implemented by hand on a built-in primitive. */
    private record Tagged(String text) {}

    private static final PersistentDataType<String, Tagged> BUKKIT_STYLE = new PersistentDataType<>() {
        @Override
        public Class<String> getPrimitiveType() {
            return String.class;
        }

        @Override
        public Class<Tagged> getComplexType() {
            return Tagged.class;
        }

        @Override
        public String toPrimitive(Tagged complex, PersistentDataAdapterContext context) {
            return complex.text();
        }

        @Override
        public Tagged fromPrimitive(String primitive, PersistentDataAdapterContext context) {
            return new Tagged(primitive);
        }
    };

    @Covers(value = PersistentDataType.class, method = "getPrimitiveType")
    @Test
    void aBukkitStyleTypeOnABuiltInPrimitiveWorks() {
        assertEquals(new Tagged("hi"), roundTrip(BUKKIT_STYLE, new Tagged("hi")));
    }

    @Covers(value = PersistentDataType.class, method = "getPrimitiveType")
    @Test
    void aBukkitStyleTypeOnAnUnknownPrimitiveIsRefusedPointingAtAdapt() {
        PersistentDataType<Instant, Instant> unknown = new PersistentDataType<>() {
            @Override
            public Class<Instant> getPrimitiveType() {
                return Instant.class;
            }

            @Override
            public Class<Instant> getComplexType() {
                return Instant.class;
            }

            @Override
            public Instant toPrimitive(Instant complex, PersistentDataAdapterContext context) {
                return complex;
            }

            @Override
            public Instant fromPrimitive(Instant primitive, PersistentDataAdapterContext context) {
                return primitive;
            }
        };
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> data().set(KEY, unknown, Instant.EPOCH));
        assertTrue(e.getMessage().contains("PersistentDataType.adapt"), e.getMessage());
    }
    @Test
    void aNestedContainerReadIsLiveAndAnUnsetOneIsCopied() {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        PersistentDataContainer data = PersistentData.of(stack);
        PersistentDataContainer built = data.getAdapterContext().newPersistentDataContainer();
        built.set(OTHER, PersistentDataType.INTEGER, 1);
        data.set(KEY, PersistentDataType.TAG_CONTAINER, built);
        built.set(OTHER, PersistentDataType.INTEGER, 2); // set copied it
        PersistentDataContainer live = data.get(KEY, PersistentDataType.TAG_CONTAINER);
        assertEquals(1, live.get(OTHER, PersistentDataType.INTEGER));
        live.set(OTHER, PersistentDataType.INTEGER, 3); // a container got back writes through to the stack
        assertEquals(3, PersistentData.of(stack).get(KEY, PersistentDataType.TAG_CONTAINER)
                .get(OTHER, PersistentDataType.INTEGER));
    }
    @Test
    void aLiveNestedContainerWhoseKeyWasOverwrittenSaysToGetItAgain() {
        PersistentDataContainer data = data();
        data.set(KEY, PersistentDataType.TAG_CONTAINER, PersistentData.newContainer());
        PersistentDataContainer live = data.get(KEY, PersistentDataType.TAG_CONTAINER);
        data.set(KEY, PersistentDataType.STRING, "replaced");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> live.set(OTHER, PersistentDataType.INTEGER, 1));
        assertTrue(e.getMessage().contains("get the container again"), e.getMessage());
    }
    @Test
    void containerArraysReadBackAsCopies() {
        PersistentDataContainer one = PersistentData.newContainer();
        one.set(OTHER, PersistentDataType.STRING, "a");
        PersistentDataContainer[] read = roundTrip(PersistentDataType.TAG_CONTAINER_ARRAY,
                new PersistentDataContainer[] {one, PersistentData.newContainer()});
        assertEquals(2, read.length);
        assertEquals("a", read[0].get(OTHER, PersistentDataType.STRING));
        assertTrue(read[1].isEmpty());
        assertThrows(NullPointerException.class, () -> data().set(KEY, PersistentDataType.TAG_CONTAINER_ARRAY,
                new PersistentDataContainer[] {null}));
    }
    @Test
    void storingAContainerNotFromHadronIsRefused() {
        PersistentDataContainer foreign = (PersistentDataContainer) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {PersistentDataContainer.class}, (p, m, a) -> null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> data().set(KEY, PersistentDataType.TAG_CONTAINER, foreign));
        assertTrue(e.getMessage().contains("newPersistentDataContainer"), e.getMessage());
    }

    @Covers(value = PersistentDataContainer.class, method = "copyTo")
    @Test
    void copyToReplacesOnlyWhenAsked() {
        PersistentDataContainer from = data();
        from.set(KEY, PersistentDataType.INTEGER, 1);
        from.set(OTHER, PersistentDataType.INTEGER, 2);
        PersistentDataContainer keep = data();
        keep.set(KEY, PersistentDataType.INTEGER, 9);
        from.copyTo(keep, false);
        assertEquals(9, keep.get(KEY, PersistentDataType.INTEGER));
        assertEquals(2, keep.get(OTHER, PersistentDataType.INTEGER));
        PersistentDataContainer replace = data();
        replace.set(KEY, PersistentDataType.INTEGER, 9);
        from.copyTo(replace, true);
        assertEquals(1, replace.get(KEY, PersistentDataType.INTEGER));
    }
    @Test
    void containersWithTheSameDataAreEqual() {
        PersistentDataContainer a = data();
        PersistentDataContainer b = PersistentData.newContainer();
        assertEquals(a, b); // both empty
        a.set(KEY, PersistentDataType.INTEGER, 1);
        assertNotEquals(a, b);
        b.set(KEY, PersistentDataType.INTEGER, 1);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        b.set(KEY, PersistentDataType.INTEGER, 2);
        assertNotEquals(a.hashCode(), b.hashCode(), "different data spreads across hash buckets");
    }

    @Test
    void anUntouchedStackAndANewContainerAreEqualEitherWayRound() {
        PersistentDataContainer untouched = data();
        PersistentDataContainer built = PersistentData.newContainer();
        assertEquals(untouched, built);
        assertEquals(built, untouched);
        assertEquals(untouched.hashCode(), built.hashCode());
    }

    @Covers(value = PersistentData.class, method = "of")
    @Test
    void worldHoldersNeedTheModRunning() {
        // No port is installed in a fast test; the error says so rather than failing obscurely.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PersistentData.Port.Missing().chunk(null));
        assertTrue(e.getMessage().contains("item stacks work without it"), e.getMessage());
    }
    @Test
    void typesNameThemselvesInErrors() {
        assertEquals("PersistentDataType.STRING", PersistentDataType.STRING.toString());
        assertEquals("LIST of INTEGER", Values.describe(PersistentDataType.LIST.integers()));
    }
}
