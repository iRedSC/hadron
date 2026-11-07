package fixture.api.a;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.FiredBy;
import static dev.iredsc.hadron.api.event.FiredBy.Core.*;

/** Shares its simple name with the other package's SameEvent: fails. */
@FiredBy(core = NONE, bridges = "a mod acts", fillIn = "the actor")
public class SameEvent extends Event {}
