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
import java.util.HashMap;
import java.util.Map;
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
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/** {@code /region} (alias {@code /rg}): every way to make, change and look at regions. */
final class RegionCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("wand", "define", "redefine", "remove", "restore", "bypass",
            "list", "info", "show", "flag", "allow", "deny", "unset", "undo", "why", "watch", "priority", "addowner",
            "addmember", "removeplayer");
    /** What a region's owners may do to it without regions.manage. */
    private static final List<String> OWNER = List.of("info", "show", "flag", "allow", "deny", "unset", "undo", "why",
            "watch", "addowner", "addmember", "removeplayer");
    /** Subcommands that check their own permission, so anyone may type them. */
    private static final List<String> ANYONE = List.of("list", "info", "show", "flag", "allow", "deny", "unset",
            "undo", "why", "watch", "addowner", "addmember", "removeplayer", "bypass");
    /** The subcommands that set rules, each its verdict; unset has none. */
    private static final List<String> RULE_SUBCOMMANDS = List.of("allow", "deny", "unset");
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
            case "allow", "deny", "unset" -> rule(sender, sub, args);
            case "undo" -> undo(sender);
            case "why" -> why(sender, args);
            case "watch" -> {
                boolean on = feedback.toggleWatch(player(sender));
                ok(sender, on ? "Watching refusals within 32 blocks in regions you manage · /region watch stops"
                        : "Stopped watching");
            }
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
        String usage = "/region flag <region> <" + Bundle.list().replace(", ", "|") + "> [allow|deny]";
        Region r = managed(sender, arg(args, 1, usage));
        String key = arg(args, 2, usage);
        Bundle flag = Bundle.named(key.toLowerCase(Locale.ROOT)).orElseThrow(() -> new Refusal("No flag " + key
                + "; the flags are " + Bundle.list()));
        Optional<Rules.Verdict> value;
        if (args.length < 4) value = Optional.empty();
        else if (args[3].equalsIgnoreCase("allow")) value = Optional.of(Rules.Verdict.ALLOW);
        else if (args[3].equalsIgnoreCase("deny")) value = Optional.of(Rules.Verdict.DENY);
        else throw new Refusal("A flag is allow or deny, or leave it off to clear it, but was " + args[3]);
        changeRules(sender, r, r.rules().withFlag(flag, value), Subject.EVERYONE);
    }

    /**
     * {@code /region allow|deny|unset <region> [subject] <node>...}: each node or flag allowed, denied or taken out of
     * the subject's lists (everyone when no subject is named). Nodes go through the same parser as regions.yml.
     */
    private void rule(CommandSourceStack sender, String sub, String[] args) {
        String usage = "/region " + sub + " <region> [" + Subject.list().replace(", ", "|") + "] <node>...";
        Region r = managed(sender, arg(args, 1, usage));
        int at = 2;
        Optional<Subject> named = Subject.parse(arg(args, 2, usage).toLowerCase(Locale.ROOT));
        if (named.isPresent()) at = 3;
        Subject subject = named.orElse(Subject.EVERYONE);
        if (args.length <= at) throw new Refusal(usage);
        Optional<Rules.Verdict> verdict = switch (sub) {
            case "allow" -> Optional.of(Rules.Verdict.ALLOW);
            case "deny" -> Optional.of(Rules.Verdict.DENY);
            default -> Optional.empty();
        };
        Rules next = r.rules();
        for (int i = at; i < args.length; i++) {
            try {
                next = next.with(subject, Pattern.parse(args[i]), verdict);
            } catch (Pattern.Invalid e) {
                throw new Refusal(e.getMessage());
            }
        }
        changeRules(sender, r, next, subject);
    }

    /** Saves new rules, says the subject's rules as they now are, and offers [undo] in place of a confirmation. */
    private void changeRules(CommandSourceStack sender, Region r, Rules next, Subject shown) {
        if (next.equals(r.rules())) {
            ok(sender, r.id() + " already says that · " + answer(r.id(), next, shown));
            return;
        }
        store.put(r.withRules(next));
        undo.put(senderKey(sender), new Undo(r.id(), r.rules(), next));
        sender.sendSuccess(() -> Component.literal(answer(r.id(), next, shown) + " ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("[undo]").withStyle(st -> st.withColor(ChatFormatting.YELLOW)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/region undo"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Put " + r.id() + "'s rules back"))))), false);
    }

    private static String answer(String id, Rules rules, Subject subject) {
        Rules.Lists lists = rules.of(subject);
        return id + " · " + subject.key() + ": deny " + lists.deny() + " · allow " + lists.allow();
    }

    /** What one sender's last rule change was, so {@code [undo]} can put it back. */
    private record Undo(String region, Rules before, Rules after) {}

    private final Map<String, Undo> undo = new HashMap<>();

    private static String senderKey(CommandSourceStack sender) {
        return sender.getEntity() instanceof ServerPlayer p ? p.getUUID().toString() : sender.getTextName();
    }

    /** Puts back the rules the sender's last change replaced, unless they changed again since. */
    private void undo(CommandSourceStack sender) {
        Undo last = undo.get(senderKey(sender));
        if (last == null) throw new Refusal("Nothing of yours to undo since the plugin started");
        Region r = managed(sender, last.region());
        if (!r.rules().equals(last.after())) {
            throw new Refusal(r.id() + "'s rules changed since your last change, so it wasn't undone; /region info "
                    + r.id() + " shows them now");
        }
        store.put(r.withRules(last.before()));
        undo.remove(senderKey(sender));
        ok(sender, "Undone · " + r.id() + " · everyone: deny " + last.before().of(Subject.EVERYONE).deny()
                + " · allow " + last.before().of(Subject.EVERYONE).allow());
    }

    /**
     * {@code /region why <node> [subject]}: at your feet, each region you manage from the highest priority down, what
     * it answers and why, then what every region there decides, resolved as protection resolves it. A region you
     * don't manage decides without its id or rules, so ids don't leak. With no subject, you are judged as you are in
     * each region (a member or everyone).
     */
    private void why(CommandSourceStack sender, String[] args) {
        String usage = "/region why <node> [" + Subject.list().replace(", ", "|") + "]";
        ServerPlayer p = player(sender);
        Node node;
        try {
            node = Pattern.parse(arg(args, 1, usage).toLowerCase(Locale.ROOT)).leaf().orElseThrow(() -> new Refusal(
                    "Name one node, such as entity.hurt.animal: " + args[1] + " covers several"));
        } catch (Pattern.Invalid e) {
            throw new Refusal(e.getMessage());
        }
        Who who = new Who.Person(p.getUUID());
        if (args.length > 2) {
            Subject as = Subject.parse(args[2].toLowerCase(Locale.ROOT)).orElseThrow(() -> new Refusal(
                    args[2] + " isn't a subject; the subjects are " + Subject.list()));
            who = switch (as) {
                case MEMBERS -> new Who.Member();
                case EVERYONE -> new Who.Anyone();
                // As if it set out from where they stand, so inside every region here.
                case WORLD -> new Who.World(List.of(new Who.Home.At(p.serverLevel().dimension().location(),
                        new BoundingBox(p.blockPosition()))));
                case UNKNOWN -> new Who.Unknown();
            };
        }
        List<Region> here = new ArrayList<>(store.at(p.serverLevel(), p.blockPosition()));
        here.sort(Comparator.comparingInt(Region::priority).reversed().thenComparing(Region::id));
        if (here.stream().noneMatch(r -> canManage(sender, r))) throw new Refusal("No region you manage is at your feet");
        for (Region r : here) {
            if (!canManage(sender, r)) continue;
            Optional<Resolve.Decision> d = Resolve.answer(r, who, node);
            ok(sender, r.id() + " (" + r.priority() + "): " + d.map(RegionCommand::reason)
                    .orElse(who.subjectIn(r).key() + ", no rule, passes"));
        }
        // Refused if protection refuses; otherwise the topmost region with an answer is the one that let it through.
        Who judged = who;
        Optional<Resolve.Decision> deciding = Resolve.refusing(here, who, node).or(() -> here.stream()
                .filter(Resolve.answers(judged, node, List.of())).findFirst()
                .flatMap(r -> Resolve.answer(r, judged, node)));
        if (deciding.isEmpty()) {
            ok(sender, "→ allowed: no region here decides " + node.key());
            return;
        }
        Resolve.Decision d = deciding.get();
        String verdict = d.refuses() ? "refused by " : "allowed by ";
        if (!canManage(sender, d.region())) {
            ok(sender, "→ " + verdict + "a region you don't manage");
            return;
        }
        ok(sender, "→ " + verdict + d.region().id());
        feedback.outline(p, d.region().world(), d.region().box(), d.refuses() ? Feedback.Look.REFUSING
                : Feedback.Look.REGION);
    }

    /** One region's answer, as {@code /region why} says it: {@code everyone deny build ⊃ entity.hurt.animal → refused}. */
    private static String reason(Resolve.Decision d) {
        String rule = switch (d.rule()) {
            case Resolve.Rule.Named n -> d.subject().key() + " " + n.verdict().key() + " " + (n.pattern().toString()
                    .equals(d.node().key()) ? d.node().key() : n.pattern() + " ⊃ " + d.node().key());
            case Resolve.Rule.Implicit i -> d.subject().key() + ", " + i.why();
        };
        return rule + (d.refuses() ? " → refused" : " → allowed");
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
            for (Subject subject : Subject.values()) {
                Rules.Lists lists = r.rules().of(subject);
                if (lists.isEmpty()) continue;
                ok(sender, "  " + subject.key() + ": deny " + lists.deny() + " · allow " + lists.allow());
            }
            if (sender.getEntity() instanceof ServerPlayer p) feedback.outline(p, r.world(), b, Feedback.Look.REGION);
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(CommandSourceStack sender, PluginCommand command, String label,
            String[] args) {
        boolean admin = Permissions.has(sender, RegionsPlugin.MANAGE);
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (RULE_SUBCOMMANDS.contains(sub) && args.length >= 3) {
            // The subject, or straight to nodes; the tree is walked one segment at a time.
            List<String> out = new ArrayList<>(Pattern.completions(last));
            if (args.length == 3) for (Subject s : Subject.values()) if (s.key().startsWith(last)) out.add(s.key());
            return out;
        }
        if (sub.equals("why")) {
            if (args.length == 2) return Pattern.completions(last);
            if (args.length == 3) return Arrays.stream(Subject.values()).map(Subject::key)
                    .filter(k -> k.startsWith(last)).toList();
            return List.of();
        }
        List<String> options = switch (args.length) {
            case 0, 1 -> admin ? SUBCOMMANDS : ANYONE;
            case 2 -> switch (sub) {
                // Only admins restore, so only they learn which removed regions there are.
                case "restore" -> admin ? List.copyOf(store.restorable()) : List.of();
                case "wand", "list", "define", "undo", "watch" -> List.of();
                default -> store.all().stream().filter(r -> canManage(sender, r)).map(Region::id).toList();
            };
            case 3 -> switch (sub) {
                case "flag" -> List.of(Bundle.list().split(", "));
                case "addowner", "addmember", "removeplayer" -> plugin.getServer().getOnlinePlayers().stream()
                        .map(p -> p.getGameProfile().getName()).toList();
                default -> List.of();
            };
            case 4 -> sub.equals("flag") ? List.of("allow", "deny") : List.of();
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

    private static void ok(CommandSourceStack sender, String text) {
        sender.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
    }
}
