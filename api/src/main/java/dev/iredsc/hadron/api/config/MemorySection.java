package dev.iredsc.hadron.api.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A {@link ConfigurationSection} held in memory, in insertion order. */
public class MemorySection implements ConfigurationSection {
    private final Map<String, Object> map = new LinkedHashMap<String, Object>();
    private final MemorySection parent;
    private final String path;
    private final String name;

    /** A root. */
    protected MemorySection() {
        parent = null;
        path = "";
        name = "";
    }

    protected MemorySection(MemorySection parent, String name) {
        this.parent = parent;
        this.name = name;
        this.path = parent.path.isEmpty() ? name : parent.path + "." + name;
    }

    /** The defaults for this section's place in the tree, from the root's defaults; null for none. */
    protected ConfigurationSection defaults() {
        if (parent == null) return null;
        ConfigurationSection parentDefaults = parent.defaults();
        return parentDefaults == null ? null : parentDefaults.getConfigurationSection(name);
    }

    @Override
    public Set<String> getKeys(boolean deep) {
        Set<String> out = new LinkedHashSet<String>();
        ConfigurationSection defaults = defaults();
        if (defaults != null) out.addAll(defaults.getKeys(deep));
        collectKeys(this, "", deep, out);
        return out;
    }

    private static void collectKeys(MemorySection section, String prefix, boolean deep, Set<String> out) {
        for (Map.Entry<String, Object> e : section.map.entrySet()) {
            out.add(prefix + e.getKey());
            if (deep && e.getValue() instanceof MemorySection) {
                collectKeys((MemorySection) e.getValue(), prefix + e.getKey() + ".", true, out);
            }
        }
    }

