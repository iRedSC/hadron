package dev.iredsc.hadron.api.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Turns typed values into tags and back. Never calls a plugin's code to check whether a stored value fits a type. */
final class Values {
    static final PersistentDataAdapterContext CONTEXT = TagContainer::newDetached;

    private Values() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Tag encode(PersistentDataType type, Object value, Object where) {
        if (value == null) {
            throw new NullPointerException("Can't store null at " + where + "; use remove(key) to delete a value");
        }
        if (type instanceof Builtin builtin) return builtin.encode(value);
        if (type instanceof ListPersistentDataType list) {
            ListTag out = new ListTag();
            for (Object element : (List<?>) value) {
                Tag tag = encode(list.elementType(), element, where + " (a list element)");
                if (!out.addTag(out.size(), tag)) throw new IllegalStateException("mixed tag types in a list");
            }
            return out;
        }
        if (type instanceof AdaptedType adapted) {
            Object base = adapted.to().apply(value);
            if (base == null) {
                throw new NullPointerException("The adapt(...) 'to' function returned null for " + value + " at "
                        + where + "; it must return a value of " + describe(adapted.base()));
            }
            return encode(adapted.base(), base, where);
        }
        Builtin primitive = primitiveOf(type);
        return primitive.encode(type.toPrimitive(value, CONTEXT));
    }

    /** Whether the tag was stored as this type. */
    @SuppressWarnings("rawtypes")
    static boolean fits(PersistentDataType type, Tag tag) {
        if (type instanceof Builtin builtin) return builtin.fits(tag);
        if (type instanceof ListPersistentDataType list) {
            if (!(tag instanceof ListTag listTag)) return false;
            for (Tag element : listTag) {
                if (!fits(list.elementType(), element)) return false;
            }
            return true;
        }
        if (type instanceof AdaptedType adapted) return fits(adapted.base(), tag);
        return primitiveOf(type).fits(tag);
    }

    /** Decodes a tag that {@link #fits} the type. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object decode(PersistentDataType type, Tag tag) {
        if (type instanceof Builtin builtin) return builtin.decode(tag);
        if (type instanceof ListPersistentDataType list) {
            List<Object> out = new ArrayList<>();
            for (Tag element : (ListTag) tag) out.add(decode(list.elementType(), element));
            return out;
        }
        if (type instanceof AdaptedType adapted) {
            return Objects.requireNonNull(adapted.from().apply(decode(adapted.base(), tag)),
                    "The adapt(...) 'from' function returned null; return a value, or remove the key instead");
        }
        Builtin primitive = primitiveOf(type);
        return type.fromPrimitive(primitive.decode(tag), CONTEXT);
    }

    private static Builtin<?, ?> primitiveOf(PersistentDataType<?, ?> type) {
        Builtin<?, ?> builtin = Builtin.byPrimitive(type.getPrimitiveType());
        if (builtin == null) {
            throw new IllegalArgumentException(type.getClass().getName() + " stores " + type.getPrimitiveType()
                    .getSimpleName() + ", which Hadron can't store. Build it on a built-in type instead: "
                    + "PersistentDataType.adapt(PersistentDataType.STRING, " + type.getComplexType().getSimpleName()
                    + ".class, from, to), or LIST.listTypeFrom(...) for lists");
        }
        return builtin;
    }

    /** A type's name, as a plugin wrote it. */
    @SuppressWarnings("rawtypes")
    static String describe(PersistentDataType type) {
        if (type instanceof Builtin builtin) return builtin.constant;
        if (type instanceof ListPersistentDataType list) return "LIST of " + describe(list.elementType());
        if (type instanceof AdaptedType adapted) {
            return adapted.type().getSimpleName() + " (stored as " + describe(adapted.base()) + ")";
        }
        return type.getComplexType().getSimpleName() + " (stored as " + type.getPrimitiveType().getSimpleName() + ")";
    }

    /** What a stored tag can be read as. */
    static String describe(Tag tag) {
        return switch (tag.getId()) {
            case Tag.TAG_BYTE -> "BYTE or BOOLEAN";
            case Tag.TAG_SHORT -> "SHORT";
            case Tag.TAG_INT -> "INTEGER";
            case Tag.TAG_LONG -> "LONG";
            case Tag.TAG_FLOAT -> "FLOAT";
            case Tag.TAG_DOUBLE -> "DOUBLE";
            case Tag.TAG_BYTE_ARRAY -> "BYTE_ARRAY";
            case Tag.TAG_STRING -> "STRING";
            case Tag.TAG_LIST -> ((ListTag) tag).isEmpty() ? "an empty LIST" : "LIST of " + describe(((ListTag) tag).get(0));
            case Tag.TAG_COMPOUND -> "TAG_CONTAINER";
            case Tag.TAG_INT_ARRAY -> ((IntArrayTag) tag).size() == 4 ? "UUID or INTEGER_ARRAY" : "INTEGER_ARRAY";
            case Tag.TAG_LONG_ARRAY -> "LONG_ARRAY";
            default -> "an unknown tag type " + tag.getId();
        };
    }
}
