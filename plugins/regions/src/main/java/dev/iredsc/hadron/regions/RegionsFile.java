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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *     rules:
 *       everyone: {deny: [build, use], allow: [block.use]}
 *       world: {allow: [entity.*.player]}
 * </pre>
 *
 * <p>Rules are lists of nodes and flags per subject, read through {@link Pattern#parse}, the only way text becomes a
 * rule; nodes are list items, never keys.
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
     * take and quietly fold into one key or drop: a key written twice in the same map, a key with a dot in it, which it
     * reads as a key inside a key, and a key with nothing after it. Each would drop what the owner wrote, and the next
     * save would write the file without it.
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

    /**
     * Refuses the keys YamlConfiguration folds together though YAML holds them apart, or drops for holding nothing, in
     * maps at any depth.
     */
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
            if (e.getValue() == null) {
                throw new Problem(where + key + "\" has nothing after it, so it would be dropped. Write [] for an "
                        + "empty list or {} for an empty map, or remove the key");
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
            case "world", "min", "max", "priority", "owners", "members", "rules" -> true;
            default -> false;
        };
    }

    private static Region region(String id, ConfigurationSection s) throws Problem {
        if (s.contains("flags")) {
            throw new Problem(at(id, "flags") + "flags became rules: write rules: {everyone: {deny: [build]}} for "
                    + "flags: {build: deny}, and one list entry for each other flag");
        }
        for (String key : s.getKeys(false)) {
            if (!known(key)) {
                throw new Problem(at(id, key) + "isn't a region setting; a region has world, min, max, priority, "
                        + "owners, members and rules");
            }
        }
        String worldText = s.get("world") instanceof String text ? text : null;
        ResourceLocation world = worldText == null ? null : ResourceLocation.tryParse(worldText);
        if (world == null) {
            throw new Problem(at(id, "world") + "must be a world id such as minecraft:overworld, but was " + worldText);
        }
        BlockPos min = pos(id, s, "min");
        BlockPos max = pos(id, s, "max");
        if (s.contains("priority") && !(s.get("priority") instanceof Integer)) {
            throw new Problem(at(id, "priority") + "must be a whole number from " + Integer.MIN_VALUE + " to "
                    + Integer.MAX_VALUE + ", but was " + s.get("priority"));
        }
        Set<UUID> owners = people(id, s, "owners");
        Set<UUID> members = people(id, s, "members");
        return new Region(id, world, BoundingBox.fromCorners(min, max), s.getInt("priority", 0), owners, members,
                rules(id, s));
    }

    /** A region's rules: a map of subjects, each holding allow and deny lists of nodes and flags. */
    private static Rules rules(String id, ConfigurationSection s) throws Problem {
        if (!s.contains("rules")) {
            throw new Problem(at(id, "") + "a region needs rules; write rules: {} for a region that only marks an "
                    + "area, or rules: {everyone: {deny: [build, use]}} to protect it");
        }
        ConfigurationSection all = s.getConfigurationSection("rules");
        if (all == null) {
            throw new Problem(at(id, "rules") + "must be a map of subjects such as {everyone: {deny: [build]}}, but "
                    + "was " + s.get("rules"));
        }
        Rules rules = Rules.none();
        for (String key : all.getKeys(false)) {
            String where = at(id, "rules") + key + ": ";
            if (key.equals("players")) {
                throw new Problem(where + "per-player rules aren't in this version; remove players");
            }
            if (key.equals("owners")) {
                throw new Problem(where + "owners are judged as members; write members");
            }
            Subject subject = Subject.parse(key).orElseThrow(() -> new Problem(at(id, "rules") + "`" + key
                    + "` isn't a subject; the subjects are " + Subject.list()));
            ConfigurationSection lists = all.getConfigurationSection(key);
            if (lists == null) {
                throw new Problem(where + "must be a map such as {deny: [build, use]}, but was " + all.get(key));
            }
            for (String verdict : lists.getKeys(false)) {
                if (!verdict.equals("allow") && !verdict.equals("deny")) {
                    throw new Problem(where + "`" + verdict + "` isn't allow or deny, the only lists a subject has");
                }
            }
            for (Rules.Verdict v : Rules.Verdict.values()) {
                for (Pattern p : patterns(where + v.key() + ": ", lists, v.key())) {
                    if (rules.of(subject).allow().contains(p) || rules.of(subject).deny().contains(p)) {
                        throw new Problem(where + "`" + p + "` is both allowed and denied for " + subject.key()
                                + "; remove one");
                    }
                    rules = rules.with(subject, p, Optional.of(v));
                }
            }
        }
        return rules;
    }

    /** One allow or deny list, each entry parsed; an entry written twice is refused, so a save keeps the file as is. */
    private static List<Pattern> patterns(String where, ConfigurationSection lists, String key) throws Problem {
        if (!lists.contains(key)) return List.of();
        List<?> raw = lists.isList(key) ? lists.getList(key) : null;
        if (raw == null) {
            throw new Problem(where + "must be a list of nodes and flags such as [build, use], but was "
                    + lists.get(key));
        }
        List<Pattern> out = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof String text)) {
                throw new Problem(where + "holds " + item + ", which isn't text; write a node such as block.break, "
                        + "or a flag: " + Bundle.list());
            }
            Pattern p;
            try {
                p = Pattern.parse(text);
            } catch (Pattern.Invalid e) {
                throw new Problem(where + e.getMessage());
            }
            if (out.contains(p)) throw new Problem(where + "`" + p + "` is written twice; remove one");
            out.add(p);
        }
        return out;
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
            // Subjects in a fixed order, deny before allow, each list flags first: a hand edit round-trips.
            ConfigurationSection rules = s.createSection("rules");
            for (Subject subject : Subject.values()) {
                Rules.Lists lists = r.rules().of(subject);
                if (lists.isEmpty()) continue;
                Map<String, Object> out = new LinkedHashMap<>();
                if (!lists.deny().isEmpty()) out.put("deny", lists.deny().stream().map(Pattern::toString).toList());
                if (!lists.allow().isEmpty()) out.put("allow", lists.allow().stream().map(Pattern::toString).toList());
                rules.set(subject.key(), out);
            }
        }
        return yaml;
    }

    private static String at(String id, String key) {
        return "regions.yml, region " + id + (key.isEmpty() ? ": " : ", " + key + ": ");
    }
}
