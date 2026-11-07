package fixture.api;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.FiredBy;
import static dev.iredsc.hadron.api.event.FiredBy.Core.*;

/** Marked NONE, but core allocates it: fails. */
@FiredBy(core = NONE, bridges = "a mod acts", fillIn = "the actor")
public class AllocatedNoneEvent extends Event {}
