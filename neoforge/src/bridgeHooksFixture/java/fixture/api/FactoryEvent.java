package fixture.api;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.FiredBy;
import static dev.iredsc.hadron.api.event.FiredBy.Core.*;

/** Builds itself in api: fails, though core builds it too. */
@FiredBy(core = FULL)
public class FactoryEvent extends Event {
    public static FactoryEvent of() { return new FactoryEvent(); }
}
