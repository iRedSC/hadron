package dev.iredsc.hadron.api.plugin;

import dev.iredsc.hadron.api.Default;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A plugin's {@code plugin.yml}, as in Bukkit: {@code name}, {@code main} and {@code version} are required;
 * {@code description}, {@code author}/{@code authors}, {@code website}, {@code depend}, {@code softdepend},
 * {@code commands} and {@code permissions} are optional, as is Hadron's own {@code mod-depend} (NeoForge mods by
 * mod id that the plugin needs installed). Hadron also requires {@code minecraft}, the Minecraft version the plugin was
 * built for, which the build fills in. Anything malformed fails the load with a message naming the key, so a typo
 * can't quietly drop a command or a dependency.
 */
public final class PluginDescription {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    /** A command under {@code commands:}. */
    public static final class CommandSpec {
        public final String name;
        public final String description;
        /** With {@code <command>} replaced by the name. */
        public final String usage;
        public final List<String> aliases;
        /** Null when anyone may run it. */
        public final String permission;

        CommandSpec(String name, String description, String usage, List<String> aliases, String permission) {
            this.name = name;
            this.description = description;
            this.usage = usage;
            this.aliases = Collections.unmodifiableList(aliases);
            this.permission = permission;
        }
    }

    /** A node under {@code permissions:}, declared when the plugin enables. */
    public static final class PermissionSpec {
        public final String node;
        public final String description;
        public final Default fallback;

        PermissionSpec(String node, String description, Default fallback) {
            this.node = node;
            this.description = description;
            this.fallback = fallback;
        }
    }

    private final String name;
    private final String version;
    private final String main;
    private final String description;
    private final List<String> authors;
    private final String website;
    private final List<String> depend;
    private final List<String> softDepend;
    private final List<String> modDepend;
    private final String minecraft;
    private final Map<String, CommandSpec> commands;
    private final List<PermissionSpec> permissions;

    private PluginDescription(Map<?, ?> yaml) {
        name = required(yaml, "name");
        if (!NAME.matcher(name).matches()) {
            throw new PluginException("plugin.yml name '" + name + "' may only use letters, digits, _ . and -");
        }
        version = required(yaml, "version");
        main = required(yaml, "main");
        description = optional(yaml, "description", "");
        List<String> authorList = new ArrayList<String>(strings(yaml, "authors"));
        String author = optional(yaml, "author", null);
        if (author != null) authorList.add(0, author);
        authors = Collections.unmodifiableList(authorList);
        website = optional(yaml, "website", "");
        depend = Collections.unmodifiableList(strings(yaml, "depend"));
        softDepend = Collections.unmodifiableList(strings(yaml, "softdepend"));
        modDepend = Collections.unmodifiableList(strings(yaml, "mod-depend"));
        minecraft = optional(yaml, "minecraft", null);
        if (minecraft == null || minecraft.isEmpty()) {
            throw new PluginException("plugin.yml needs 'minecraft', the Minecraft version the plugin was built for, "
                    + "such as 1.21.1. Hadron's Gradle plugin fills it in; by hand, add minecraft: ${minecraft_version} "
                    + "and expand it in processResources");
        }
        commands = Collections.unmodifiableMap(commands(yaml.get("commands")));
        permissions = Collections.unmodifiableList(permissions(yaml.get("permissions")));
    }

