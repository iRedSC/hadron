package dev.iredsc.hadron.api.data;

import org.jspecify.annotations.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Every container: a root (where the data lives) and a path of keys to a nested container under it. Its compound
 * holds one compound per namespace, holding that namespace's values by key.
 */
final class TagContainer implements PersistentDataContainer {
    private static final NamespacedKey[] NO_PATH = {};

    private final PersistentData.Port.Root root;
    private final NamespacedKey[] path;

    TagContainer(PersistentData.Port.Root root) {
        this(root, NO_PATH);
    }

    private TagContainer(PersistentData.Port.Root root, NamespacedKey[] path) {
        this.root = root;
        this.path = path;
    }

    static PersistentDataContainer newDetached() {
        return detached(new CompoundTag());
    }

    static TagContainer detached(CompoundTag tag) {
        return new TagContainer(new Detached(tag));
    }

    /** A copy of a container's data, to store it somewhere. */
    static CompoundTag snapshot(PersistentDataContainer container) {
        if (!(container instanceof TagContainer tags)) {
            throw new IllegalArgumentException("Only containers from Hadron can be stored; make one with "
                    + "context.newPersistentDataContainer() or PersistentData.newContainer(), not your own class");
        }
        CompoundTag view = tags.view();
        return view == null ? new CompoundTag() : view.copy();
    }

    /** This container's compound, or null when nothing is stored. Never modified. */
    private @Nullable CompoundTag view() {
        CompoundTag tag = root.read();
        for (NamespacedKey key : path) {
            if (tag == null) return null;
            tag = value(tag, key) instanceof CompoundTag nested ? nested : null;
        }
        return tag;
    }

    private void edit(Consumer<CompoundTag> edit) {
        root.write(top -> {
            CompoundTag tag = top;
            for (NamespacedKey key : path) {
                Tag nested = value(tag, key);
                if (nested == null) {
                    nested = new CompoundTag();
                    put(tag, key, nested);
                } else if (!(nested instanceof CompoundTag)) {
                    throw new IllegalStateException("This container was stored at " + key + ", which now holds "
                            + Values.describe(nested) + " instead; get the container again");
                }
                tag = (CompoundTag) nested;
            }
            edit.accept(tag);
        });
    }

    private static @Nullable Tag value(CompoundTag tag, NamespacedKey key) {
        return tag.get(key.getNamespace()) instanceof CompoundTag namespace ? namespace.get(key.getKey()) : null;
    }

    private static void put(CompoundTag tag, NamespacedKey key, Tag value) {
        CompoundTag namespace = tag.get(key.getNamespace()) instanceof CompoundTag existing ? existing : null;
        if (namespace == null) {
            namespace = new CompoundTag();
            tag.put(key.getNamespace(), namespace);
        }
        namespace.put(key.getKey(), value);
    }

    private static void remove(CompoundTag tag, NamespacedKey key) {
        if (!(tag.get(key.getNamespace()) instanceof CompoundTag namespace)) return;
        namespace.remove(key.getKey());
        if (namespace.isEmpty()) tag.remove(key.getNamespace());
    }

    private @Nullable Tag stored(NamespacedKey key) {
        CompoundTag view = view();
        return view == null ? null : value(view, Objects.requireNonNull(key, "key"));
    }

    @Override
    public <P, C> void set(NamespacedKey key, PersistentDataType<P, C> type, C value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Tag tag = Values.encode(type, value, key);
        edit(c -> put(c, key, tag));
    }

    @Override
    public <P, C> boolean has(NamespacedKey key, PersistentDataType<P, C> type) {
        Tag tag = stored(key);
        return tag != null && Values.fits(Objects.requireNonNull(type, "type"), tag);
    }

    @Override
    public boolean has(NamespacedKey key) {
        return stored(key) != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <P, C> @Nullable C get(NamespacedKey key, PersistentDataType<P, C> type) {
        Objects.requireNonNull(type, "type");
        Tag tag = stored(key);
        if (tag == null) return null;
        if (!Values.fits(type, tag)) {
            throw new IllegalArgumentException(key + " holds a value stored as " + Values.describe(tag)
                    + ", but was read as " + Values.describe(type) + ". Read it with the type it was set with, or "
                    + "store the " + Values.describe(type) + " under a different key.");
        }
        if (type == Builtin.TAG_CONTAINER) {
            NamespacedKey[] nested = Arrays.copyOf(path, path.length + 1);
            nested[path.length] = key;
            return (C) new TagContainer(root, nested);
        }
        return (C) Values.decode(type, tag);
    }

    @Override
    public <P, C> C getOrDefault(NamespacedKey key, PersistentDataType<P, C> type, C defaultValue) {
        C value = get(key, type);
        return value != null ? value : defaultValue;
    }

    @Override
    public Set<NamespacedKey> getKeys() {
        CompoundTag view = view();
        if (view == null) return Set.of();
        Set<NamespacedKey> keys = new LinkedHashSet<>();
        for (String namespace : view.getAllKeys()) {
            if (!(view.get(namespace) instanceof CompoundTag values)) continue;
            for (String name : values.getAllKeys()) {
                NamespacedKey key = NamespacedKey.parse(namespace, name);
                if (key != null) keys.add(key);
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    @Override
    public void remove(NamespacedKey key) {
        if (stored(key) != null) edit(c -> remove(c, key));
    }

    @Override
    public boolean isEmpty() {
        CompoundTag view = view();
        return view == null || view.isEmpty();
    }

    @Override
    public void copyTo(PersistentDataContainer other, boolean replace) {
        Objects.requireNonNull(other, "other");
        if (!(other instanceof TagContainer target)) {
            throw new IllegalArgumentException("copyTo needs a container from Hadron, not your own class");
        }
        CompoundTag view = view();
        if (view == null || view.isEmpty()) return;
        CompoundTag source = view.copy();
        target.edit(c -> {
            for (String namespace : source.getAllKeys()) {
                if (!(source.get(namespace) instanceof CompoundTag values)) continue;
                for (String name : values.getAllKeys()) {
                    NamespacedKey key = NamespacedKey.parse(namespace, name);
                    if (key != null && (replace || value(c, key) == null)) put(c, key, values.get(name));
                }
            }
        });
    }

    @Override
    public PersistentDataAdapterContext getAdapterContext() {
        return Values.CONTEXT;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof TagContainer that)) return false;
        CompoundTag a = view();
        CompoundTag b = that.view();
        return (a == null || a.isEmpty()) ? (b == null || b.isEmpty()) : a.equals(b);
    }

    @Override
    public int hashCode() {
        CompoundTag view = view();
        return view == null || view.isEmpty() ? 0 : view.hashCode();
    }

    @Override
    public String toString() {
        CompoundTag view = view();
        return "PersistentDataContainer" + (view == null ? "{}" : view.toString());
    }

    /** Data that belongs to nothing yet. */
    private record Detached(CompoundTag tag) implements PersistentData.Port.Root {
        @Override
        public CompoundTag read() {
            return tag;
        }

        @Override
        public void write(Consumer<CompoundTag> edit) {
            edit.accept(tag);
        }
    }
}
