package dev.iredsc.hadron.regions;

import com.mojang.authlib.GameProfile;
import dev.iredsc.hadron.api.Permissions;
import dev.iredsc.hadron.api.plugin.CommandExecutor;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginCommand;
import dev.iredsc.hadron.api.plugin.TabCompleter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/** {@code /region} (alias {@code /rg}): every way to make, change and look at regions. */
final class RegionCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("wand", "define", "redefine", "remove", "restore",
            "list", "info", "show", "flag", "priority", "addowner", "addmember", "removeplayer");
    private static final String USAGE = "/region <" + String.join("|", SUBCOMMANDS) + ">";

    private final JavaPlugin plugin;
    private final RegionStore store;
    private final Wand wand;
    private final Feedback feedback;

    RegionCommand(JavaPlugin plugin, RegionStore store, Wand wand, Feedback feedback) {
        this.plugin = plugin;
        this.store = store;
        this.wand = wand;
        this.feedback = feedback;
    }

    /** A mistake in what was typed, with the fix. */
    private static final class Refusal extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refusal(String message) {
            super(message, null, false, false);
        }
    }

    @Override
    public boolean onCommand(CommandSourceStack sender, PluginCommand command, String label, String[] args) {
        if (!Permissions.has(sender, RegionsPlugin.MANAGE)) {
            sender.sendFailure(Component.literal("You need " + RegionsPlugin.MANAGE.id + " to manage regions"));
            return true;
        }
        try {
            run(sender, args);
        } catch (Refusal r) {
            sender.sendFailure(Component.literal(r.getMessage()));
        } catch (UncheckedIOException e) {
            sender.sendFailure(Component.literal(e.getMessage()));
            plugin.getLogger().error("Couldn't save regions.yml", e);
        }
        return true;
    }

    private void run(CommandSourceStack sender, String[] args) {
        if (args.length == 0) throw new Refusal(USAGE);
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "wand" -> {
                ServerPlayer p = player(sender);
                if (!p.getInventory().add(Wand.make())) p.drop(Wand.make(), false);
                ok(sender, "Left-click a block for one corner, right-click for the other");
            }
            case "define" -> define(sender, args, false);
            case "redefine" -> define(sender, args, true);
            case "remove" -> {
                String id = arg(args, 1, "/region remove <region>");
                Region gone = store.remove(id.toLowerCase(Locale.ROOT)).orElseThrow(() -> noSuch(id));
                ok(sender, "Removed " + gone.id() + " · /region restore " + gone.id() + " puts it back");
            }
            case "restore" -> {
                String id = arg(args, 1, "/region restore <region>");
                if (store.get(id).isPresent()) throw new Refusal(id + " exists; remove it first to restore the old one");
                Region back = store.restore(id).orElseThrow(() -> new Refusal("Nothing named " + id
                        + " was removed since the plugin last started"));
                showIfPlayer(sender, back);
                ok(sender, "Restored " + id);
            }
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "show" -> {
                Region r = region(arg(args, 1, "/region show <region>"));
                feedback.outline(player(sender), r.box(), Feedback.Look.REGION);
            }
            case "flag" -> flag(sender, args);
            case "priority" -> {
                Region r = region(arg(args, 1, "/region priority <region> <number>"));
                String n = arg(args, 2, "/region priority " + r.id() + " <number>");
                int priority;
                try {
                    priority = Integer.parseInt(n);
                } catch (NumberFormatException e) {
                    throw new Refusal("Priority must be a whole number, such as 10, but was " + n);
                }
                store.put(r.withPriority(priority));
                ok(sender, r.id() + " has priority " + priority);
            }
            case "addowner", "addmember" -> {
                boolean owner = sub.equals("addowner");
                Region r = region(arg(args, 1, "/region " + sub + " <region> <player>"));
                GameProfile who = profile(arg(args, 2, "/region " + sub + " " + r.id() + " <player>"));
                store.put(owner ? r.withOwner(who.getId()) : r.withMember(who.getId()));
                ok(sender, who.getName() + (owner ? " owns " : " is a member of ") + r.id());
            }
            case "removeplayer" -> {
                Region r = region(arg(args, 1, "/region removeplayer <region> <player>"));
                GameProfile who = profile(arg(args, 2, "/region removeplayer " + r.id() + " <player>"));
                if (!r.isMember(who.getId())) throw new Refusal(who.getName() + " isn't in " + r.id());
                store.put(r.without(who.getId()));
                ok(sender, who.getName() + " left " + r.id());
            }
            default -> throw new Refusal("No /region " + sub + ". " + USAGE);
        }
    }

    private void define(CommandSourceStack sender, String[] args, boolean redefine) {
        ServerPlayer p = player(sender);
        String id = arg(args, 1, "/region " + (redefine ? "redefine" : "define") + " <name>").toLowerCase(Locale.ROOT);
        if (!Region.validId(id)) throw new Refusal(Region.idProblem(id));
        Selection s = wand.selection(p).orElseThrow(() -> new Refusal("Pick two corners first: /region wand"));
        BoundingBox box = s.box().orElseThrow(() -> new Refusal("Pick the other corner too"));
        if (!s.world().equals(p.serverLevel().dimension().location())) {
            throw new Refusal("Your corners are in " + s.world() + "; pick them again in this world");
        }
        Optional<Region> old = store.get(id);
        Region next;
        if (redefine) {
            next = old.orElseThrow(() -> noSuch(id)).withBox(s.world(), box);
        } else {
            if (old.isPresent()) throw new Refusal(id + " exists; /region redefine " + id + " moves it here");
            next = Region.of(id, s.world(), box);
        }
        store.put(next);
        wand.clear(p);
        feedback.outline(p, box, Feedback.Look.REGION);
        ok(sender, (redefine ? "Moved " : "Defined ") + id + " · " + Selection.size(box));
    }

    private void flag(CommandSourceStack sender, String[] args) {
        String usage = "/region flag <region> <" + RegionsFile.flagList().replace(", ", "|") + "> [allow|deny]";
        Region r = region(arg(args, 1, usage));
        String key = arg(args, 2, usage);
        Flag flag = Flag.parse(key).orElseThrow(() -> new Refusal("No flag " + key + "; the flags are "
                + RegionsFile.flagList()));
        Boolean value;
        if (args.length < 4) value = null;
        else if (args[3].equalsIgnoreCase("allow")) value = true;
        else if (args[3].equalsIgnoreCase("deny")) value = false;
        else throw new Refusal("A flag is allow or deny, or leave it off to clear it, but was " + args[3]);
        store.put(r.withFlag(flag, value));
        ok(sender, r.id() + " " + flag.key() + ": " + (value == null ? "default (" + word(flag.outsiderDefault)
                + " for outsiders)" : word(value)));
    }

    private void list(CommandSourceStack sender) {
        List<Region> all = new ArrayList<>(store.all());
        if (all.isEmpty()) {
            ok(sender, "No regions yet: /region wand, then /region define <name>");
            return;
        }
        all.sort(Comparator.comparing(Region::id));
        for (Region r : all) {
            ok(sender, r.id() + " · " + r.world() + " · " + Selection.size(r.box()));
        }
    }

    private void info(CommandSourceStack sender, String[] args) {
        List<Region> which;
        if (args.length > 1) {
            which = List.of(region(args[1]));
        } else {
            ServerPlayer p = player(sender);
            which = store.at(p.serverLevel(), p.blockPosition());
            if (which.isEmpty()) throw new Refusal("You aren't in a region; /region info <region> names one");
        }
        for (Region r : which) {
            BoundingBox b = r.box();
            ok(sender, r.id() + " · " + r.world() + " " + b.minX() + " " + b.minY() + " " + b.minZ() + " to "
                    + b.maxX() + " " + b.maxY() + " " + b.maxZ() + " · priority " + r.priority());
            ok(sender, "  owners: " + names(r.owners()) + " · members: " + names(r.members()));
            List<String> flags = new ArrayList<>();
            for (Flag f : Flag.values()) r.flag(f).ifPresent(v -> flags.add(f.key() + " " + word(v)));
            ok(sender, "  flags: " + (flags.isEmpty() ? "defaults" : String.join(", ", flags)));
            if (sender.getEntity() instanceof ServerPlayer p) feedback.outline(p, b, Feedback.Look.REGION);
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(CommandSourceStack sender, PluginCommand command, String label,
            String[] args) {
        if (!Permissions.has(sender, RegionsPlugin.MANAGE)) return List.of();
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = switch (args.length) {
            case 0, 1 -> SUBCOMMANDS;
            case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "restore" -> List.copyOf(store.restorable());
                case "wand", "list", "define" -> List.of();
                default -> store.all().stream().map(Region::id).toList();
            };
            case 3 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "flag" -> Arrays.stream(Flag.values()).map(Flag::key).toList();
                case "addowner", "addmember", "removeplayer" -> plugin.getServer().getOnlinePlayers().stream()
                        .map(p -> p.getGameProfile().getName()).toList();
                default -> List.of();
            };
            case 4 -> args[0].equalsIgnoreCase("flag") ? List.of("allow", "deny") : List.of();
            default -> List.<String>of();
        };
        return options.stream().filter(o -> o.startsWith(last)).sorted().toList();
    }

    private void showIfPlayer(CommandSourceStack sender, Region r) {
        if (sender.getEntity() instanceof ServerPlayer p) feedback.outline(p, r.box(), Feedback.Look.REGION);
    }

    private Region region(String id) {
        return store.get(id.toLowerCase(Locale.ROOT)).orElseThrow(() -> noSuch(id));
    }

    private Refusal noSuch(String id) {
        List<String> ids = store.all().stream().map(Region::id).sorted().toList();
        return new Refusal("No region named " + id + (ids.isEmpty() ? "; there are none yet"
                : "; there are " + String.join(", ", ids)));
    }

    private GameProfile profile(String name) {
        MinecraftServer server = plugin.getServer().getMinecraftServer();
        ServerPlayer online = plugin.getServer().getPlayer(name);
        if (online != null) return online.getGameProfile();
        GameProfileCache cache = server.getProfileCache();
        Optional<GameProfile> known = cache == null ? Optional.empty() : cache.get(name);
        return known.orElseThrow(() -> new Refusal("No player named " + name + " has played here; they need to "
                + "join once first"));
    }

    private String names(Set<UUID> people) {
        if (people.isEmpty()) return "none";
        GameProfileCache cache = plugin.getServer().getMinecraftServer().getProfileCache();
        List<String> out = new ArrayList<>();
        for (UUID id : people) {
            ServerPlayer online = plugin.getServer().getPlayer(id);
            String name = online != null ? online.getGameProfile().getName()
                    : cache == null ? null : cache.get(id).map(GameProfile::getName).orElse(null);
            out.add(name != null ? name : id.toString());
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(", ", out);
    }

    private static ServerPlayer player(CommandSourceStack sender) {
        if (sender.getEntity() instanceof ServerPlayer p) return p;
        throw new Refusal("Only a player can do that: it uses where you are");
    }

    private static String arg(String[] args, int i, String usage) {
        if (args.length <= i) throw new Refusal(usage);
        return args[i];
    }

    private static String word(boolean allowed) {
        return allowed ? "allow" : "deny";
    }

    private static void ok(CommandSourceStack sender, String text) {
        sender.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
    }
}
