package dev.iredsc.hadron.common;

import dev.iredsc.hadron.api.event.Event;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link PluginHost#recordEvents} starts: hands its sink every event some plugin handled, once they all had,
 * with the line {@code /hadron trace} would show for it. It hears only handled events
 * ({@link EventBus.Observer#onlyHandled}), so no chokepoint builds an event for it alone, and it has no filters or
 * rate limit: the sink keeps what it wants.
 */
final class TraceRecorder implements EventBus.Observer, PluginHost.EventRecording {
    private final Trace trace;
    private final EventBus bus;
    private final BiConsumer<Event, String> sink;
    private volatile boolean closed;

    TraceRecorder(Trace trace, EventBus bus, BiConsumer<Event, String> sink) {
        this.trace = trace;
        this.bus = bus;
        this.sink = sink;
    }

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
        return closed ? null : Trace.before(event);
    }

    @Override
    public void after(Event event, Object state, @Nullable String canceller) {
        if (!closed) sink.accept(event, trace.plain(event, state, canceller));
    }

    @Override
    public void close() {
        closed = true;
        bus.unobserve(this);
    }
}
