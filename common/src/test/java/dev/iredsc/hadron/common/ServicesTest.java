package dev.iredsc.hadron.common;

import dev.iredsc.hadron.api.plugin.ServicesManager;
import dev.iredsc.hadron.test.Covers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.economy.Economy;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.server.ServiceRegisterEvent;
import dev.iredsc.hadron.api.event.server.ServiceUnregisterEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.ServicePriority;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which provider a plugin gets for a service, and what registering and unregistering announce. */
class ServicesTest {
    interface Greeter {
        String greet();
    }

    record Named(String name) implements Greeter {
        @Override
        public String greet() {
            return name;
        }
    }

    interface Other {}

    record OtherImpl() implements Other {}

    @TempDir
    Path dir;

    private PluginHost host;
    private Services services;
    private JavaPlugin alpha;
    private JavaPlugin beta;
    private final List<Object> announced = new ArrayList<>();

    /** A host with no Minecraft server: Services only needs its plugin checks and its event bus. */
    static PluginHost hostWithoutServer(File serverDir) {
        return new PluginHost(null, serverDir, new Platform() {
            @Override
            public String loader() {
                return "test";
            }

            @Override
            public boolean isModLoaded(String modId) {
                return false;
            }

            @Override
            public File configFolder() {
                return new File(serverDir, "config");
            }
        });
    }

    static EventBus events(PluginHost host) throws ReflectiveOperationException {
        Field field = PluginHost.class.getDeclaredField("events");
        field.setAccessible(true);
        return (EventBus) field.get(host);
    }

    @BeforeEach
    void setUp() throws Exception {
        host = hostWithoutServer(dir.toFile());
        services = host.services();
        alpha = TestPlugins.create("Alpha", host.server());
        beta = TestPlugins.create("Beta", host.server());
        events(host).subscribe(new Listener() {
            @EventHandler
            void registered(ServiceRegisterEvent e) {
                announced.add("register " + e.getService().getSimpleName() + " " + e.getProvider() + " by "
                        + e.getPlugin().getName());
            }

            @EventHandler
            void unregistered(ServiceUnregisterEvent e) {
                announced.add("unregister " + e.getService().getSimpleName() + " " + e.getProvider() + " by "
                        + e.getPlugin().getName());
            }
        }, "Test", org.slf4j.LoggerFactory.getLogger("test"));
    }

    @Covers(value = ServicesManager.class, method = "load")
    @Covers(value = ServicesManager.class, method = "isProvidedFor")
    @Test
    void nothingIsProvidedUntilRegistered() {
        assertNull(services.load(Greeter.class));
        assertEquals(List.of(), services.loadAll(Greeter.class));
        assertFalse(services.isProvidedFor(Greeter.class));
    }

    @Covers(value = ServicesManager.class, method = "register")
    @Covers(value = ServicesManager.class, method = "load")
    @Test
    void theHighestPriorityProviderWins() {
        services.register(Greeter.class, new Named("low"), alpha, ServicePriority.Low);
        services.register(Greeter.class, new Named("highest"), beta, ServicePriority.Highest);
        services.register(Greeter.class, new Named("normal"), alpha, ServicePriority.Normal);
        assertEquals("highest", services.load(Greeter.class).greet());
        assertEquals(List.of(new Named("highest"), new Named("normal"), new Named("low")),
                services.loadAll(Greeter.class));
        assertTrue(services.isProvidedFor(Greeter.class));
    }

    @Covers(value = ServicesManager.class, method = "register")
    @Test
    void amongEqualPrioritiesTheFirstRegisteredWins() {
        services.register(Greeter.class, new Named("first"), alpha, ServicePriority.Normal);
        services.register(Greeter.class, new Named("second"), beta, ServicePriority.Normal);
        assertEquals("first", services.load(Greeter.class).greet());
    }

    @Covers(value = ServicesManager.class, method = "register")
    @Test
    void registeringAnnouncesTheServiceProviderAndPlugin() {
        services.register(Greeter.class, new Named("a"), alpha, ServicePriority.Normal);
        assertEquals(List.of("register Greeter Named[name=a] by Alpha"), announced);
    }

