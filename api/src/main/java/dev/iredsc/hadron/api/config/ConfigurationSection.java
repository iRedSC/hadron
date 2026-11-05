package dev.iredsc.hadron.api.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A map of values reached by dot-separated paths ({@code "shops.main.price"}), as in Bukkit. Getters fall back to
 * the defaults, then to the given default, then to null, zero or false. Getters never throw over a wrong type: a
 * value that isn't the type asked for counts as missing, so check with the {@code is*} methods when it matters.
 */
public interface ConfigurationSection {
    /** Keys directly in this section, or every path below it when {@code deep}. */
    Set<String> getKeys(boolean deep);

    /** Values by key, or by every path below this section when {@code deep}; sections appear as sections. */
    Map<String, Object> getValues(boolean deep);

    /** Set here or in the defaults. */
    boolean contains(String path);

    /** Set here, ignoring the defaults. */
    boolean isSet(String path);

    /** This section's full path from the root; empty for the root. */
    String getCurrentPath();

    /** The last part of {@link #getCurrentPath}. */
    String getName();

    ConfigurationSection getParent();

    Object get(String path);

    Object get(String path, Object def);

    /** Null removes the value. A map becomes a section. */
    void set(String path, Object value);

    ConfigurationSection createSection(String path);

    ConfigurationSection getConfigurationSection(String path);

    boolean isConfigurationSection(String path);

    String getString(String path);

    String getString(String path, String def);

    boolean isString(String path);

    int getInt(String path);

    int getInt(String path, int def);

    boolean isInt(String path);

    long getLong(String path);

    long getLong(String path, long def);

    double getDouble(String path);

    double getDouble(String path, double def);

    /** Whether the value is any number. */
    boolean isDouble(String path);

    boolean getBoolean(String path);

    boolean getBoolean(String path, boolean def);

    boolean isBoolean(String path);

    List<?> getList(String path);

    List<?> getList(String path, List<?> def);

    boolean isList(String path);

    /** The list's entries as text; empty when there is no list. */
    List<String> getStringList(String path);

    List<Integer> getIntegerList(String path);

    List<Double> getDoubleList(String path);

    List<Map<?, ?>> getMapList(String path);

    /** Values behind this one's, for anything it doesn't set. */
    void addDefault(String path, Object value);
}
