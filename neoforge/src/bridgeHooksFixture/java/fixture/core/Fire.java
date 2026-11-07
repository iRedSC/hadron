package fixture.core;

import dev.iredsc.hadron.api.event.Event;
import fixture.api.FactoryEvent;
import fixture.api.Outer;
import fixture.api.RefEvent;
import java.util.function.Supplier;

public class Fire {
    /** Each `new` follows a switch, so misreading the switches' padded operands loses them. */
    static Object fire(int dense, int sparse) {
        switch (dense) { case 0: case 1: case 2: case 3: dense++; }
        switch (sparse) { case 1: case 1000: case 1000000: sparse++; }
        Supplier<RefEvent> ref = RefEvent::new;
        Event[] events = { new Outer.InnerEvent(), new FactoryEvent(), new CoreChildEvent() };
        return dense + sparse + events.length + ref.hashCode();
    }
}
