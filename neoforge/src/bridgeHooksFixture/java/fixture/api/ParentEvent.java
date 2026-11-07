package fixture.api;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.FiredBy;
import static dev.iredsc.hadron.api.event.FiredBy.Core.*;

/** Fired only through core's CoreChildEvent: counts as fired. */
@FiredBy(core = FULL)
public class ParentEvent extends Event {}
