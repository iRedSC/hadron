package fixture.api;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.FiredBy;
import static dev.iredsc.hadron.api.event.FiredBy.Core.*;

/** Marked FULL and fired by core, but its subclass NarrowEvent is NONE: fails. */
@FiredBy(core = FULL)
public class WideEvent extends Event {}
