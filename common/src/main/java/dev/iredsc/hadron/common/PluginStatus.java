package dev.iredsc.hadron.common;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One jar's outcome as plain text, for scripts: {@code /hadron status}. {@code /plugins} shows the same facts as color
 * and hover text, which an RCON reply loses.
 *
 * <p>A line is tab-separated {@code key=value} pairs, so a jar name or a reason with spaces needs no quoting:
 * <pre>
 * plugin=Hello  state=failed  jar=Hello.jar  modified=1731200000000  sha256=ab12...  why=onEnable threw ...
 * refused  state=refused  jar=Bad.jar  modified=1731200000000  sha256=ab12...  why=Bad.jar isn't a usable plugin: ...
 * </pre>
 * {@code modified} and {@code sha256} name the exact file version the host acted on, so a deploy can tell its own jar
 * from one that was already there. {@code why} is present only for {@code failed} and {@code refused}.
 */
record PluginStatus(@Nullable String plugin, State state, String jar, long modified, String sha256,
        @Nullable String why) {

    enum State {
        /** onEnable ran: the plugin is running this jar. */
        ENABLED,
        /** Its jar was read and constructed but not enabled yet. */
        LOADING,
        FAILED,
        /** Switched off with {@code /hadron disable}. */
        OFF,
        /** Not started, or stopped with the server. */
        STOPPED,
        /** The jar couldn't become a plugin at all (unreadable, duplicate name), so it has no plugin name. */
        REFUSED;

        String text() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What an unknown hash reads as: the jar changed on disk after the host acted on it. */
    static final String UNKNOWN = "unknown";

    PluginStatus {
        if (state == State.REFUSED != (plugin == null)) {
            throw new IllegalArgumentException("a refused jar has no plugin name, and every other state has one");
        }
        if ((state == State.FAILED || state == State.REFUSED) != (why != null)) {
            throw new IllegalArgumentException("a failed or refused jar says why, and nothing else does");
        }
    }

    /** One line, no line breaks inside: a reason's own newlines become spaces. */
    String format() {
        StringBuilder out = new StringBuilder();
        out.append(plugin == null ? "refused" : "plugin=" + clean(plugin));
        out.append("\tstate=").append(state.text());
        out.append("\tjar=").append(clean(jar));
        out.append("\tmodified=").append(modified);
        out.append("\tsha256=").append(sha256);
        if (why != null) out.append("\twhy=").append(clean(why));
        return out.toString();
    }

    private static String clean(String text) {
        return text.replaceAll("\\s*[\\t\\r\\n]+\\s*", " ").strip();
    }
}
