package dev.iredsc.hadron.api.data;

import java.util.function.Function;

/** What {@link PersistentDataType#adapt} returns. */
record AdaptedType<B, C>(PersistentDataType<?, B> base, Class<C> type, Function<B, C> from, Function<C, B> to)
        implements PersistentDataType<B, C> {
    @Override
    public Class<B> getPrimitiveType() {
        return base.getComplexType();
    }

    @Override
    public Class<C> getComplexType() {
        return type;
    }

    @Override
    public B toPrimitive(C complex, PersistentDataAdapterContext context) {
        return to.apply(complex);
    }

    @Override
    public C fromPrimitive(B primitive, PersistentDataAdapterContext context) {
        return from.apply(primitive);
    }
}
