package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.config.ConfigurationSection;
import dev.iredsc.hadron.api.config.YamlConfiguration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

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

    static Map<String, Region> read(YamlConfiguration yaml) throws Problem {
        int format = yaml.getInt("format", FORMAT);
        if (format != FORMAT) {
            throw new Problem("regions.yml says format " + format + ", but this Regions reads format " + FORMAT
                    + ". Update the Regions plugin, or restore the file from a backup made for this version");
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

    private static Region region(String id, ConfigurationSection s) throws Problem {
        String worldText = s.getString("world");
        ResourceLocation world = worldText == null ? null : ResourceLocation.tryParse(worldText);
        if (world == null) {
            throw new Problem(at(id, "world") + "must be a world id such as minecraft:overworld, but was " + worldText);
        }
        BlockPos min = pos(id, s, "min");
        BlockPos max = pos(id, s, "max");
        Map<Flag, Boolean> flags = new EnumMap<>(Flag.class);
        ConfigurationSection f = s.getConfigurationSection("flags");
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
                people(id, s, "owners"), people(id, s, "members"), flags);
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
        for (String text : s.getStringList(key)) {
            try {
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