    @Covers(value = ServicesManager.class, method = "register")
    @Test
    void aProviderThatDoesntImplementTheServiceIsRefused() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Object> greeter = (Class) Greeter.class;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> services.register(greeter, new OtherImpl(), alpha, ServicePriority.Normal));
        assertTrue(e.getMessage().contains("doesn't implement " + Greeter.class.getName()), e.getMessage());
        assertFalse(services.isProvidedFor(Greeter.class));
        assertEquals(List.of(), announced);
    }

    @Covers(value = ServicesManager.class, method = "register")
    @Test
    void aPluginThatIsUnloadingCantRegister() {
        TestPlugins.beginUnload(alpha);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> services.register(Greeter.class, new Named("a"), alpha, ServicePriority.Normal));
        assertEquals("Alpha can't register services while unloading", e.getMessage());
        assertFalse(services.isProvidedFor(Greeter.class));
    }

    @Covers(value = ServicesManager.class, method = "register")
    @Test
    void aPluginOfAnotherServerCantRegister() {
        JavaPlugin stranger = TestPlugins.create("Stranger", null);
        assertThrows(IllegalStateException.class,
                () -> services.register(Greeter.class, new Named("x"), stranger, ServicePriority.Normal));
    }

    @Covers(value = ServicesManager.class, method = "unregister")
    @Test
    void unregisteringAProviderRemovesItFromEveryService() {
        class Both implements Greeter, Other {
            @Override
            public String greet() {
                return "both";
            }

            @Override
            public String toString() {
                return "Both";
            }
        }
        Both both = new Both();
        services.register(Greeter.class, both, alpha, ServicePriority.Normal);
        services.register(Other.class, both, alpha, ServicePriority.Normal);
        services.register(Greeter.class, new Named("kept"), beta, ServicePriority.Low);
        announced.clear();
        services.unregister(both);
        assertEquals("kept", services.load(Greeter.class).greet());
        assertFalse(services.isProvidedFor(Other.class), "an emptied service is dropped");
        assertEquals(2, announced.size(), announced.toString());
        assertTrue(announced.contains("unregister Greeter Both by Alpha"), announced.toString());
        assertTrue(announced.contains("unregister Other Both by Alpha"), announced.toString());
    }

    @Covers(value = ServicesManager.class, method = "unregisterAll")
    @Test
    void unregisterAllRemovesOnlyThatPluginsProviders() {
        services.register(Greeter.class, new Named("alpha"), alpha, ServicePriority.Highest);
        services.register(Greeter.class, new Named("beta"), beta, ServicePriority.Lowest);
        services.register(Other.class, new OtherImpl(), alpha, ServicePriority.Normal);
        announced.clear();
        services.unregisterAll(alpha);
        assertEquals("beta", services.load(Greeter.class).greet());
        assertFalse(services.isProvidedFor(Other.class));
        assertEquals(2, announced.size(), announced.toString());
    }

    @Covers(value = ServicesManager.class, method = "unregister")
    @Test
    void unregisteringSomethingNeverRegisteredDoesNothing() {
        services.register(Greeter.class, new Named("a"), alpha, ServicePriority.Normal);
        announced.clear();
        services.unregister(new Named("a")); // equal but not the same object
        assertTrue(services.isProvidedFor(Greeter.class));
        assertEquals(List.of(), announced);
    }

    @Covers(value = ServicesManager.class, method = "register")
    @Test
    void aListenerMayLookServicesUpWhileHearingARegistration() throws Exception {
        List<Object> seen = new ArrayList<>();
        events(host).subscribe(new Listener() {
            @EventHandler
            void on(ServiceRegisterEvent e) {
                seen.add(services.load(Greeter.class));
            }
        }, "Test", org.slf4j.LoggerFactory.getLogger("test"));
        services.register(Greeter.class, new Named("a"), alpha, ServicePriority.Normal);
        assertEquals(List.of(new Named("a")), seen, "the provider is in place before anyone hears of it");
    }

    @Covers(value = ServicesManager.class, method = "load")
    @Test
    void theEconomyServiceIsAlwaysHadronsOwn() {
        Economy running = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(),
                new Class<?>[] {Economy.class}, (p, m, a) -> null);
        Economy before = Economy.Lifecycle.running();
        Economy.Lifecycle.install(running);
        try {
            Economy imposter = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(),
                    new Class<?>[] {Economy.class}, (p, m, a) -> null);
            services.register(Economy.class, imposter, alpha, ServicePriority.Highest);
            assertSame(running, services.load(Economy.class),
                    "plugins looking the economy up as a service, Vault-style, get Hadron's");
        } finally {
            Economy.Lifecycle.install(before);
        }
    }

    @Covers(value = ServicesManager.class, method = "load")
    @Test
    void theEconomyServiceIsNullWhileNoServerRuns() {
        Economy before = Economy.Lifecycle.running();
        Economy.Lifecycle.install(null);
        try {
            assertNull(services.load(Economy.class));
        } finally {
            Economy.Lifecycle.install(before);
        }
    }

    @Covers(value = ServicesManager.class, method = "register")
    @Covers(value = ServicesManager.class, method = "unregister")
    @Test
    void everyoneHearsChangesInTheOrderTheyHappened() throws Exception {
        Named provider = new Named("a");
        // Registered after the setUp listener, so it hears each event first and undoes the registration at once.
        events(host).subscribe(new Listener() {
            @EventHandler(priority = dev.iredsc.hadron.api.event.EventPriority.LOWEST)
            void on(ServiceRegisterEvent e) {
                services.unregister(e.getProvider());
            }
        }, "Test", org.slf4j.LoggerFactory.getLogger("test"));
        services.register(Greeter.class, provider, alpha, ServicePriority.Normal);
        assertEquals(List.of("register Greeter Named[name=a] by Alpha", "unregister Greeter Named[name=a] by Alpha"),
                announced, "a listener that unregisters while hearing the registration mustn't make others hear the "
                        + "unregistration first");
        assertFalse(services.isProvidedFor(Greeter.class));
    }
}
