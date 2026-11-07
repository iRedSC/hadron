package fixture.api;

import dev.iredsc.hadron.api.event.FiredBy;
import static dev.iredsc.hadron.api.event.FiredBy.Core.*;

/** Only bridges fire it, which makes its FULL parent WideEvent wrong. */
@FiredBy(core = NONE, bridges = "a mod acts", fillIn = "the actor")
public class NarrowEvent extends WideEvent {}
