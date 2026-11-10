package dev.iredsc.hadron.common;

import dev.iredsc.hadron.api.event.Event;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/**
 * What {@link PluginHost#recordEvents} starts: hands its sink every event some plugin handled, once they all had,
 * with the line {@code /hadron trace} would show for it and the clock's reading when it fired. It hears only handled
 * events ({@link EventBus.Observer#onlyHandled}), so no chokepoint builds an event for it alone, and it has no filters
 * or rate limit: the sink keeps what it wants.
 */
final class TraceRecorder implements EventBus.Observer, PluginHost.EventRecording {
    private final Trace trace;
    private final EventBus bus;
    private final IntSupplier clock;
    private final PluginHost.EventSink sink;
    private volatile boolean closed;

    TraceRecorder(Trace trace, EventBus bus, IntSupplier clock, PluginHost.EventSink sink) {
        this.trace = trace;
        this.bus = bus;
        this.clock = clock;
        this.sink = sink;
    }

    /** What {@link #before} keeps: the clock as the event fired, and what the trace line needs. */
    private record Fired(int tick, Object seen) {}

    @Override
    public boolean hears(Class<?> type) {
        return Event.class.isAssignableFrom(type);
    }

    @Override
    public boolean onlyHandled() {
        return true;
    }

    @Override
    public @Nullable Object before(Event event) {
        return closed ? null : new Fired(clock.getAsInt(), Trace.before(event));
    }

    @Override
    public void after(Event event, Object state, @Nullable String canceller) {
        if (closed) return;
        Fired fired = (Fired) state;
        sink.heard(event, fired.tick(), trace.plain(event, fired.seen(), canceller));
    }

    @Override
    public void close() {
        closed = true;
        bus.unobserve(this);
    }
}
