package fixture.api;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.FiredBy;
import static dev.iredsc.hadron.api.event.FiredBy.Core.*;

public class Outer {
    /** Nested, with a catalog row naming it Outer.InnerEvent: passes. */
    @FiredBy(core = FULL)
    public static class InnerEvent extends Event {}

    /** Nested, without @FiredBy: fails. */
    public static class BareEvent extends Event {}
}
