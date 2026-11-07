package fixture.api;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.FiredBy;
import static dev.iredsc.hadron.api.event.FiredBy.Core.*;

/** Core subclasses it but never allocates either: not fired, so NONE passes. */
@FiredBy(core = NONE, bridges = "a mod acts", fillIn = "the actor")
public class SuperOnlyEvent extends Event {}
