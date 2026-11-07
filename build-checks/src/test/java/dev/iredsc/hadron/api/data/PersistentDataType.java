package dev.iredsc.hadron.api.data;

import java.util.function.Function;

/**
 * A stand-in for Hadron's api type, compiled with the tests so the check sees it as a plugin does: from a class file,
 * not from source.
 */
public interface PersistentDataType<P, C> {
    PersistentDataType<String, String> STRING = new PersistentDataType<>() {};

    static <B, C> PersistentDataType<B, C> adapt(PersistentDataType<?, B> base, Class<C> type, Function<B, C> from,
            Function<C, B> to) {
        return new PersistentDataType<>() {};
    }
}
