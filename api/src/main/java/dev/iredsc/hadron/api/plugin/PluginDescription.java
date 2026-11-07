package dev.iredsc.hadron.api.plugin;

import org.jspecify.annotations.Nullable;
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
 * {@code commands} and {@code permissions} are optional. Hadron also requires {@code minecraft}, the Minecraft version
 * the plugin was built for, which the build fills in. {@code mod-depend} lists mods by mod id whose classes the plugin
 * uses directly; that ties it to one loader, so it also needs {@code loader} (such as {@code neoforge}). A plugin
 * without mod-depend runs on every loader. Anything malformed fails the load with a message naming the key, so a typo
 * can't quietly drop a command or a dependency.
 */
public final class PluginDescription {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    /**
     * The name is also the plugin's data folder, {@code plugins/<name>/}. Starting with a letter or digit keeps it out of
     * {@code .loaded} (Hadron's jar cache, emptied at every start) and away from {@code .} and {@code ..}.
     */
    private static final Pattern FOLDER_SAFE = Pattern.compile("[A-Za-z0-9].*");

    /** A command under {@code commands:}. */
    public static final class CommandSpec {
        public final String name;
        public final String description;
        /** With {@code <command>} replaced by the name. */
        public final String usage;
        public final List<String> aliases;
        /** Null when anyone may run it. */
        public final @Nullable String permission;

        CommandSpec(String name, String description, String usage, List<String> aliases, @Nullable String permission) {
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
    private final @Nullable String loader;
    private final Map<String, CommandSpec> commands;
    private final List<PermissionSpec> permissions;

    private PluginDescription(Map<?, ?> yaml) {
        name = required(yaml, "name");
        if (!NAME.matcher(name).matches()) {
            throw new PluginException("plugin.yml name '" + name + "' may only use letters, digits, _ . and -");
        }
        if (!FOLDER_SAFE.matcher(name).matches() || name.equalsIgnoreCase("disabled.txt")
                || name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new PluginException("plugin.yml name '" + name + "' would make the plugin's data folder plugins/"
                    + name + " one of Hadron's own files there. Start the name with a letter or digit and don't end it "
                    + "in .jar, such as name: " + suggestion(name));
        }
        version = required(yaml, "version");
        main = required(yaml, "main");
        description = optional(yaml, "description", "");
        List<String> authorList = new ArrayList<String>(strings(yaml, "authors"));
        String author = optional(yaml, "author");
        if (author != null) authorList.add(0, author);
        authors = Collections.unmodifiableList(authorList);
        website = optional(yaml, "website", "");
        depend = Collections.unmodifiableList(strings(yaml, "depend"));
        softDepend = Collections.unmodifiableList(strings(yaml, "softdepend"));
        modDepend = Collections.unmodifiableList(strings(yaml, "mod-depend"));
        String minecraft = optional(yaml, "minecraft");
        if (minecraft == null || minecraft.isEmpty()) {
            throw new PluginException("plugin.yml needs 'minecraft', the Minecraft version the plugin was built for, "
                    + "such as 1.21.1. Hadron's Gradle plugin fills it in; by hand, add minecraft: ${minecraft_version} "
                    + "and expand it in processResources");
        }
        this.minecraft = minecraft;
        loader = optional(yaml, "loader");
        if (!modDepend.isEmpty() && loader == null) {
            throw new PluginException("plugin.yml lists mod-depend, so it uses those mods' classes, which differ per "
                    + "loader. Add the loader it was built for, such as loader: neoforge");
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

    /** The loader this plugin was built for, or null when it runs on any. Set by plugins that use mods directly. */
    public @Nullable String getLoader() {
        return loader;
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

    /** A usable name close to a refused one, for the error message. */
    private static String suggestion(String name) {
        String trimmed = name.replaceAll("(?i)(\\.jar)+$", "").replaceAll("^[^A-Za-z0-9]+", "");
        if (trimmed.equalsIgnoreCase("disabled.txt")) trimmed = "";
        return trimmed.isEmpty() ? "MyPlugin" : trimmed;
    }

    /** A line of plugin.yml with the key written right, for messages that refuse it. */
    private static String example(String key) {
        return switch (key) {
            case "name" -> "name: MyPlugin";
            case "version" -> "version: \"1.0\"";
            case "main" -> "main: com.example.myplugin.MyPlugin (the full name of its class extending JavaPlugin)";
            case "description" -> "description: What the plugin does";
            case "author" -> "author: YourName";
            case "website" -> "website: https://example.com";
            case "loader" -> "loader: neoforge";
            case "depend", "softdepend" -> key + ": [OtherPlugin]";
            case "mod-depend" -> "mod-depend: [modid]";
            case "authors" -> "authors: [YourName]";
            case "aliases" -> "aliases: [shortname]";
            default -> key + ": value";
        };
    }

    private static String required(Map<?, ?> yaml, String key) {
        String value = optional(yaml, key);
        if (value == null || value.isEmpty()) {
            throw new PluginException("plugin.yml needs '" + key + "', such as " + example(key));
        }
        return value;
    }

    private static String optional(Map<?, ?> yaml, String key, String fallback) {
        String value = optional(yaml, key);
        return value == null ? fallback : value;
    }

    private static @Nullable String optional(Map<?, ?> yaml, String key) {
        Object value = yaml.get(key);
        if (value == null) return null;
        // YAML reads an unquoted 1.10 as the number 1.1 before Hadron sees it, so a version could change silently.
        if (value instanceof Number n && (key.equals("version") || key.equals("minecraft"))) {
            throw new PluginException("plugin.yml '" + key + "' must be quoted: unquoted, YAML reads it as the number "
                    + n + ", so a version like 1.10 would become 1.1. Write " + key + ": \"" + n + "\" with the exact "
                    + "version you mean between the quotes");
        }
        if (value instanceof Map || value instanceof List) {
            throw new PluginException("plugin.yml '" + key + "' must be a single value, such as " + example(key));
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
            throw new PluginException("plugin.yml '" + key + "' must be a list, such as " + example(key));
        } else {
            out.add(value.toString());
        }
        return out;
    }

    private static Map<String, CommandSpec> commands(@Nullable Object section) {
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
                    optional(spec, "permission")));
        }
        return out;
    }

    private static List<PermissionSpec> permissions(@Nullable Object section) {
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
