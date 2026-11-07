package fixture.api;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.FiredBy;
import static dev.iredsc.hadron.api.event.FiredBy.Core.*;

/** Marked FULL, but nothing allocates it: fails. */
@FiredBy(core = FULL)
public class UnfiredEvent extends Event {}
