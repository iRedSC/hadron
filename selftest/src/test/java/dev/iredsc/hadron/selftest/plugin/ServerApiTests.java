package dev.iredsc.hadron.selftest.plugin;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.economy.Economy;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.server.CommandExecuteEvent;
import dev.iredsc.hadron.api.event.server.ServiceRegisterEvent;
import dev.iredsc.hadron.api.event.server.ServiceUnregisterEvent;
import dev.iredsc.hadron.api.plugin.PluginDescription;
import dev.iredsc.hadron.api.plugin.PluginException;
import dev.iredsc.hadron.api.plugin.PluginManager;
import dev.iredsc.hadron.api.plugin.Server;
import dev.iredsc.hadron.api.plugin.ServicePriority;
import dev.iredsc.hadron.api.plugin.ServicesManager;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** The server as plugins reach it: Hadron's static shortcuts, the Server, plugins' listeners and commands, services. */
public final class ServerApiTests {
    @HadronTest
    @Covers(value = Hadron.class, method = "getServer")
    @Covers(value = Hadron.class, method = "getPluginManager")
    @Covers(value = Hadron.class, method = "getScheduler")
    @Covers(value = Hadron.class, method = "getServicesManager")
    @Covers(value = Hadron.class, method = "getEconomy")
    @Covers(value = Hadron.class, method = "getOnlinePlayers")
    @Covers(value = Hadron.class, method = "getPlayer")
    @Covers(value = Hadron.class, method = "isPrimaryThread")
    @Covers(value = Server.class, method = "getPluginManager")
    @Covers(value = Server.class, method = "getScheduler")
    @Covers(value = Server.class, method = "getServicesManager")
    @Covers(value = Server.class, method = "getMinecraftServer")
    @Covers(value = Server.class, method = "getOnlinePlayers")
    @Covers(value = Server.class, method = "getPlayer")
    @Covers(value = Server.class, method = "isStopping")
    @Covers(value = Server.class, method = "getConfigFolder")
    void hadronsShortcutsAreThePluginsServer(TestContext t) {
        TestPlayer alice = t.player("alice");
        Server server = t.plugin().getServer();
        t.assertTrue(Hadron.getServer() == server, "Hadron.getServer() should be the plugin's server");
        t.assertTrue(Hadron.getPluginManager() == server.getPluginManager(), "the plugin manager");
        t.assertTrue(Hadron.getScheduler() == server.getScheduler(), "the scheduler");
        t.assertTrue(Hadron.getServicesManager() == server.getServicesManager(), "the services manager");
        t.assertTrue(Hadron.getEconomy() == Economy.get(), "the economy");
        t.assertTrue(server.getMinecraftServer() == t.server(), "the Minecraft server");
        t.assertTrue(Hadron.getOnlinePlayers().contains(alice.player()), "Hadron's online players should have alice");
        t.assertTrue(server.getOnlinePlayers().contains(alice.player()), "the server's online players should have alice");
        t.assertTrue(server.getPlayer("ALICE") == alice.player(), "getPlayer by name ignores case");
        t.assertTrue(server.getPlayer(alice.uuid()) == alice.player(), "getPlayer by UUID");
        t.assertTrue(Hadron.getPlayer("alice") == alice.player() && Hadron.getPlayer(alice.uuid()) == alice.player(),
                "Hadron.getPlayer by name and by UUID");
        t.assertEquals(null, server.getPlayer("nobody"), "getPlayer for someone offline");
        t.assertTrue(Hadron.isPrimaryThread(), "a test runs on the server thread");
        t.assertFalse(server.isStopping(), "the server isn't stopping");
        t.assertEquals("config", server.getConfigFolder().getName(), "the shared config folder");
    }

