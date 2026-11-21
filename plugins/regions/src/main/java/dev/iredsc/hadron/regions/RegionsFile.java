package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.config.ConfigurationSection;
import dev.iredsc.hadron.api.config.InvalidConfigurationException;
import dev.iredsc.hadron.api.config.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * {@code regions.yml}: every region, as plain YAML an owner can read and fix by hand.
 *
 * <pre>
 * format: 1
 * regions:
 *   spawn:
 *     world: minecraft:overworld
 *     min: [-50, -64, -50]
 *     max: [50, 320, 50]
 *     priority: 0
 *     owners: []
 *     members: [5f2b...]
 *     flags: {use: allow, pvp: deny}
 * </pre>
 *
 * <p>Reading refuses the whole file on any mistake, naming where it is, so a typo can never quietly drop a region
 * and the next save can never write the file without it.
 */
final class RegionsFile {
    static final int FORMAT = 1;

    private RegionsFile() {}

    /** A mistake in regions.yml, said so an owner can fix it by hand. */
    static final class Problem extends Exception {
        private static final long serialVersionUID = 1L;

        Problem(String message) {
            super(message);
        }
    }

    /**
     * Reads one of the plugin's data files (regions.yml, bypass.yml), refusing what {@link YamlConfiguration} would
     * take and quietly fold into one key: a key written twice in the same map, and a key with a dot in it, which it
     * reads as a key inside a key. Either would drop what the owner wrote, and the next save would write the file
     * without it.
     */
    static YamlConfiguration open(File file) throws Problem {
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new Problem("Couldn't read " + file + ": " + e.getMessage());
        }
        return parse(file.getName(), text);
    }

    static YamlConfiguration parse(String name, String text) throws Problem {
        LoaderOptions strict = new LoaderOptions();
        strict.setAllowDuplicateKeys(false);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            foldedKeys(name, "", new Yaml(new SafeConstructor(strict)).load(text));
            yaml.loadFromString(text);
        } catch (DuplicateKeyException e) {
            String key = e.getProblem().replaceFirst("^found duplicate key ", "");
            throw new Problem(name + ", line " + (e.getProblemMark().getLine() + 1) + ": \"" + key + "\" is written "
                    + "twice in the same map, so one of the two would be lost. Rename or remove one of them");
        } catch (InvalidConfigurationException | RuntimeException e) {
            throw new Problem(name + " isn't valid YAML: " + e.getMessage());
        }
        return yaml;
    }

    /** Refuses the keys YamlConfiguration folds together though YAML holds them apart, in maps at any depth. */
    private static void foldedKeys(String name, String under, Object node) throws Problem {
        if (!(node instanceof Map<?, ?> map)) return;
        String where = name + (under.isEmpty() ? "" : ", under " + under) + ": \"";
        Set<String> seen = new HashSet<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (key.indexOf('.') >= 0) {
                throw new Problem(where + key + "\" has a dot in it, which would be read as a key inside a key. "
                        + "Remove the dot, or write the part after it as a key on its own line beneath");
            }
            if (!seen.add(key)) {
                throw new Problem(where + key + "\" is written twice in the same map, once as text and once as a "
                        + "number or other value, so one of the two would be lost. Rename or remove one of them");
            }
            foldedKeys(name, under.isEmpty() ? key : under + " > " + key, e.getValue());
        }
    }

    static Map<String, Region> read(YamlConfiguration yaml) throws Problem {
        int format = yaml.getInt("format", FORMAT);
        if (format != FORMAT) {
            throw new Problem("regions.yml says format " + format + ", but this Regions reads format " + FORMAT
                    + ". Update the Regions plugin, or restore the file from a backup made for this version");
        }
        for (String key : yaml.getKeys(false)) {
            if (!key.equals("format") && !key.equals("regions")) {
                throw new Problem("regions.yml: \"" + key + "\" isn't a setting; the file holds only format and "
                        + "regions");
            }
        }
        if (yaml.contains("format") && !(yaml.get("format") instanceof Integer)) {
            throw new Problem("regions.yml: format must be a whole number, but was " + yaml.get("format"));
        }
        Map<String, Region> regions = new LinkedHashMap<>();
        ConfigurationSection all = yaml.getConfigurationSection("regions");
        if (all == null) {
            if (yaml.contains("regions")) throw new Problem("regions.yml: \"regions\" must be a map of region ids");
            return regions;
        }
        for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            if (s == null) throw new Problem(at(id, "") + "must be a map with world, min and max");
            if (!Region.validId(id)) throw new Problem("regions.yml: " + Region.idProblem(id) + "; rename it");
            regions.put(id, region(id, s));
        }
        return regions;
    }

    /** The keys a region may have; anything else is a typo that would otherwise drop what it meant to set. */
    private static boolean known(String key) {
        return switch (key) {
            case "world", "min", "max", "priority", "owners", "members", "flags" -> true;
            default -> false;
        };
    }

    private static Region region(String id, ConfigurationSection s) throws Problem {
        for (String key : s.getKeys(false)) {
            if (!known(key)) {
                throw new Problem(at(id, key) + "isn't a region setting; a region has world, min, max, priority, "
                        + "owners, members and flags");
            }
        }
        String worldText = s.get("world") instanceof String text ? text : null;
        ResourceLocation world = worldText == null ? null : ResourceLocation.tryParse(worldText);
        if (world == null) {
            throw new Problem(at(id, "world") + "must be a world id such as minecraft:overworld, but was " + worldText);
        }
        BlockPos min = pos(id, s, "min");
        BlockPos max = pos(id, s, "max");
        Map<Flag, Boolean> flags = new EnumMap<>(Flag.class);
        ConfigurationSection f = s.getConfigurationSection("flags");
        if (f == null && s.contains("flags")) {
            throw new Problem(at(id, "flags") + "must be a map such as {pvp: deny}, but was " + s.get("flags"));
        }
        if (s.contains("priority") && !(s.get("priority") instanceof Integer)) {
            throw new Problem(at(id, "priority") + "must be a whole number from " + Integer.MIN_VALUE + " to "
                    + Integer.MAX_VALUE + ", but was " + s.get("priority"));
        }
        if (f != null) {
            for (String key : f.getKeys(false)) {
                Flag flag = Flag.parse(key).orElseThrow(() -> new Problem(at(id, "flags." + key)
                        + "isn't a flag; the flags are " + flagList()));
                String value = String.valueOf(f.get(key));
                switch (value) {
                    case "allow" -> flags.put(flag, true);
                    case "deny" -> flags.put(flag, false);
                    default -> throw new Problem(at(id, "flags." + key) + "must be allow or deny, but was " + value);
                }
            }
        }
        return new Region(id, world, BoundingBox.fromCorners(min, max), s.getInt("priority", 0),
                people(id, s, "owners"), people(id, s, "members"), flags, Rules.fresh());
    }

    private static BlockPos pos(String id, ConfigurationSection s, String key) throws Problem {
        List<?> raw = s.isList(key) ? s.getList(key) : null;
        if (raw == null || raw.size() != 3 || !raw.stream().allMatch(n -> n instanceof Integer)) {
            throw new Problem(at(id, key) + "must be three whole numbers [x, y, z], but was " + s.get(key));
        }
        return new BlockPos((Integer) raw.get(0), (Integer) raw.get(1), (Integer) raw.get(2));
    }

    private static Set<UUID> people(String id, ConfigurationSection s, String key) throws Problem {
        Set<UUID> out = new LinkedHashSet<>();
        if (!s.contains(key)) return out;
        List<?> raw = s.isList(key) ? s.getList(key) : null;
        if (raw == null) throw new Problem(at(id, key) + "must be a list of player UUIDs, but was " + s.get(key));
        for (Object item : raw) {
            String text = String.valueOf(item);
            try {
                if (!(item instanceof String)) throw new IllegalArgumentException();
                out.add(UUID.fromString(text));
            } catch (IllegalArgumentException e) {
                throw new Problem(at(id, key) + "holds \"" + text + "\", which isn't a player UUID. Add players "
                        + "with /region addmember or addowner, which look the UUID up");
            }
        }
        return out;
    }

    static YamlConfiguration write(Collection<Region> regions) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT);
        ConfigurationSection all = yaml.createSection("regions");
        List<Region> sorted = new ArrayList<>(regions);
        sorted.sort(Comparator.comparing(Region::id));
        for (Region r : sorted) {
            ConfigurationSection s = all.createSection(r.id());
            s.set("world", r.world().toString());
            BoundingBox b = r.box();
            s.set("min", List.of(b.minX(), b.minY(), b.minZ()));
            s.set("max", List.of(b.maxX(), b.maxY(), b.maxZ()));
            s.set("priority", r.priority());
            s.set("owners", r.owners().stream().map(UUID::toString).sorted().toList());
            s.set("members", r.members().stream().map(UUID::toString).sorted().toList());
            Map<String, Object> flags = new LinkedHashMap<>();
            for (Flag flag : Flag.values()) {
                r.flag(flag).ifPresent(v -> flags.put(flag.key(), v ? "allow" : "deny"));
            }
            s.set("flags", flags);
        }
        return yaml;
    }

    static String flagList() {
        List<String> keys = new ArrayList<>();
        for (Flag f : Flag.values()) keys.add(f.key());
        return String.join(", ", keys);
    }

    private static String at(String id, String key) {
        return "regions.yml, region " + id + (key.isEmpty() ? ": " : ", " + key + ": ");
    }
}
