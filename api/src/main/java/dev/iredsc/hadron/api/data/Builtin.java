package dev.iredsc.hadron.api.data;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** The primitive types, each written as one NBT tag. This layout is Hadron's promise: it never changes. */
@StaticState(reason = "every field is final, and the functions it holds are stateless lambdas and method references")
final class Builtin<P, C> implements PersistentDataType<P, C> {
    static final Builtin<Byte, Byte> BYTE = simple("BYTE", Byte.class, Tag.TAG_BYTE, ByteTag::valueOf,
            t -> ((NumericTag) t).getAsByte());
    static final Builtin<Short, Short> SHORT = simple("SHORT", Short.class, Tag.TAG_SHORT, ShortTag::valueOf,
            t -> ((NumericTag) t).getAsShort());
    static final Builtin<Integer, Integer> INTEGER = simple("INTEGER", Integer.class, Tag.TAG_INT, IntTag::valueOf,
            t -> ((NumericTag) t).getAsInt());
    static final Builtin<Long, Long> LONG = simple("LONG", Long.class, Tag.TAG_LONG, LongTag::valueOf,
            t -> ((NumericTag) t).getAsLong());
    static final Builtin<Float, Float> FLOAT = simple("FLOAT", Float.class, Tag.TAG_FLOAT, FloatTag::valueOf,
            t -> ((NumericTag) t).getAsFloat());
    static final Builtin<Double, Double> DOUBLE = simple("DOUBLE", Double.class, Tag.TAG_DOUBLE, DoubleTag::valueOf,
            t -> ((NumericTag) t).getAsDouble());
    static final Builtin<Byte, Boolean> BOOLEAN = new Builtin<>("BOOLEAN", Byte.class, Boolean.class, Tag.TAG_BYTE,
            t -> true, b -> ByteTag.valueOf(b), t -> ((NumericTag) t).getAsByte() != 0, b -> (byte) (b ? 1 : 0),
            p -> p != 0);
    static final Builtin<String, String> STRING = simple("STRING", String.class, Tag.TAG_STRING, StringTag::valueOf,
            Tag::getAsString);
    static final Builtin<java.util.UUID, java.util.UUID> UUID = new Builtin<>("UUID", java.util.UUID.class,
            java.util.UUID.class, Tag.TAG_INT_ARRAY, t -> ((IntArrayTag) t).size() == 4,
            u -> new IntArrayTag(UUIDUtil.uuidToIntArray(u)),
            t -> UUIDUtil.uuidFromIntArray(((IntArrayTag) t).getAsIntArray()), u -> u, u -> u);
    static final Builtin<byte[], byte[]> BYTE_ARRAY = simple("BYTE_ARRAY", byte[].class, Tag.TAG_BYTE_ARRAY,
            a -> new ByteArrayTag(a.clone()), t -> ((ByteArrayTag) t).getAsByteArray().clone());
    static final Builtin<int[], int[]> INTEGER_ARRAY = simple("INTEGER_ARRAY", int[].class, Tag.TAG_INT_ARRAY,
            a -> new IntArrayTag(a.clone()), t -> ((IntArrayTag) t).getAsIntArray().clone());
    static final Builtin<long[], long[]> LONG_ARRAY = simple("LONG_ARRAY", long[].class, Tag.TAG_LONG_ARRAY,
            a -> new LongArrayTag(a.clone()), t -> ((LongArrayTag) t).getAsLongArray().clone());
    /** Decoded here as a detached copy; {@link TagContainer#get} returns a live view instead. */
    static final Builtin<PersistentDataContainer, PersistentDataContainer> TAG_CONTAINER = simple("TAG_CONTAINER",
            PersistentDataContainer.class, Tag.TAG_COMPOUND, c -> TagContainer.snapshot(c),
            t -> TagContainer.detached(((CompoundTag) t).copy()));
    static final Builtin<PersistentDataContainer[], PersistentDataContainer[]> TAG_CONTAINER_ARRAY = new Builtin<>(
            "TAG_CONTAINER_ARRAY", PersistentDataContainer[].class, PersistentDataContainer[].class, Tag.TAG_LIST,
            t -> ((ListTag) t).isEmpty() || ((ListTag) t).getElementType() == Tag.TAG_COMPOUND,
            Builtin::encodeContainers, Builtin::decodeContainers, a -> a, a -> a);

    private static final java.util.List<Builtin<?, ?>> ALL = java.util.List.of(BYTE, SHORT, INTEGER, LONG, FLOAT,
            DOUBLE, BOOLEAN, STRING, UUID, BYTE_ARRAY, INTEGER_ARRAY, LONG_ARRAY, TAG_CONTAINER, TAG_CONTAINER_ARRAY);

    final String constant;
    private final Class<P> primitive;
    private final Class<C> complex;
    final byte tagId;
    private final Predicate<Tag> fitsMore;
    private final Function<C, Tag> encoder;
    private final Function<Tag, C> decoder;
    private final Function<C, P> toPrimitive;
    private final Function<P, C> fromPrimitive;

    private Builtin(String constant, Class<P> primitive, Class<C> complex, int tagId, Predicate<Tag> fitsMore,
            Function<C, Tag> encoder, Function<Tag, C> decoder, Function<C, P> toPrimitive,
            Function<P, C> fromPrimitive) {
        this.constant = constant;
        this.primitive = primitive;
        this.complex = complex;
        this.tagId = (byte) tagId;
        this.fitsMore = fitsMore;
        this.encoder = encoder;
        this.decoder = decoder;
        this.toPrimitive = toPrimitive;
        this.fromPrimitive = fromPrimitive;
    }

    private static <T> Builtin<T, T> simple(String constant, Class<T> type, int tagId, Function<T, Tag> encoder,
            Function<Tag, T> decoder) {
        return new Builtin<>(constant, type, type, tagId, t -> true, encoder, decoder, v -> v, v -> v);
    }

    /** The built-in type a Bukkit-style custom type is stored as, by its primitive class; null if none. */
    static @Nullable Builtin<?, ?> byPrimitive(Class<?> type) {
        for (Builtin<?, ?> builtin : ALL) {
            if (builtin.complex == type) return builtin;
        }
        return null;
    }

    boolean fits(Tag tag) {
        return tag.getId() == tagId && fitsMore.test(tag);
    }

    Tag encode(C value) {
        return encoder.apply(value);
    }

    C decode(Tag tag) {
        return decoder.apply(tag);
    }

    private static Tag encodeContainers(PersistentDataContainer[] array) {
        ListTag list = new ListTag();
        for (PersistentDataContainer container : array) {
            list.add(TagContainer.snapshot(java.util.Objects.requireNonNull(container,
                    "a TAG_CONTAINER_ARRAY element; leave nulls out of the array")));
        }
        return list;
    }

    private static PersistentDataContainer[] decodeContainers(Tag tag) {
        ListTag list = (ListTag) tag;
        PersistentDataContainer[] out = new PersistentDataContainer[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = TagContainer.detached(list.getCompound(i).copy());
        return out;
    }

    @Override
    public Class<P> getPrimitiveType() {
        return primitive;
    }

    @Override
    public Class<C> getComplexType() {
        return complex;
    }

    @Override
    public P toPrimitive(C value, PersistentDataAdapterContext context) {
        return toPrimitive.apply(value);
    }

    @Override
    public C fromPrimitive(P value, PersistentDataAdapterContext context) {
        return fromPrimitive.apply(value);
    }

    @Override
    public String toString() {
        return "PersistentDataType." + constant;
    }
}
