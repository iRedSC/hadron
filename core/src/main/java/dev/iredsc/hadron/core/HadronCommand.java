package dev.iredsc.hadron.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.iredsc.hadron.api.Permissions;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * {@code /plugins} (and {@code /pl}) lists every plugin, colored by state; hover for why, click for details.
 * {@code /hadron reload [plugin]}, {@code /hadron enable|disable|info <plugin>} manage them. Reloading by hand is
 * rarely needed: a changed jar reloads by itself.
 */
final class HadronCommand {
    private static final SimpleCommandExceptionType NOT_RUNNING =
            new SimpleCommandExceptionType(Component.literal("No server is running plugins yet"));
    private static final DynamicCommandExceptionType NO_SUCH = new DynamicCommandExceptionType(
            name -> Component.literal("No plugin named " + name + ". See /plugins"));

    private HadronCommand() {}

    /** Hadron's own commands, added to each new dispatcher. The host is looked up per run, since it lives per server. */
    static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<PluginHost> hosts) {
        var list = dispatcher.register(Commands.literal("plugins")
                .requires(s -> Permissions.has(s, PluginHost.LIST))
                .executes(ctx -> list(ctx, hosts)));
        dispatcher.register(Commands.literal("pl").requires(list.getRequirement()).executes(ctx -> list(ctx, hosts)));
        dispatcher.register(Commands.literal("hadron")
                .requires(s -> Permissions.has(s, PluginHost.MANAGE))
                .executes(ctx -> list(ctx, hosts))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            host(hosts).reloadAll();
                            return console(ctx) ? list(ctx, hosts) : 1;
                        })
                        .then(named(hosts, (host, e) -> host.reload(e))))
                .then(Commands.literal("enable").then(named(hosts, PluginHost::switchOn)))
                .then(Commands.literal("disable").then(named(hosts, PluginHost::switchOff)))
                .then(Commands.literal("info").then(Commands.argument("plugin", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(names(hosts), b))
                        .executes(ctx -> {
                            info(ctx.getSource(), entry(ctx, hosts));
                            return 1;
                        }))));
    }

    private interface Action {
        void run(PluginHost host, PluginHost.Entry entry);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> named(Supplier<PluginHost> hosts, Action action) {
        return Commands.argument("plugin", StringArgumentType.word())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(names(hosts), b))
                .executes(ctx -> {
                    PluginHost.Entry e = entry(ctx, hosts);
                    action.run(host(hosts), e);
                    if (console(ctx)) ctx.getSource().sendSystemMessage(name(e).append(" " + summary(e)));
                    return 1;
                });
    }

    /**
     * Players who may manage plugins already hear every result as it happens; anyone else running these (the
     * console, RCON) gets it as the command's reply.
     */
    private static boolean console(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getPlayer() == null;
    }

    private static Iterable<String> names(Supplier<PluginHost> hosts) {
        PluginHost host = hosts.get();
        return host == null ? java.util.List.of() : host.entries().stream().map(e -> e.name).toList();
    }

    private static PluginHost host(Supplier<PluginHost> hosts) throws CommandSyntaxException {
        PluginHost host = hosts.get();
        if (host == null) throw NOT_RUNNING.create();
        return host;
    }

    private static PluginHost.Entry entry(CommandContext<CommandSourceStack> ctx, Supplier<PluginHost> hosts)
            throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "plugin");
        PluginHost.Entry e = host(hosts).entry(name);
        if (e == null) throw NO_SUCH.create(name);
        return e;
    }

    private static int list(CommandContext<CommandSourceStack> ctx, Supplier<PluginHost> hosts)
            throws CommandSyntaxException {
        PluginHost host = host(hosts);
        MutableComponent line = Component.literal("Plugins (" + host.entries().size() + "): ");
        boolean first = true;
        for (PluginHost.Entry e : host.entries()) {
            if (!first) line.append(", ");
            first = false;
            line.append(name(e));
        }
        if (host.entries().isEmpty()) line.append("none in plugins/");
        ctx.getSource().sendSystemMessage(line);
        return host.entries().size();
    }

    private static MutableComponent name(PluginHost.Entry e) {
        ChatFormatting color = e.state instanceof PluginHost.State.Enabled ? ChatFormatting.GREEN
                : e.state instanceof PluginHost.State.Failed ? ChatFormatting.RED : ChatFormatting.GRAY;
        String hover = e.description.getFullName() + "\n" + summary(e);
        return Component.literal(e.name).withStyle(Style.EMPTY.withColor(color)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover)))
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hadron info " + e.name)));
    }

    private static String summary(PluginHost.Entry e) {
        if (e.state instanceof PluginHost.State.Failed f) return "Didn't enable: " + f.why;
        if (e.state instanceof PluginHost.State.Off) return "Switched off. /hadron enable " + e.name;
        if (e.state instanceof PluginHost.State.Enabled) return "Enabled";
        return "Not running";
    }

    private static void info(CommandSourceStack sender, PluginHost.Entry e) {
        sender.sendSystemMessage(name(e).append(Component.literal(" " + e.description.getVersion())));
        Consumer<String> line = text -> sender.sendSystemMessage(
                Component.literal("  " + text).withStyle(ChatFormatting.GRAY));
        line.accept(summary(e));
        var d = e.description;
        if (!d.getDescription().isEmpty()) line.accept(d.getDescription());
        if (!d.getAuthors().isEmpty()) line.accept("By " + String.join(", ", d.getAuthors()));
        if (!d.getDepend().isEmpty()) line.accept("Needs " + String.join(", ", d.getDepend()));
        if (!d.getModDepend().isEmpty()) line.accept("Needs mods " + String.join(", ", d.getModDepend()));
        if (!d.getSoftDepend().isEmpty()) line.accept("Uses, if present: " + String.join(", ", d.getSoftDepend()));
        line.accept("Built for Minecraft " + d.getMinecraft());
        line.accept("Jar: plugins/" + e.jar.getName());
    }
}
