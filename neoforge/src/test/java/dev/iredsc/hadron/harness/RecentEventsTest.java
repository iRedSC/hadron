package dev.iredsc.hadron.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A failure's report keeps only the latest events, and says how many came before them. */
final class RecentEventsTest {
    @Test
    void aShortTestKeepsEveryEvent() {
        RecentEvents events = new RecentEvents();
        events.add(0, "A", null, "A · no actor · done");
        events.add(3, "B", true, "B · no actor · cancelled");
        RecentEvents.Tail tail = events.tail();
        assertEquals(0, tail.omitted());
        assertEquals(List.of("A", "B"), tail.events().stream().map(FailureContext.Heard::event).toList());
        assertEquals(true, tail.events().get(1).cancelled());
    }

    @Test
    void aLongTestKeepsTheLatestAndCountsTheRest() {
        RecentEvents events = new RecentEvents();
        int fired = 100;
        for (int i = 0; i < fired; i++) events.add(i, "E" + i, null, "E" + i);
        RecentEvents.Tail tail = events.tail();
        assertEquals(30, tail.events().size(), "a long test's report must stay short");
        assertEquals(70, tail.omitted());
        assertEquals("E70", tail.events().get(0).event(), "the oldest are the ones dropped");
        assertEquals("E99", tail.events().get(29).event(), "the latest, nearest the failure, are kept");
    }

    @Test
    void aContextCantClaimOmittedEventsWithNoneKept() {
        assertThrows(IllegalArgumentException.class, () -> new FailureContext(List.of(), 5, List.of()));
    }
}