    @HadronTest
    @Covers(value = Server.class, method = "broadcastMessage")
    @Covers(value = Hadron.class, method = "broadcastMessage")
    @Covers(value = Server.class, method = "dispatchCommand")
    @Covers(value = Server.class, method = "getConsoleSender")
    void broadcastsReachEveryoneAndTheConsoleRunsCommands(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Server server = t.plugin().getServer();
        server.broadcastMessage("&aserver news");
        Hadron.broadcastMessage("static news");
        t.tick();
        t.assertChat(alice, "server news");
        t.assertChat(bob, "static news");
        t.assertNoChat(alice, "&a");
        CommandSourceStack console = server.getConsoleSender();
        t.assertTrue(console.hasPermission(4), "the console sender has the highest permission level");
        t.assertTrue(server.dispatchCommand(console, "/tellraw alice \"from the console\""), "dispatching tellraw");
        t.assertTrue(server.dispatchCommand(console, "tellraw bob \"no slash\""), "dispatching without the slash");
        t.assertFalse(server.dispatchCommand(console, "nosuchcommand"), "an unknown command reports false");
        t.tick();
        t.assertChat(alice, "from the console");
        t.assertChat(bob, "no slash");
    }

    @HadronTest
    @Covers(value = PluginManager.class, method = "registerEvents")
    @Covers(value = PluginManager.class, method = "unregisterEvents")
    @Covers(value = PluginManager.class, method = "callEvent")
    @Covers(CommandExecuteEvent.class)
    void aRegisteredListenerHearsUntilUnregistered(TestContext t) {
        PluginManager plugins = t.plugin().getServer().getPluginManager();
        List<String> heard = new ArrayList<>();
        Listener listener = new Listener() {
            @EventHandler
            void on(CommandExecuteEvent e) {
                heard.add(e.getCommand());
            }
        };
        plugins.registerEvents(listener, t.plugin());
        try {
            CommandExecuteEvent fired = new CommandExecuteEvent(new dev.iredsc.hadron.api.actor.Actor.Server("test"),
                    t.server().createCommandSourceStack(), "say called");
            t.assertFalse(plugins.callEvent(fired), "callEvent reports an uncancelled event as not cancelled");
            t.assertEquals(List.of("say called"), heard, "what the registered listener heard");
        } finally {
            plugins.unregisterEvents(listener);
        }
        plugins.callEvent(new CommandExecuteEvent(new dev.iredsc.hadron.api.actor.Actor.Server("test"),
                t.server().createCommandSourceStack(), "say again"));
        t.assertEquals(List.of("say called"), heard, "what the listener heard after unregistering");
    }

