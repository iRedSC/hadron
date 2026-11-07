package fixture.api;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.FiredBy;
import static dev.iredsc.hadron.api.event.FiredBy.Core.*;

/** Built only by RefEvent::new in core: counts as fired. */
@FiredBy(core = FULL)
public class RefEvent extends Event {}