    /** @throws PluginException naming what's wrong. */
    public static PluginDescription parse(Map<?, ?> yaml) {
        return new PluginDescription(yaml);
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getMain() {
        return main;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getAuthors() {
        return authors;
    }

    public String getWebsite() {
        return website;
    }

    /** Plugins that must be enabled first. This plugin may use their classes, and reloads whenever they do. */
    public List<String> getDepend() {
        return depend;
    }

    /** Like {@link #getDepend}, but this plugin also runs without them; check before touching their classes. */
    public List<String> getSoftDepend() {
        return softDepend;
    }

    /** NeoForge mod ids that must be installed. This plugin may use their classes. */
    public List<String> getModDepend() {
        return modDepend;
    }

    /** The Minecraft version this plugin was built for. Hadron refuses to load it on any other. */
    public String getMinecraft() {
        return minecraft;
    }

    public Map<String, CommandSpec> getCommands() {
        return commands;
    }

    public List<PermissionSpec> getPermissions() {
        return permissions;
    }

    /** Name and version, such as {@code Tags v0.1.0}. */
    public String getFullName() {
        return name + " v" + version;
    }

    private static String required(Map<?, ?> yaml, String key) {
        String value = optional(yaml, key, null);
        if (value == null || value.isEmpty()) throw new PluginException("plugin.yml needs '" + key + "'");
        return value;
    }

    private static String optional(Map<?, ?> yaml, String key, String fallback) {
        Object value = yaml.get(key);
        if (value == null) return fallback;
        if (value instanceof Map || value instanceof List) {
            throw new PluginException("plugin.yml '" + key + "' must be a single value");
        }
        return value.toString();
    }

    private static List<String> strings(Map<?, ?> yaml, String key) {
        Object value = yaml.get(key);
        List<String> out = new ArrayList<String>();
        if (value == null) return out;
        if (value instanceof List) {
            for (Object o : (List<?>) value) out.add(String.valueOf(o));
        } else if (value instanceof Map) {
            throw new PluginException("plugin.yml '" + key + "' must be a list");
        } else {
            out.add(value.toString());
        }
        return out;
    }

    private static Map<String, CommandSpec> commands(Object section) {
        Map<String, CommandSpec> out = new LinkedHashMap<String, CommandSpec>();
        if (section == null) return out;
        if (!(section instanceof Map)) throw new PluginException("plugin.yml 'commands' must be a map of names");
        for (Map.Entry<?, ?> e : ((Map<?, ?>) section).entrySet()) {
            String cmd = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
            Map<?, ?> spec = e.getValue() == null ? Collections.emptyMap() : asMap(e.getValue(), "commands." + cmd);
            String usage = optional(spec, "usage", "/<command>").replace("<command>", cmd);
            List<String> aliases = new ArrayList<String>();
            for (String a : strings(spec, "aliases")) aliases.add(a.toLowerCase(Locale.ROOT));
            if (spec.containsKey("permission-message")) {
                throw new PluginException("plugin.yml commands." + cmd + ": 'permission-message' never shows. Hadron "
                        + "hides a command from anyone without its permission, as vanilla does. Remove the line.");
            }
            out.put(cmd, new CommandSpec(cmd, optional(spec, "description", ""), usage, aliases,
                    optional(spec, "permission", null)));
        }
        return out;
    }

    private static List<PermissionSpec> permissions(Object section) {
        List<PermissionSpec> out = new ArrayList<PermissionSpec>();
        if (section == null) return out;
        if (!(section instanceof Map)) throw new PluginException("plugin.yml 'permissions' must be a map of nodes");
        for (Map.Entry<?, ?> e : ((Map<?, ?>) section).entrySet()) {
            String node = String.valueOf(e.getKey());
            Map<?, ?> spec = e.getValue() == null ? Collections.emptyMap() : asMap(e.getValue(), "permissions." + node);
            if (spec.containsKey("children")) {
                throw new PluginException("plugin.yml permissions." + node + ": 'children' isn't supported. Give each "
                        + "node its own entry; groups can grant " + node.split("\\.")[0] + ".* for all of them.");
            }
            out.add(new PermissionSpec(node, optional(spec, "description", ""),
                    fallback(optional(spec, "default", "op"), node)));
        }
        return out;
    }

    private static Default fallback(String value, String node) {
        String v = value.toLowerCase(Locale.ROOT);
        if (v.equals("true")) return Default.EVERYONE;
        if (v.equals("false")) return Default.NOBODY;
        if (v.equals("op")) return Default.OPS;
        throw new PluginException("plugin.yml permissions." + node + " default '" + value
                + "': use true, false or op");
    }

    private static Map<?, ?> asMap(Object value, String key) {
        if (!(value instanceof Map)) throw new PluginException("plugin.yml '" + key + "' must be a map");
        return (Map<?, ?>) value;
    }
}
