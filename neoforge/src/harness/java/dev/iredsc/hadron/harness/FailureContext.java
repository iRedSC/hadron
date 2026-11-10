package dev.iredsc.hadron.harness;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What a failed or hung test's report says led up to it: the last events its listeners heard, and each of its players
 * as it ended. Captured before the test's world is cleaned up, and only for a test that didn't pass.
 *
 * @param events  the last events, oldest first, at most {@link RecentEvents#KEPT}
 * @param omitted how many earlier events were heard but not kept
 * @param players every player the test joined, in the order they joined
 */
record FailureContext(List<Heard> events, int omitted, List<PlayerState> players) {
    FailureContext {
        events = List.copyOf(events);
        players = List.copyOf(players);
        if (omitted < 0 || (omitted > 0 && events.isEmpty())) {
            throw new IllegalArgumentException(omitted + " events omitted with " + events.size() + " kept: only the "
                    + "earliest are ever omitted, so some are always kept");
        }
    }

    /**
     * One event, as {@code /hadron trace} shows it.
     *
     * @param tick      the test's own tick count when it fired, as the report counts a test's ticks
     * @param event     the event's class name, {@code BlockBreakEvent}
     * @param cancelled whether it ended cancelled; null for an event that can't be
     * @param line      the trace line: the event, its actor, where and to what, and how it ended
     */
    record Heard(int tick, String event, @Nullable Boolean cancelled, String line) {}

    /** A test player: {@link Online} unless they had left the server. */
    record PlayerState(String name, @Nullable Online online) {
        String render() {
            Online o = online;
            if (o == null) return name + ": not online";
            return name + ": " + o.gameMode() + ", in " + o.world() + (o.pos() != null ? " at " + o.pos() : "")
                    + " (" + coordinate(o.x()) + ", " + coordinate(o.y()) + ", " + coordinate(o.z()) + "), holding "
                    + o.mainHand() + ", off hand " + o.offHand();
        }
    }

    /**
     * Where an online player was and what they held.
     *
     * @param world    the world's id, {@code hadrontest:test3}
     * @param pos      the block they stood in as the test names it, {@code pos(0, 1, 0)}; null outside the test's world
     * @param gameMode {@code survival}, {@code creative}, {@code adventure} or {@code spectator}
     * @param mainHand what's in their main hand: {@code 1 diamond_pickaxe}, or {@code nothing}
     */
    record Online(String world, @Nullable String pos, double x, double y, double z, String gameMode, String mainHand,
                  String offHand) {}

    /** The lines under a failure in report.txt, each indented by {@code indent}. */
    void render(StringBuilder out, String indent, boolean hung) {
        out.append(indent);
        if (events.isEmpty()) {
            out.append("Events its listeners heard: none\n");
        } else if (omitted > 0) {
            out.append("Events its listeners heard, the last ").append(events.size()).append(" (").append(omitted)
                    .append(" earlier events omitted):\n");
        } else {
            out.append("Events its listeners heard:\n");
        }
        for (Heard e : events) out.append(indent).append("  tick ").append(e.tick()).append("  ").append(e.line()).append('\n');
        out.append(indent).append(hung ? "Players when it hung:" : "Players when it failed:");
        if (players.isEmpty()) {
            out.append(" none joined\n");
            return;
        }
        out.append('\n');
        for (PlayerState p : players) out.append(indent).append("  ").append(p.render()).append('\n');
    }

    private static String coordinate(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }
}
