package dev.iredsc.hadron.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@code /hadron status}'s lines: plain text a script can split, whatever a jar name or a reason holds. */
class PluginStatusTest {
    private static final String SHA = "ab".repeat(32);

    @Test
    void aPluginLineHasEveryFieldAndNoColor() {
        String line = new PluginStatus("Hello", PluginStatus.State.ENABLED, "Hello.jar", 1731200000000L, SHA, null)
                .format();
        assertEquals("plugin=Hello\tstate=enabled\tjar=Hello.jar\tmodified=1731200000000\tsha256=" + SHA, line);
    }

    @Test
    void aFailedPluginSaysWhyAsTheLastField() {
        String line = new PluginStatus("Hello", PluginStatus.State.FAILED, "Hello.jar", 5, SHA,
                "onEnable threw java.lang.IllegalStateException: boom at Hello.onEnable(Hello.java:9). Fix that there")
                .format();
        assertTrue(line.endsWith("\twhy=onEnable threw java.lang.IllegalStateException: boom at Hello.onEnable"
                + "(Hello.java:9). Fix that there"), line);
        assertEquals(6, line.split("\t").length);
    }

    @Test
    void aReasonSpanningLinesStaysOneLine() {
        String line = new PluginStatus(null, PluginStatus.State.REFUSED, "My Plugin.jar", 5, SHA,
                "first\nsecond\t third\r\nfourth").format();
        assertFalse(line.contains("\n") || line.contains("\r"), "one line per jar, or a script splits it wrong");
        assertTrue(line.endsWith("\twhy=first second third fourth"), line);
        assertTrue(line.startsWith("refused\tstate=refused\tjar=My Plugin.jar\t"), line);
    }

    @Test
    void everyStateHasItsOwnWord() {
        for (PluginStatus.State state : PluginStatus.State.values()) {
            boolean refused = state == PluginStatus.State.REFUSED;
            boolean says = state == PluginStatus.State.FAILED || refused;
            String line = new PluginStatus(refused ? null : "P", state, "p.jar", 1, SHA, says ? "why" : null).format();
            assertTrue(line.contains("\tstate=" + state.name().toLowerCase() + "\t"), line);
        }
    }

    @Test
    void aStatusCantContradictItself() {
        assertThrows(IllegalArgumentException.class,
                () -> new PluginStatus("P", PluginStatus.State.FAILED, "p.jar", 1, SHA, null), "failed without why");
        assertThrows(IllegalArgumentException.class,
                () -> new PluginStatus("P", PluginStatus.State.ENABLED, "p.jar", 1, SHA, "why"), "enabled with a why");
        assertThrows(IllegalArgumentException.class,
                () -> new PluginStatus("P", PluginStatus.State.REFUSED, "p.jar", 1, SHA, "why"), "refused with a name");
        assertThrows(IllegalArgumentException.class,
                () -> new PluginStatus(null, PluginStatus.State.ENABLED, "p.jar", 1, SHA, null), "a nameless plugin");
    }
}
