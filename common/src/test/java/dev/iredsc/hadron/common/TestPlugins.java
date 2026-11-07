package dev.iredsc.hadron.common;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginDescription;
import dev.iredsc.hadron.api.plugin.Server;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Real {@link JavaPlugin}s for fast tests, each loaded by its own {@link PluginLoader} from a one-class jar, as Hadron
 * loads a plugin from {@code plugins/}. So {@link PluginHost#isAlive} and everything keyed on a plugin's identity
 * behave as on a server, with no server.
 */
public final class TestPlugins {
    private static final String STUB = StubPlugin.class.getName();
    private static final String EVENT = StubEvent.class.getName();
    private static final Path JAR = writeJar();

    private TestPlugins() {}

    /** A loaded (not yet enabled) plugin named {@code name}, belonging to {@code server} (null for none). */
    public static JavaPlugin create(String name, Server server) {
        PluginDescription description = PluginDescription.parse(Map.of("name", name, "version", "1",
                "main", STUB, "minecraft", "1.21.1"));
        try {
            PluginLoader loader = new PluginLoader(description, JAR.toFile(), JAR.toFile(),
                    new File(JAR.getParent().toFile(), name), server, List.of(), new HidingStub());
            return (JavaPlugin) loader.loadClass(STUB).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | IOException e) {
            throw new AssertionError("Couldn't load the stub plugin " + name, e);
        }
    }

    /** The plugin's own copy of {@link StubEvent}: a class only that plugin version's loader defines. */
    public static Class<?> event(JavaPlugin plugin) {
        try {
            return loader(plugin).loadClass(EVENT);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("The stub jar has no " + EVENT, e);
        }
    }

    public static void enable(JavaPlugin plugin) {
        loader(plugin).enable();
    }

    /** As Hadron does when the plugin starts disabling: from here on it isn't alive. */
    public static void beginUnload(JavaPlugin plugin) {
        loader(plugin).beginUnload();
    }

    private static PluginLoader loader(JavaPlugin plugin) {
        return (PluginLoader) plugin.getClass().getClassLoader();
    }

    /** The test classpath without the stub, so each PluginLoader defines its own copy and becomes its context. */
    private static final class HidingStub extends ClassLoader {
        HidingStub() {
            super(TestPlugins.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(STUB) || name.equals(EVENT)) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }
    }

    private static Path writeJar() {
        try {
            Path dir = Files.createTempDirectory("hadron-test-plugins");
            dir.toFile().deleteOnExit();
            Path jar = dir.resolve("stub.jar");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
                for (String name : List.of(STUB, EVENT)) {
                    String entry = name.replace('.', '/') + ".class";
                    try (InputStream in = TestPlugins.class.getClassLoader().getResourceAsStream(entry)) {
                        out.putNextEntry(new JarEntry(entry));
                        in.transferTo(out);
                        out.closeEntry();
                    }
                }
            }
            jar.toFile().deleteOnExit();
            return jar;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
