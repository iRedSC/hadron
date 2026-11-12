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
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/** {@code /region} (alias {@code /rg}): every way to make, change and look at regions. */
final class RegionCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("wand", "define", "redefine", "remove", "restore", "bypass",
            "list", "info", "show", "flag", "priority", "addowner", "addmember", "removeplayer");
    /** What a region's owners may do to it without regions.manage. */
    private static final List<String> OWNER = List.of("info", "show", "flag", "addowner", "addmember",
            "removeplayer");
    /** Subcommands that check their own permission, so anyone may type them. */
    private static final List<String> ANYONE = List.of("list", "info", "show", "flag", "addowner", "addmember",
            "removeplayer", "bypass");
    private static final String USAGE = "/region <" + String.join("|", SUBCOMMANDS) + ">";

    private final JavaPlugin plugin;
    private final RegionStore store;
    private final Wand wand;
    private final Feedback feedback;
    private final Bypass bypass;

    RegionCommand(JavaPlugin plugin, RegionStore store, Wand wand, Feedback feedback, Bypass bypass) {
        this.plugin = plugin;
        this.store = store;
        this.wand = wand;
        this.feedback = feedback;
        this.bypass = bypass;
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
        guarded(sender, () -> run(sender, args));
        return true;
    }

    /** Runs it, telling the sender what stopped it. */
    private void guarded(CommandSourceStack sender, Runnable action) {
        try {
            action.run();
        } catch (Refusal r) {
            sender.sendFailure(Component.literal(r.getMessage()));
        } catch (UncheckedIOException e) {
            sender.sendFailure(Component.literal(e.getMessage()));
            plugin.getLogger().error("Couldn't save a Regions file", e);
        }
    }

    private void run(CommandSourceStack sender, String[] args) {
        if (args.length == 0) throw new Refusal(USAGE);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!ANYONE.contains(sub)) requireAdmin(sender);
        switch (sub) {
            case "bypass" -> {
                ServerPlayer p = player(sender);
                if (!Permissions.has(p, RegionsPlugin.BYPASS)) {
                    throw new Refusal("You need " + RegionsPlugin.BYPASS.id + " to bypass regions");
                }
                Component now = Bypass.status(bypass.toggle(p));
                sender.sendSuccess(() -> now, false);
            }
            case "wand" -> {
                ServerPlayer p = player(sender);
                if (!p.getInventory().add(Wand.make())) p.drop(Wand.make(), false);
                ok(sender, "Sneak and right-click a block for one corner, right-click for the other");
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
                Region r = managed(sender, arg(args, 1, "/region show <region>"));
                ServerPlayer p = player(sender);
                if (!p.serverLevel().dimension().location().equals(r.world())) {
                    throw new Refusal(r.id() + " is in " + r.world() + "; go there to see it");
                }
                feedback.outline(p, r.world(), r.box(), Feedback.Look.REGION);
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
                Region r = managed(sender, arg(args, 1, "/region " + sub + " <region> <player>"));
                String name = arg(args, 2, "/region " + sub + " " + r.id() + " <player>");
                lookUp(sender, name, who -> add(sender, r.id(), who, owner));
            }
            case "removeplayer" -> {
                Region r = managed(sender, arg(args, 1, "/region removeplayer <region> <player>"));
                String name = arg(args, 2, "/region removeplayer " + r.id() + " <player>");
                UUID who = inRegion(r, name);
                store.put(r.without(who));
                ok(sender, name + " left " + r.id());
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
        feedback.outline(p, s.world(), box, Feedback.Look.REGION);
        ok(sender, (redefine ? "Moved " : "Defined ") + id + " · " + Selection.size(box));
    }

    private void flag(CommandSourceStack sender, String[] args) {
        String usage = "/region flag <region> <" + RegionsFile.flagList().replace(", ", "|") + "> [allow|deny]";
        Region r = managed(sender, arg(args, 1, usage));
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
        List<Region> all = new ArrayList<>(store.all().stream().filter(r -> canManage(sender, r)).toList());
        if (all.isEmpty()) {
            boolean admin = Permissions.has(sender, RegionsPlugin.MANAGE);
            ok(sender, admin ? "No regions yet: /region wand, then /region define <name>" : "You own no regions");
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
            which = List.of(managed(sender, args[1]));
        } else {
            ServerPlayer p = player(sender);
            which = store.at(p.serverLevel(), p.blockPosition()).stream().filter(r -> canManage(sender, r)).toList();
            if (which.isEmpty()) throw new Refusal("You aren't in a region you manage; /region info <region> names one");
        }
        for (Region r : which) {
            BoundingBox b = r.box();
            ok(sender, r.id() + " · " + r.world() + " " + b.minX() + " " + b.minY() + " " + b.minZ() + " to "
                    + b.maxX() + " " + b.maxY() + " " + b.maxZ() + " · priority " + r.priority());
            ok(sender, "  owners: " + names(r.owners()) + " · members: " + names(r.members()));
            List<String> flags = new ArrayList<>();
            for (Flag f : Flag.values()) r.flag(f).ifPresent(v -> flags.add(f.key() + " " + word(v)));
            ok(sender, "  flags: " + (flags.isEmpty() ? "defaults" : String.join(", ", flags)));
            if (sender.getEntity() instanceof ServerPlayer p) feedback.outline(p, r.world(), b, Feedback.Look.REGION);
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(CommandSourceStack sender, PluginCommand command, String label,
            String[] args) {
        boolean admin = Permissions.has(sender, RegionsPlugin.MANAGE);
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = switch (args.length) {
            case 0, 1 -> admin ? SUBCOMMANDS : ANYONE;
            case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                // Only admins restore, so only they learn which removed regions there are.
                case "restore" -> admin ? List.copyOf(store.restorable()) : List.of();
                case "wand", "list", "define" -> List.of();
                default -> store.all().stream().filter(r -> canManage(sender, r)).map(Region::id).toList();
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
        if (sender.getEntity() instanceof ServerPlayer p) feedback.outline(p, r.world(), r.box(), Feedback.Look.REGION);
    }

    /** Refuses anyone without {@code regions.manage}. */
    private static void requireAdmin(CommandSourceStack sender) {
        if (Permissions.has(sender, RegionsPlugin.MANAGE)) return;
        throw new Refusal("You need " + RegionsPlugin.MANAGE.id + " for that; owners of a region can use "
                + String.join(", ", OWNER));
    }

    /** Whether the sender may change this region: an admin, or one of its owners holding regions.manage.owned. */
    private static boolean canManage(CommandSourceStack sender, Region r) {
        if (Permissions.has(sender, RegionsPlugin.MANAGE)) return true;
        return sender.getEntity() instanceof ServerPlayer p && r.owners().contains(p.getUUID())
                && Permissions.has(p, RegionsPlugin.MANAGE_OWNED);
    }

    /** The region, if the sender may change it; another's region reads as missing, so ids don't leak. */
    private Region managed(CommandSourceStack sender, String id) {
        Optional<Region> r = store.get(id.toLowerCase(Locale.ROOT));
        if (r.isPresent() && canManage(sender, r.get())) return r.get();
        if (Permissions.has(sender, RegionsPlugin.MANAGE)) throw noSuch(id);
        throw new Refusal("You don't own a region named " + id);
    }

    private Region region(String id) {
        return store.get(id.toLowerCase(Locale.ROOT)).orElseThrow(() -> noSuch(id));
    }

    private Refusal noSuch(String id) {
        List<String> ids = store.all().stream().map(Region::id).sorted().toList();
        return new Refusal("No region named " + id + (ids.isEmpty() ? "; there are none yet"
                : "; there are " + String.join(", ", ids)));
    }

    /**
     * Finds the player by name and hands them to {@code then} on the server thread. Someone online answers at once;
     * anyone else is looked up off the server thread, since a name the server hasn't cached asks Mojang, and a wait
     * there would stall the world.
     */
    private void lookUp(CommandSourceStack sender, String name, Consumer<GameProfile> then) {
        ServerPlayer online = plugin.getServer().getPlayer(name);
        if (online != null) {
            then.accept(online.getGameProfile());
            return;
        }
        GameProfileCache cache = plugin.getServer().getMinecraftServer().getProfileCache();
        if (cache == null) throw new Refusal(unknownPlayer(name));
        cache.getAsync(name).whenComplete((found, error) -> {
            if (!plugin.isEnabled()) return;
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> guarded(sender, () -> {
                    if (error != null || found == null || found.isEmpty()) throw new Refusal(unknownPlayer(name));
                    then.accept(found.get());
                }));
            } catch (IllegalStateException disabledMeanwhile) {
                // The plugin stopped while the lookup ran; there's nothing left to change.
            }
        });
    }

    /** Adds them, checking again that the sender may still change the region, since a lookup can take a while. */
    private void add(CommandSourceStack sender, String id, GameProfile who, boolean owner) {
        Region r = managed(sender, id);
        store.put(owner ? r.withOwner(who.getId()) : r.withMember(who.getId()));
        ok(sender, who.getName() + (owner ? " owns " : " is a member of ") + r.id());
    }

    /** An owner or member of the region by name or UUID, from what the server already knows: no lookup. */
    private UUID inRegion(Region r, String name) {
        Set<UUID> people = new LinkedHashSet<>(r.owners());
        people.addAll(r.members());
        for (UUID id : people) {
            if (id.toString().equalsIgnoreCase(name) || name.equalsIgnoreCase(nameOf(id))) return id;
        }
        throw new Refusal(name + " isn't in " + r.id());
    }

    private static String unknownPlayer(String name) {
        return "No player is named " + name;
    }

    /** Their name if the server knows it, from those online and its own cache; otherwise their UUID. */
    private String nameOf(UUID id) {
        ServerPlayer online = plugin.getServer().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        GameProfileCache cache = plugin.getServer().getMinecraftServer().getProfileCache();
        return cache == null ? id.toString() : cache.get(id).map(GameProfile::getName).orElse(id.toString());
    }

    private String names(Set<UUID> people) {
        if (people.isEmpty()) return "none";
        List<String> out = new ArrayList<>();
        for (UUID id : people) out.add(nameOf(id));
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
