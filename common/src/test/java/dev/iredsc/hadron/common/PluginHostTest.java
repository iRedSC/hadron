package dev.iredsc.hadron.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the host remembers across a restart without any plugin loaded. */
class PluginHostTest {
    @TempDir
    Path dir;

    @SuppressWarnings("unchecked")
    private static Set<String> switchedOff(PluginHost host) throws ReflectiveOperationException {
        Field field = PluginHost.class.getDeclaredField("off");
        field.setAccessible(true);
        return (Set<String>) field.get(host);
    }

    @Test
    void pluginsSwitchedOffBeforeARestartStayOff() throws Exception {
        Files.createDirectories(dir.resolve("plugins"));
        Files.writeString(dir.resolve("plugins/disabled.txt"), "Homes\n  \n  Shop  \n");
        Set<String> off = switchedOff(ServicesTest.hostWithoutServer(dir.toFile()));
        assertEquals(Set.of("Homes", "Shop"), off, "blank lines are skipped and names trimmed");
        assertTrue(off.contains("homes"), "names match whatever their case, as plugin names do");
    }

    @Test
    void withoutTheFileNothingIsOff() throws Exception {
        assertEquals(Set.of(), switchedOff(ServicesTest.hostWithoutServer(dir.toFile())));
    }
}
