package dev.iredsc.hadron.api.config;

import org.jspecify.annotations.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * A YAML file as a {@link ConfigurationSection}, as in Bukkit. Comments aren't kept when saving, so keep
 * hand-written comments in the jar's default file and save only what players change.
 */
public class YamlConfiguration extends MemorySection {
    private @Nullable MemorySection defaults;
    private boolean copyDefaults;

    public YamlConfiguration() {
        super();
    }

    /**
     * Reads the file; a missing file gives an empty config. A malformed one logs the line it broke on and gives an
     * empty config too, so the plugin starts with its defaults instead of failing to enable.
     */
    public static YamlConfiguration loadConfiguration(File file) {
        YamlConfiguration config = new YamlConfiguration();
        if (!file.exists()) return config;
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            LoggerFactory.getLogger("Hadron").error("Couldn't read {}: {}", file, e.getMessage());
        }
        return config;
    }

    public static YamlConfiguration loadConfiguration(Reader reader) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(reader);
        } catch (IOException | InvalidConfigurationException e) {
            LoggerFactory.getLogger("Hadron").error("Couldn't read a YAML config: " + e.getMessage());
        }
        return config;
    }

    public void load(File file) throws IOException, InvalidConfigurationException {
        Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
        try {
            load(reader);
        } finally {
            reader.close();
        }
    }

    public void load(Reader reader) throws IOException, InvalidConfigurationException {
        StringBuilder text = new StringBuilder();
        char[] buf = new char[4096];
        for (int n; (n = reader.read(buf)) > 0; ) text.append(buf, 0, n);
        loadFromString(text.toString());
    }

    public void loadFromString(String text) throws InvalidConfigurationException {
        Object parsed;
        try {
            parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (RuntimeException e) {
            throw new InvalidConfigurationException(e.getMessage(), e);
        }
        clear();
        if (parsed == null) return;
        if (!(parsed instanceof Map)) throw new InvalidConfigurationException("The top level must be keys and values");
        for (Map.Entry<?, ?> e : ((Map<?, ?>) parsed).entrySet()) set(String.valueOf(e.getKey()), e.getValue());
    }

    public String saveToString() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setAllowUnicode(true);
        Map<String, Object> plain = toPlain();
        if (copyDefaults && defaults != null) plain = merged(defaults.toPlain(), plain);
        String out = new Yaml(options).dump(plain);
        return out.equals("{}\n") ? "" : out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merged(Map<String, Object> under, Map<String, Object> over) {
        for (Map.Entry<String, Object> e : over.entrySet()) {
            Object below = under.get(e.getKey());
            if (below instanceof Map && e.getValue() instanceof Map) {
                under.put(e.getKey(), merged((Map<String, Object>) below, (Map<String, Object>) e.getValue()));
            } else {
                under.put(e.getKey(), e.getValue());
            }
        }
        return under;
    }

    /** Writes through a temporary file, so a crash mid-save never leaves half a config. */
    public void save(File file) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        Files.createDirectories(dir.toPath());
        File tmp = new File(dir, file.getName() + ".tmp");
        Files.write(tmp.toPath(), saveToString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Values to answer with for anything this config doesn't set. */
    public void setDefaults(YamlConfiguration defaults) {
        this.defaults = defaults;
    }

    public @Nullable YamlConfiguration getDefaults() {
        return defaults instanceof YamlConfiguration ? (YamlConfiguration) defaults : null;
    }

    MemorySection defaultsRoot() {
        if (defaults == null) defaults = new YamlConfiguration();
        return defaults;
    }

    @Override
    protected @Nullable ConfigurationSection defaults() {
        return defaults;
    }

    /** Bukkit's {@code options().copyDefaults(true)}: saving writes the defaults too, so players see every key. */
    public Options options() {
        return new Options();
    }

    public final class Options {
        public Options copyDefaults(boolean value) {
            copyDefaults = value;
            return this;
        }

        public boolean copyDefaults() {
            return copyDefaults;
        }
    }
}
