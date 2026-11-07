package dev.iredsc.hadron.common;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Why plugins left over by dependency ordering can't start, from their names and depend: lists alone, so the reasons
 * are tested without a server.
 */
final class DependencyCycles {
    private DependencyCycles() {}

    /**
     * Why {@code name} can't start. {@code left} maps each plugin still waiting, in load order, to its depend: list; a
     * plugin outside it has already started or isn't there.
     */
    static String reason(String name, Map<String, List<String>> left) {
        Set<String> cycle = cycleThrough(name, left);
        if (cycle.equals(Set.of(name))) {
            return "depends on itself: remove " + name + " from its own depend: list in plugin.yml";
        }
        if (!cycle.isEmpty()) {
            return String.join(", ", cycle) + " depend on each other, so none of them can start first. In one of "
                    + "their plugin.yml files, move the other from depend: to softdepend:";
        }
        // Only the cycles its own depend: reaches: another plugin's unrelated cycle isn't this one's fix.
        Set<String> reach = reachable(name, left);
        List<String> blocking = left.keySet().stream()
                .filter(o -> !o.equals(name) && reach.contains(o) && cycleThrough(o, left).contains(o)).toList();
        return "needs a plugin that can't start: its depend: leads to " + String.join(", ", blocking)
                + ", which depend on each other. Break that cycle and it starts too";
    }

    /** The plugins in {@code left} on a depend: cycle through {@code name}, or empty when it is on none. */
    private static Set<String> cycleThrough(String name, Map<String, List<String>> left) {
        Set<String> reach = reachable(name, left);
        if (!reach.contains(name)) return Set.of();
        Set<String> cycle = new LinkedHashSet<>();
        for (String o : left.keySet()) if (reach.contains(o) && reachable(o, left).contains(name)) cycle.add(o);
        return cycle;
    }

    /** Every plugin in {@code left} that {@code from}'s depend: reaches, through one or more steps. */
    private static Set<String> reachable(String from, Map<String, List<String>> left) {
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(from));
        while (!todo.isEmpty()) {
            for (String need : left.getOrDefault(todo.pop(), List.of())) {
                if (left.containsKey(need) && seen.add(need)) todo.push(need);
            }
        }
        return seen;
    }
}