    @HadronTest
    @Covers(CommandExecuteEvent.class)
    void aPlayersCommandIsHeardAndCanBeRewrittenOrRefused(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        List<CommandExecuteEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(CommandExecuteEvent e) {
                if (!e.getSource().getTextName().equals("alice")) return;
                heard.add(e);
                if (e.getCommand().contains("refused")) e.setCancelled(true);
                if (e.getCommand().contains("original")) e.setCommand("say rewritten");
            }
        });
        alice.setOp(true); // for /say
        alice.command("say refused");
        alice.command("say original");
        t.tick();
        t.assertEquals(2, heard.size(), "the CommandExecuteEvents for alice's two commands");
        t.assertTrue(heard.get(0).getActor() instanceof dev.iredsc.hadron.api.actor.Actor.Player p
                && p.player() == alice.player(), "a player's command is theirs, but was " + heard.get(0).getActor());
        t.assertNoChat(bob, "refused");
        t.assertChat(bob, "rewritten");
        t.assertNoChat(bob, "original");
    }

    @HadronTest
    @Covers(value = PluginManager.class, method = "registerCommand")
    void aRegisteredBrigadierCommandRuns(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<String> ran = new ArrayList<>();
        LiteralArgumentBuilder<CommandSourceStack> ping = Commands.literal("selftestping").executes(c -> {
            ran.add(c.getSource().getTextName());
            c.getSource().sendSystemMessage(Component.literal("pong"));
            return 1;
        });
        t.plugin().getServer().getPluginManager().registerCommand(ping, t.plugin());
        t.tick();
        alice.command("selftestping");
        t.assertEquals(List.of("alice"), ran, "who ran the registered command");
        t.assertChat(alice, "pong");
    }

    /** A service nobody else provides, so the test controls every provider of it. */
    public interface Greeter {
        String greet();
    }

    @HadronTest
    @Covers(value = ServicesManager.class, method = "register")
    @Covers(value = ServicesManager.class, method = "load")
    @Covers(value = ServicesManager.class, method = "loadAll")
    @Covers(value = ServicesManager.class, method = "isProvidedFor")
    @Covers(value = ServicesManager.class, method = "unregister")
    @Covers(value = ServicesManager.class, method = "unregisterAll")
    @Covers(ServiceRegisterEvent.class)
    @Covers(ServiceUnregisterEvent.class)
    void servicesAreHandedOutByPriorityAndAnnounced(TestContext t) {
        ServicesManager services = t.plugin().getServer().getServicesManager();
        List<String> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ServiceRegisterEvent e) {
                if (e.getService() == Greeter.class && e.getPlugin() == t.plugin()) {
                    events.add("register " + ((Greeter) e.getProvider()).greet());
                }
            }

            @EventHandler
            void on(ServiceUnregisterEvent e) {
                if (e.getService() == Greeter.class) events.add("unregister " + ((Greeter) e.getProvider()).greet());
            }
        });
        Greeter low = () -> "low";
        Greeter high = () -> "high";
        try {
            t.assertFalse(services.isProvidedFor(Greeter.class), "nobody provides Greeter yet");
            t.assertEquals(null, services.load(Greeter.class), "load with no provider");
            services.register(Greeter.class, low, t.plugin(), ServicePriority.Low);
            services.register(Greeter.class, high, t.plugin(), ServicePriority.High);
            t.assertTrue(services.isProvidedFor(Greeter.class), "Greeter is provided once registered");
            t.assertEquals("high", services.load(Greeter.class).greet(), "load picks the highest priority");
            t.assertEquals(List.of("high", "low"), services.loadAll(Greeter.class).stream().map(Greeter::greet).toList(),
                    "loadAll, highest first");
            services.unregister(high);
            t.assertEquals("low", services.load(Greeter.class).greet(), "load once the high one is gone");
            services.register(Greeter.class, high, t.plugin(), ServicePriority.High);
            services.unregisterAll(t.plugin());
            t.assertFalse(services.isProvidedFor(Greeter.class), "unregisterAll removes the plugin's providers");
            t.assertEquals(List.of("register low", "register high", "unregister high", "register high"),
                    events.subList(0, 4), "the service events, in order");
            t.assertTrue(events.subList(4, events.size()).containsAll(List.of("unregister low", "unregister high")),
                    "unregisterAll should announce both providers going, but heard " + events);
        } finally {
            services.unregister(low);
            services.unregister(high);
        }
    }

    @HadronTest
    @Covers(value = PluginDescription.class, method = "parse")
    void aPluginYmlWithoutTheMinecraftVersionIsRefusedWithTheFix(TestContext t) {
        PluginDescription ok = PluginDescription.parse(Map.of("name", "Tags", "version", "0.1.0", "main", "a.Tags",
                "minecraft", "1.21.1"));
        t.assertEquals("Tags v0.1.0", ok.getFullName(), "a parsed description");
        try {
            PluginDescription.parse(Map.of("name", "Tags", "version", "0.1.0", "main", "a.Tags"));
            t.fail("a plugin.yml without minecraft should be refused");
        } catch (PluginException e) {
            t.assertTrue(e.getMessage().contains("minecraft") && e.getMessage().contains("1.21.1"),
                    "the refusal should say what to add, but said: " + e.getMessage());
        }
    }
}