    @Override
    public Map<String, Object> getValues(boolean deep) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (String key : getKeys(deep)) out.put(key, get(key));
        return out;
    }

    @Override
    public boolean contains(String path) {
        return get(path) != null;
    }

    @Override
    public boolean isSet(String path) {
        return getLocal(path) != null;
    }

    @Override
    public String getCurrentPath() {
        return path;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ConfigurationSection getParent() {
        return parent;
    }

    @Override
    public Object get(String path) {
        return get(path, null);
    }

    @Override
    public Object get(String path, Object def) {
        Object local = getLocal(path);
        if (local != null) return local;
        ConfigurationSection defaults = defaults();
        Object fallback = defaults == null ? null : defaults.get(path);
        return fallback != null ? fallback : def;
    }

    private Object getLocal(String path) {
        MemorySection section = this;
        String[] parts = path.split("\\.", -1);
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = section.map.get(parts[i]);
            if (!(next instanceof MemorySection)) return null;
            section = (MemorySection) next;
        }
        return section.map.get(parts[parts.length - 1]);
    }

    @Override
    public void set(String path, Object value) {
        MemorySection section = this;
        String[] parts = path.split("\\.", -1);
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = section.map.get(parts[i]);
            if (!(next instanceof MemorySection)) {
                if (value == null) return;
                next = new MemorySection(section, parts[i]);
                section.map.put(parts[i], next);
            }
            section = (MemorySection) next;
        }
        String key = parts[parts.length - 1];
        if (value == null) {
            section.map.remove(key);
        } else if (value instanceof Map) {
            MemorySection child = new MemorySection(section, key);
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) child.set(String.valueOf(e.getKey()), e.getValue());
            section.map.put(key, child);
        } else {
            section.map.put(key, value);
        }
    }

    @Override
    public ConfigurationSection createSection(String path) {
        set(path, Collections.emptyMap());
        return getConfigurationSection(path);
    }

    @Override
    public ConfigurationSection getConfigurationSection(String path) {
        Object local = getLocal(path);
        if (local instanceof ConfigurationSection) return (ConfigurationSection) local;
        if (local != null) return null;
        ConfigurationSection defaults = defaults();
        // A section only the defaults have still answers from them, through a section of our own.
        if (defaults != null && defaults.isConfigurationSection(path)) return createSection(path);
        return null;
    }

    @Override
    public boolean isConfigurationSection(String path) {
        return get(path) instanceof ConfigurationSection;
    }

    /** Whether a value counts for a typed getter. */
    private interface Kind {
        boolean fits(Object value);
    }

    private static final Kind TEXT = new Kind() {
        public boolean fits(Object v) {
            return !(v instanceof ConfigurationSection) && !(v instanceof List) && !(v instanceof java.util.Map);
        }
    };
    private static final Kind NUMBER = new Kind() {
        public boolean fits(Object v) {
            return v instanceof Number;
        }
    };
    private static final Kind BOOLEAN = new Kind() {
        public boolean fits(Object v) {
            return v instanceof Boolean;
        }
    };
    private static final Kind LIST = new Kind() {
        public boolean fits(Object v) {
            return v instanceof List;
        }
    };

    /** The local value if it fits, else the defaults' if that fits, else null: a wrong type counts as missing. */
    private Object typed(String path, Kind kind) {
        Object local = getLocal(path);
        if (local != null && kind.fits(local)) return local;
        ConfigurationSection defaults = defaults();
        Object fallback = defaults == null ? null : defaults.get(path);
        return fallback != null && kind.fits(fallback) ? fallback : null;
    }

    @Override
    public String getString(String path) {
        return getString(path, null);
    }

    @Override
    public String getString(String path, String def) {
        Object v = typed(path, TEXT);
        return v == null ? def : v.toString();
    }

    @Override
    public boolean isString(String path) {
        return get(path) instanceof String;
    }

    @Override
    public int getInt(String path) {
        return getInt(path, 0);
    }

    @Override
    public int getInt(String path, int def) {
        Object v = typed(path, NUMBER);
        return v != null ? ((Number) v).intValue() : def;
    }

    @Override
    public boolean isInt(String path) {
        return get(path) instanceof Integer;
    }

    @Override
    public long getLong(String path) {
        return getLong(path, 0L);
    }

    @Override
    public long getLong(String path, long def) {
        Object v = typed(path, NUMBER);
        return v != null ? ((Number) v).longValue() : def;
    }

    @Override
    public double getDouble(String path) {
        return getDouble(path, 0.0);
    }

    @Override
    public double getDouble(String path, double def) {
        Object v = typed(path, NUMBER);
        return v != null ? ((Number) v).doubleValue() : def;
    }

    @Override
    public boolean isDouble(String path) {
        return get(path) instanceof Number;
    }

    @Override
    public boolean getBoolean(String path) {
        return getBoolean(path, false);
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        Object v = typed(path, BOOLEAN);
        return v != null ? (Boolean) v : def;
    }

    @Override
    public boolean isBoolean(String path) {
        return get(path) instanceof Boolean;
    }

    @Override
    public List<?> getList(String path) {
        return getList(path, null);
    }

    @Override
    public List<?> getList(String path, List<?> def) {
        Object v = typed(path, LIST);
        return v != null ? (List<?>) v : def;
    }

    @Override
    public boolean isList(String path) {
        return get(path) instanceof List;
    }

    @Override
    public List<String> getStringList(String path) {
        List<String> out = new ArrayList<String>();
        List<?> list = getList(path);
        if (list != null) for (Object o : list) if (o != null && !(o instanceof Map)) out.add(o.toString());
        return out;
    }

    @Override
    public List<Integer> getIntegerList(String path) {
        List<Integer> out = new ArrayList<Integer>();
        List<?> list = getList(path);
        if (list != null) for (Object o : list) if (o instanceof Number) out.add(((Number) o).intValue());
        return out;
    }

    @Override
    public List<Double> getDoubleList(String path) {
        List<Double> out = new ArrayList<Double>();
        List<?> list = getList(path);
        if (list != null) for (Object o : list) if (o instanceof Number) out.add(((Number) o).doubleValue());
        return out;
    }

    @Override
    public List<Map<?, ?>> getMapList(String path) {
        List<Map<?, ?>> out = new ArrayList<Map<?, ?>>();
        List<?> list = getList(path);
        if (list != null) for (Object o : list) if (o instanceof Map) out.add((Map<?, ?>) o);
        return out;
    }

    @Override
    public void addDefault(String path, Object value) {
        MemorySection root = this;
        while (root.parent != null) root = root.parent;
        if (!(root instanceof YamlConfiguration)) throw new IllegalStateException("Only a loaded config has defaults");
        ((YamlConfiguration) root).defaultsRoot().set(this.path.isEmpty() ? path : this.path + "." + path, value);
    }

    /** Plain maps and lists, for writing out. */
    Map<String, Object> toPlain() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            out.put(e.getKey(), e.getValue() instanceof MemorySection ? ((MemorySection) e.getValue()).toPlain()
                    : e.getValue());
        }
        return out;
    }

    void clear() {
        map.clear();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[path='" + path + "']";
    }
}
