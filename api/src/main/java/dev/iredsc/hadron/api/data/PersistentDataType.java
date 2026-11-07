package dev.iredsc.hadron.api.data;

import java.util.Objects;
import java.util.function.Function;

/**
 * How a value is stored in a {@link PersistentDataContainer}, as in Bukkit: {@code P} is the primitive it is stored
 * as, {@code C} the type a plugin works with. Hadron owns how primitives are written, so stored data never changes
 * layout under a plugin and a Minecraft version port never migrates it.
 *
 * <p>Built in: {@link #BYTE}, {@link #SHORT}, {@link #INTEGER}, {@link #LONG}, {@link #FLOAT}, {@link #DOUBLE},
 * {@link #BOOLEAN}, {@link #STRING}, {@link #UUID}, {@link #BYTE_ARRAY}, {@link #INTEGER_ARRAY}, {@link #LONG_ARRAY},
 * {@link #TAG_CONTAINER} (a nested container), and lists of any of these through {@link #LIST}.
 *
 * <p>A plugin's own type is built on one of those with {@link #adapt}, so nothing stored references the plugin's
 * classes and a reloaded plugin reads its data back:
 *
 * <pre>{@code
 * static final PersistentDataType<String, Rank> RANK =
 *         PersistentDataType.adapt(PersistentDataType.STRING, Rank.class, Rank::valueOf, Rank::name);
 * }</pre>
 *
 * A Bukkit-style implementation of this interface works too, when its primitive type is one of the built-in ones.
 */
public interface PersistentDataType<P, C> {
    PersistentDataType<Byte, Byte> BYTE = Builtin.BYTE;
    PersistentDataType<Short, Short> SHORT = Builtin.SHORT;
    PersistentDataType<Integer, Integer> INTEGER = Builtin.INTEGER;
    PersistentDataType<Long, Long> LONG = Builtin.LONG;
    PersistentDataType<Float, Float> FLOAT = Builtin.FLOAT;
    PersistentDataType<Double, Double> DOUBLE = Builtin.DOUBLE;
    /** Stored as a byte, 1 or 0, as in Bukkit. */
    PersistentDataType<Byte, Boolean> BOOLEAN = Builtin.BOOLEAN;
    PersistentDataType<String, String> STRING = Builtin.STRING;
    /** Stored as four ints, the way Minecraft stores UUIDs. */
    PersistentDataType<java.util.UUID, java.util.UUID> UUID = Builtin.UUID;
    PersistentDataType<byte[], byte[]> BYTE_ARRAY = Builtin.BYTE_ARRAY;
    PersistentDataType<int[], int[]> INTEGER_ARRAY = Builtin.INTEGER_ARRAY;
    PersistentDataType<long[], long[]> LONG_ARRAY = Builtin.LONG_ARRAY;
    /**
     * A nested container. One you {@code get} is live: changing it changes the data where it is stored. Set one made
     * with {@link PersistentDataAdapterContext#newPersistentDataContainer()} to store it; that copies it.
     */
    PersistentDataType<PersistentDataContainer, PersistentDataContainer> TAG_CONTAINER = Builtin.TAG_CONTAINER;
    /** Bukkit's older array form; {@code LIST.dataContainers()} is the same data as a list. Elements are copies. */
    PersistentDataType<PersistentDataContainer[], PersistentDataContainer[]> TAG_CONTAINER_ARRAY =
            Builtin.TAG_CONTAINER_ARRAY;
    /** Lists: {@code LIST.strings()}, {@code LIST.integers()}, ..., {@code LIST.listTypeFrom(type)}. */
    ListPersistentDataTypeProvider LIST = new ListPersistentDataTypeProvider();

    Class<P> getPrimitiveType();

    Class<C> getComplexType();

    P toPrimitive(C complex, PersistentDataAdapterContext context);

    C fromPrimitive(P primitive, PersistentDataAdapterContext context);

    /**
     * A plugin's own type, stored as {@code base}. {@code from} turns what's stored into the plugin's type and
     * {@code to} turns it back; neither may return null.
     *
     * <pre>{@code
     * PersistentDataType.adapt(PersistentDataType.LONG, Instant.class, Instant::ofEpochMilli, Instant::toEpochMilli)
     * }</pre>
     */
    static <B, C> PersistentDataType<B, C> adapt(PersistentDataType<?, B> base, Class<C> type, Function<B, C> from,
            Function<C, B> to) {
        return new AdaptedType<>(Objects.requireNonNull(base, "base"), Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(from, "from"), Objects.requireNonNull(to, "to"));
    }
}
