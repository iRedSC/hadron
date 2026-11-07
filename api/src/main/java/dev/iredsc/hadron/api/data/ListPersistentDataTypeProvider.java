package dev.iredsc.hadron.api.data;

import java.util.UUID;

/** {@link PersistentDataType#LIST}: {@code PersistentDataType.LIST.strings()}, or {@code listTypeFrom(type)}. */
public final class ListPersistentDataTypeProvider {
    private static final ListPersistentDataType<Byte, Byte> BYTES = new ListType<>(Builtin.BYTE);
    private static final ListPersistentDataType<Short, Short> SHORTS = new ListType<>(Builtin.SHORT);
    private static final ListPersistentDataType<Integer, Integer> INTEGERS = new ListType<>(Builtin.INTEGER);
    private static final ListPersistentDataType<Long, Long> LONGS = new ListType<>(Builtin.LONG);
    private static final ListPersistentDataType<Float, Float> FLOATS = new ListType<>(Builtin.FLOAT);
    private static final ListPersistentDataType<Double, Double> DOUBLES = new ListType<>(Builtin.DOUBLE);
    private static final ListPersistentDataType<Byte, Boolean> BOOLEANS = new ListType<>(Builtin.BOOLEAN);
    private static final ListPersistentDataType<String, String> STRINGS = new ListType<>(Builtin.STRING);
    private static final ListPersistentDataType<UUID, UUID> UUIDS = new ListType<>(Builtin.UUID);
    private static final ListPersistentDataType<byte[], byte[]> BYTE_ARRAYS =
            new ListType<>(Builtin.BYTE_ARRAY);
    private static final ListPersistentDataType<int[], int[]> INTEGER_ARRAYS =
            new ListType<>(Builtin.INTEGER_ARRAY);
    private static final ListPersistentDataType<long[], long[]> LONG_ARRAYS =
            new ListType<>(Builtin.LONG_ARRAY);
    private static final ListPersistentDataType<PersistentDataContainer, PersistentDataContainer> CONTAINERS =
            new ListType<>(Builtin.TAG_CONTAINER);

    ListPersistentDataTypeProvider() {}

    public ListPersistentDataType<Byte, Byte> bytes() { return BYTES; }

    public ListPersistentDataType<Short, Short> shorts() { return SHORTS; }

    public ListPersistentDataType<Integer, Integer> integers() { return INTEGERS; }

    public ListPersistentDataType<Long, Long> longs() { return LONGS; }

    public ListPersistentDataType<Float, Float> floats() { return FLOATS; }

    public ListPersistentDataType<Double, Double> doubles() { return DOUBLES; }

    public ListPersistentDataType<Byte, Boolean> booleans() { return BOOLEANS; }

    public ListPersistentDataType<String, String> strings() { return STRINGS; }

    public ListPersistentDataType<UUID, UUID> uuids() { return UUIDS; }

    public ListPersistentDataType<byte[], byte[]> byteArrays() { return BYTE_ARRAYS; }

    public ListPersistentDataType<int[], int[]> integerArrays() { return INTEGER_ARRAYS; }

    public ListPersistentDataType<long[], long[]> longArrays() { return LONG_ARRAYS; }

    /** Containers in a list are copies: change one, then set the list again. */
    public ListPersistentDataType<PersistentDataContainer, PersistentDataContainer> dataContainers() {
        return CONTAINERS;
    }

    /** A list of any type, including another list or a custom type. */
    public <P, C> ListPersistentDataType<P, C> listTypeFrom(PersistentDataType<P, C> elementType) {
        return new ListType<>(java.util.Objects.requireNonNull(elementType, "elementType"));
    }

    record ListType<P, C>(PersistentDataType<P, C> elementType) implements ListPersistentDataType<P, C> {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Class<java.util.List<P>> getPrimitiveType() {
            return (Class) java.util.List.class;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Class<java.util.List<C>> getComplexType() {
            return (Class) java.util.List.class;
        }

        @Override
        public java.util.List<P> toPrimitive(java.util.List<C> complex, PersistentDataAdapterContext context) {
            return complex.stream().map(c -> elementType.toPrimitive(c, context)).toList();
        }

        @Override
        public java.util.List<C> fromPrimitive(java.util.List<P> primitive, PersistentDataAdapterContext context) {
            return primitive.stream().map(p -> elementType.fromPrimitive(p, context)).toList();
        }
    }
}
