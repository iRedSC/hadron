package dev.iredsc.hadron.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DependencyCyclesTest {
    private static Map<String, List<String>> left(String... pairs) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1].isEmpty() ? List.of() : List.of(pairs[i + 1]));
        return out;
    }

    @Test
    void aPluginThatDependsOnItselfIsToldToRemoveIt() {
        assertTrue(DependencyCycles.reason("A", left("A", "A")).startsWith("depends on itself: remove A"));
    }

    @Test
    void twoPluginsInACycleAreBothNamed() {
        String reason = DependencyCycles.reason("A", left("A", "B", "B", "A"));
        assertTrue(reason.contains("A") && reason.contains("B") && reason.contains("depend on each other"), reason);
    }

    @Test
    void aPluginWaitingOnACycleIsToldOnlyAboutTheCycleItReaches() {
        String reason = DependencyCycles.reason("Waiting",
                left("Waiting", "A", "A", "B", "B", "A", "C", "D", "D", "C"));
        assertTrue(reason.contains("leads to A, B"), reason);
        assertFalse(reason.contains("C") || reason.contains("D"), "names a cycle it doesn't reach: " + reason);
    }

    @Test
    void namesMatchWhateverTheirCaseAsTheHostResolvesThem() {
        String cycle = DependencyCycles.reason("Alpha", left("Alpha", "beta", "Beta", "alpha"));
        assertTrue(cycle.contains("Alpha") && cycle.contains("Beta") && cycle.contains("depend on each other"), cycle);
        assertTrue(DependencyCycles.reason("Alpha", left("Alpha", "ALPHA")).startsWith("depends on itself: remove Alpha"));
    }
}
