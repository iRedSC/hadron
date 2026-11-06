package dev.iredsc.hadron.api.data;

import java.util.List;

/** A list of one element type, from {@link PersistentDataType#LIST}. Stored as a list, read back as a new list. */
public interface ListPersistentDataType<P, C> extends PersistentDataType<List<P>, List<C>> {
    PersistentDataType<P, C> elementType();
}
