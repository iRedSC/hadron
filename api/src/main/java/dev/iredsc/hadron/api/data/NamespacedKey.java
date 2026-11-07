package dev.iredsc.hadron.api.data;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A key for {@link PersistentDataContainer}: the plugin's name as the namespace, and a name the plugin picks, as in
 * Bukkit. Two plugins never collide, and a plugin finds its own data again after a reload or restart, since the key
 * holds only the two strings.
 *
 * <pre>{@code
 * static NamespacedKey OWNER;                        // in onEnable: OWNER = new NamespacedKey(this, "owner");
 * PersistentData.of(chest).set(OWNER, PersistentDataType.UUID, player.getUUID());
 * }</pre>
 */
public final class NamespacedKey {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9._-]+");
    private static final Pattern KEY = Pattern.compile("[a-z0-9/._-]+");

    private final String namespace;
    private final String key;

    /** A key in this plugin's namespace (its name, lowercased). The key may use a-z, 0-9 and {@code / . _ -}. */
    public NamespacedKey(JavaPlugin plugin, String key) {
        this(Objects.requireNonNull(plugin, "plugin").getName().toLowerCase(Locale.ROOT), key);
    }

    /**
     * A key in any namespace; plugins use {@link #NamespacedKey(JavaPlugin, String)}. This one is for reading another
     * plugin's data on purpose, or for bridges.
     */
    public NamespacedKey(String namespace, String key) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(key, "key");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("\"" + namespace + "\" is not a valid NamespacedKey namespace: use only "
                    + "lowercase letters, digits and . _ -, for example \"" + suggest(namespace, false) + "\"");
        }
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("\"" + key + "\" is not a valid NamespacedKey name: use only lowercase "
                    + "letters, digits and / . _ -, for example new NamespacedKey(plugin, \"" + suggest(key, true)
                    + "\")");
        }
        this.namespace = namespace;
        this.key = key;
    }

    /** Parses {@code "namespace:key"}. Returns null when it isn't one, as Bukkit does. */
    public static NamespacedKey fromString(String string) {
        int colon = Objects.requireNonNull(string, "string").indexOf(':');
        if (colon <= 0 || colon == string.length() - 1) return null;
        String namespace = string.substring(0, colon);
        String key = string.substring(colon + 1);
        if (!NAMESPACE.matcher(namespace).matches() || !KEY.matcher(key).matches()) return null;
        return new NamespacedKey(namespace, key);
    }

    /** Null when either part isn't valid; for reading keys back from storage. */
    static NamespacedKey parse(String namespace, String key) {
        return NAMESPACE.matcher(namespace).matches() && KEY.matcher(key).matches()
                ? new NamespacedKey(namespace, key) : null;
    }

    private static String suggest(String raw, boolean allowSlash) {
        StringBuilder out = new StringBuilder();
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-'
                    || (allowSlash && c == '/');
            out.append(ok ? c : '_');
        }
        return out.isEmpty() ? "my_key" : out.toString();
    }

    public String getNamespace() {
        return namespace;
    }

    public String getKey() {
        return key;
    }

    /** {@code "namespace:key"}. */
    public String asString() {
        return namespace + ':' + key;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NamespacedKey k && k.namespace.equals(namespace) && k.key.equals(key);
    }

    @Override
    public int hashCode() {
        return namespace.hashCode() * 31 + key.hashCode();
    }

    @Override
    public String toString() {
        return asString();
    }
}
