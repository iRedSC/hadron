package dev.iredsc.hadron.common;

import dev.iredsc.hadron.checks.Nondeterministic;
import dev.iredsc.hadron.test.Covers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.event.Listener;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;

/** Who hears an event, in what order, and what a handler's failure costs. */
class EventBusTest {
    static class Ping extends Event {
        final List<String> heard = new ArrayList<>();
    }

    static class LoudPing extends Ping {}

    static class Stoppable extends Ping implements Cancellable {
        private boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancel) {
            cancelled = cancel;
        }
    }

    /** Every error the bus logs, by message. */
    static final class Errors {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final Logger logger = (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(),
                new Class<?>[] {Logger.class}, (proxy, method, args) -> {
                    if (method.getName().equals("error") && args != null && args[0] instanceof String s) {
                        messages.add(s);
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }

    private final EventBus bus = new EventBus(TestHost.KEY);
    private final Errors errors = new Errors();

    private List<EventBus.Subscription> subscribe(Listener listener) {
        return bus.subscribe(listener, "Test", errors.logger);
    }

    // ---- the port's dispatch scope ----

    @Test
    void everyHandlerRunsInsideThePortsDispatchScopeAndItEndsEvenWhenOneThrows() {
        List<String> log = new ArrayList<>();
        PluginHost.DispatchScope scope = new PluginHost.DispatchScope() {
            @Override
            public Object enter() {
                log.add("enter");
                return "token";
            }

            @Override
            public void exit(Object entered) {
                log.add("exit " + entered);
            }
        };
        subscribe(new Listener() {
            @EventHandler(priority = EventPriority.LOW)
            void first(Ping p) {
                log.add("first");
                throw new IllegalStateException("a plugin's bug");
            }

            @EventHandler
            void second(Ping p) {
                log.add("second");
            }
        });
        PluginHost.aroundDispatch(scope);
        try {
            bus.post(new Ping());
            bus.post(new Event() {}); // nobody hears it: no scope to open
        } finally {
            PluginHost.aroundDispatch(PluginHost.DispatchScope.NONE);
        }
        assertEquals(List.of("enter", "first", "second", "exit token"), log);
    }

    // ---- order ----

    @Test
    void handlersRunLowestToMonitor() {
        subscribe(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            void monitor(Ping p) {
                p.heard.add("monitor");
            }

            @EventHandler(priority = EventPriority.HIGHEST)
            void highest(Ping p) {
                p.heard.add("highest");
            }

            @EventHandler
            void normal(Ping p) {
                p.heard.add("normal");
            }

            @EventHandler(priority = EventPriority.LOWEST)
            void lowest(Ping p) {
                p.heard.add("lowest");
            }

            @EventHandler(priority = EventPriority.HIGH)
            void high(Ping p) {
                p.heard.add("high");
            }

            @EventHandler(priority = EventPriority.LOW)
            void low(Ping p) {
                p.heard.add("low");
            }
        });
        Ping ping = new Ping();
        bus.post(ping);
        assertEquals(List.of("lowest", "low", "normal", "high", "highest", "monitor"), ping.heard);
    }

    @Test
    void equalPrioritiesRunInTheOrderRegistered() {
        for (String name : List.of("a", "b", "c")) {
            subscribe(new Listener() {
                @EventHandler
                void on(Ping p) {
                    p.heard.add(name);
                }
            });
        }
        Ping ping = new Ping();
        bus.post(ping);
        assertEquals(List.of("a", "b", "c"), ping.heard);
    }

    @Test
    void equalPrioritiesRunInTheOrderRegisteredAcrossEventClasses() {
        subscribe(new Listener() {
            @EventHandler
            void on(Ping p) {
                p.heard.add("ping 1");
            }
        });
        subscribe(new Listener() {
            @EventHandler
            void on(LoudPing p) {
                p.heard.add("loud 2");
            }
        });
        subscribe(new Listener() {
            @EventHandler
            void on(Ping p) {
                p.heard.add("ping 3");
            }
        });
        LoudPing ping = new LoudPing();
        bus.post(ping);
        assertEquals(List.of("ping 1", "loud 2", "ping 3"), ping.heard);
    }

    @Test
    void aLaterRegisteredHigherPriorityStillRunsAfter() {
        subscribe(new Listener() {
            @EventHandler(priority = EventPriority.HIGH)
            void on(Ping p) {
                p.heard.add("high, first registered");
            }
        });
        subscribe(new Listener() {
            @EventHandler(priority = EventPriority.LOW)
            void on(Ping p) {
                p.heard.add("low, second registered");
            }
        });
        Ping ping = new Ping();
        bus.post(ping);
        assertEquals(List.of("low, second registered", "high, first registered"), ping.heard);
    }

    // ---- cancellation ----

    @Covers(value = EventHandler.class, method = "ignoreCancelled")
    @Test
    void ignoreCancelledSkipsWhatAnEarlierHandlerCancelled() {
        subscribe(new Listener() {
            @EventHandler(priority = EventPriority.LOW)
            void cancel(Stoppable s) {
                s.setCancelled(true);
            }

            @EventHandler(ignoreCancelled = true)
            void skipped(Stoppable s) {
                s.heard.add("skipped");
            }

            @EventHandler(priority = EventPriority.MONITOR)
            void monitor(Stoppable s) {
                s.heard.add("monitor sees " + s.isCancelled());
            }
        });
        Stoppable event = new Stoppable();
        bus.post(event);
        assertEquals(List.of("monitor sees true"), event.heard);
    }

    @Covers(value = EventHandler.class, method = "ignoreCancelled")
    @Test
    void anUncancelledEventReachesIgnoreCancelledHandlers() {
        subscribe(new Listener() {
            @EventHandler(ignoreCancelled = true)
            void on(Stoppable s) {
                s.heard.add("heard");
            }
        });
        Stoppable event = new Stoppable();
        bus.post(event);
        assertEquals(List.of("heard"), event.heard);
    }

    @Test
    void aLaterHandlerCanUncancel() {
        subscribe(new Listener() {
            @EventHandler(priority = EventPriority.LOW)
            void cancel(Stoppable s) {
                s.setCancelled(true);
            }

            @EventHandler(priority = EventPriority.HIGH)
            void allow(Stoppable s) {
                s.setCancelled(false);
            }

            @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
            void after(Stoppable s) {
                s.heard.add("after");
            }
        });
        Stoppable event = new Stoppable();
        bus.post(event);
        assertFalse(event.isCancelled());
        assertEquals(List.of("after"), event.heard);
    }

    // ---- which handlers hear which events ----

    @Test
    void aHandlerForAParentClassHearsSubclasses() {
        subscribe(new Listener() {
            @EventHandler
            void any(Event e) {
                ((Ping) e).heard.add("event");
            }

            @EventHandler
            void ping(Ping p) {
                p.heard.add("ping");
            }

            @EventHandler
            void loud(LoudPing p) {
                p.heard.add("loud");
            }
        });
        Ping ping = new Ping();
        bus.post(ping);
        assertEquals(List.of("event", "ping"), ping.heard);
        LoudPing loud = new LoudPing();
        bus.post(loud);
        assertEquals(List.of("event", "ping", "loud"), loud.heard);
    }

    @Test
    void listensIsTrueOnlyWhenSomethingHearsTheClass() {
        assertFalse(bus.listens(Ping.class));
        List<EventBus.Subscription> subs = subscribe(new Listener() {
            @EventHandler
            void loud(LoudPing p) {}
        });
        assertTrue(bus.listens(LoudPing.class));
        assertFalse(bus.listens(Ping.class), "a handler for a subclass doesn't hear the parent");
        subs.forEach(EventBus.Subscription::remove);
        assertFalse(bus.listens(LoudPing.class));
    }

    @Test
    void listensSeesAParentClassHandler() {
        subscribe(new Listener() {
            @EventHandler
            void any(Ping p) {}
        });
        assertTrue(bus.listens(Stoppable.class));
        assertFalse(bus.listens(Event.class));
    }

    // ---- registration and removal ----

    @Test
    void aRemovedHandlerHearsNothingMore() {
        List<EventBus.Subscription> subs = subscribe(new Listener() {
            @EventHandler
            void on(Ping p) {
                p.heard.add("heard");
            }
        });
        bus.post(new Ping()); // bakes the handler list, so removal must rebuild it
        subs.forEach(EventBus.Subscription::remove);
        Ping ping = new Ping();
        bus.post(ping);
        assertEquals(List.of(), ping.heard);
        assertFalse(bus.listens(Ping.class), "nothing hears it now, so a chokepoint can skip building it");
        subs.forEach(EventBus.Subscription::remove); // twice is fine
    }

    @Test
    void removingOneHandlerKeepsTheOthersForTheSameClass() {
        List<EventBus.Subscription> first = subscribe(new Listener() {
            @EventHandler
            void on(Ping p) {
                p.heard.add("first");
            }
        });
        subscribe(new Listener() {
            @EventHandler
            void on(Ping p) {
                p.heard.add("second");
            }
        });
        bus.post(new Ping());
        first.forEach(EventBus.Subscription::remove);
        Ping ping = new Ping();
        bus.post(ping);
        assertEquals(List.of("second"), ping.heard);
        assertTrue(bus.listens(Ping.class));
    }

    @Test
    void aHandlerAddedAfterAPostIsHeardByTheNext() {
        bus.post(new Ping());
        subscribe(new Listener() {
            @EventHandler
            void on(Ping p) {
                p.heard.add("late");
            }
        });
        Ping ping = new Ping();
        bus.post(ping);
        assertEquals(List.of("late"), ping.heard);
    }

    @Test
    void aHandlerRemovedMidPostIsSkippedForTheRestOfIt() {
        List<EventBus.Subscription> later = new ArrayList<>();
        subscribe(new Listener() {
            @EventHandler(priority = EventPriority.LOW)
            void remover(Ping p) {
                later.forEach(EventBus.Subscription::remove);
            }
        });
        later.addAll(subscribe(new Listener() {
            @EventHandler(priority = EventPriority.HIGH)
            void removed(Ping p) {
                p.heard.add("should not run");
            }
        }));
        Ping ping = new Ping();
        bus.post(ping);
        assertEquals(List.of(), ping.heard);
    }

    @Test
    void subscribeReturnsOneSubscriptionPerHandler() {
        Listener listener = new Listener() {
            @EventHandler
            void a(Ping p) {}

            @EventHandler
            void b(LoudPing p) {}

            void notAHandler(Ping p) {}
        };
        List<EventBus.Subscription> subs = subscribe(listener);
        assertEquals(2, subs.size());
        assertTrue(subs.stream().allMatch(s -> s.listener == listener));
    }

    static class BaseListener implements Listener {
        @EventHandler
        void inherited(Ping p) {
            p.heard.add("base");
        }

        @EventHandler
        void overridden(Ping p) {
            p.heard.add("base overridden");
        }
    }

    static class ChildListener extends BaseListener {
        @Override
        @EventHandler
        void overridden(Ping p) {
            p.heard.add("child overridden");
        }

        @EventHandler
        private static void staticHandler(Ping p) {
            p.heard.add("static");
        }
    }

    @Test
    void inheritedPrivateAndStaticHandlersAreFoundAndAnOverrideRunsOnce() {
        subscribe(new ChildListener());
        Ping ping = new Ping();
        bus.post(ping);
        assertEquals(3, ping.heard.size(), ping.heard.toString());
        assertTrue(ping.heard.containsAll(List.of("base", "child overridden", "static")), ping.heard.toString());
    }

    @Test
    void aHandlerThatDoesntTakeOneEventIsRefusedAndNothingIsRegistered() {
        Listener bad = new Listener() {
            @EventHandler
            void good(Ping p) {
                p.heard.add("good");
            }

            @EventHandler
            void bad(String notAnEvent) {}
        };
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> subscribe(bad));
        assertTrue(e.getMessage().startsWith("Test: "), e.getMessage());
        assertTrue(e.getMessage().contains(".bad has @EventHandler but doesn't take exactly one Hadron event"),
                e.getMessage());
        Ping ping = new Ping();
        bus.post(ping);
        assertEquals(List.of(), ping.heard, "all or nothing: the good handler must not be registered either");
    }

    @Test
    void aHandlerWithTwoParametersIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> subscribe(new Listener() {
            @EventHandler
            void two(Ping a, Ping b) {}
        }));
    }

    // ---- failures ----

    @Test
    void aThrowingHandlerCostsOnlyItsOwnCall() {
        subscribe(new Listener() {
            @EventHandler(priority = EventPriority.LOW)
            void broken(Ping p) {
                throw new IllegalStateException("plugin bug");
            }

            @EventHandler(priority = EventPriority.HIGH)
            void fine(Ping p) {
                p.heard.add("fine");
            }
        });
        Ping ping = new Ping();
        bus.post(ping);
        assertEquals(List.of("fine"), ping.heard);
        assertEquals(1, errors.messages.size());
        assertTrue(errors.messages.get(0).contains(".broken threw on " + Ping.class.getName()),
                errors.messages.get(0));
    }

    @Test
    void aFrequentFailureIsLoggedFiveTimesThenEveryThousandth() {
        subscribe(new Listener() {
            @EventHandler
            void broken(Ping p) {
                throw new IllegalStateException("plugin bug");
            }
        });
        for (int i = 0; i < 2000; i++) bus.post(new Ping());
        assertEquals(7, errors.messages.size(), errors.messages.toString());
        assertTrue(errors.messages.get(5).contains("has thrown 1000 times"), errors.messages.get(5));
        assertTrue(errors.messages.get(6).contains("has thrown 2000 times"), errors.messages.get(6));
    }

    @Test
    void anErrorThrownByAHandlerIsContainedToo() {
        subscribe(new Listener() {
            @EventHandler
            void broken(Ping p) {
                throw new AssertionError("even an Error");
            }
        });
        bus.post(new Ping());
        assertEquals(1, errors.messages.size());
    }

    // ---- threads ----

    @Test
    @Nondeterministic(reason = "Subscription.awaitIdle takes a System.nanoTime deadline, so testing it reads that clock; every margin is seconds")
    void awaitIdleWaitsForACallOnAnotherThread() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<EventBus.Subscription> subs = subscribe(new Listener() {
            @EventHandler
            void slow(Ping p) throws InterruptedException {
                inside.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
        });
        EventBus.Subscription sub = subs.get(0);
        assertTrue(sub.awaitIdle(System.nanoTime()), "idle when nothing is calling it");
        Thread poster = new Thread(() -> bus.post(new Ping()));
        poster.start();
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        assertFalse(sub.awaitIdle(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)),
                "busy while another thread is inside the handler");
        release.countDown();
        assertTrue(sub.awaitIdle(System.nanoTime() + TimeUnit.SECONDS.toNanos(10)));
        poster.join();
    }

    @Test
    @Nondeterministic(reason = "waits on another thread's handler with latches; every margin is seconds")
    void anUnregisteredListenerStillRunningIsKeptUntilItFinishes() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Listener slow = new Listener() {
            @EventHandler
            void slow(Ping p) throws InterruptedException {
                inside.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
        };
        List<EventBus.Subscription> listed = new ArrayList<>(subscribe(slow));
        Thread poster = new Thread(() -> bus.post(new Ping()));
        poster.start();
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        PluginHost.unregister(listed, slow);
        assertEquals(1, listed.size(), "a handler still running stays listed, so a disable still waits for it");
        release.countDown();
        poster.join();
        PluginHost.unregister(listed, new Listener() {});
        assertEquals(0, listed.size(), "once it finished, the next unregister lets it go");
    }

    @Test
    @Nondeterministic(reason = "Subscription.awaitIdle takes a System.nanoTime deadline, so testing it reads that clock; every margin is seconds")
    void awaitIdleReturnsAtOnceWhenNothingIsCalling() {
        EventBus.Subscription sub = subscribe(new Listener() {
            @EventHandler
            void on(Ping p) {}
        }).get(0);
        long start = System.nanoTime();
        assertTrue(sub.awaitIdle(start + TimeUnit.SECONDS.toNanos(30)));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10), "an idle handler must not wait out the deadline");
    }

    /**
     * The wait is ordered by latches and the waiter's own thread state, never by a sleep or the clock: the waiter must
     * be seen blocked inside awaitIdle while the handler is still held, and may return only after the handler ended.
     */
    @Test
    @Timeout(60)
    void awaitIdleWaitsUntilACallInProgressEnds() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean handlerEnded = new AtomicBoolean();
        EventBus.Subscription sub = subscribe(new Listener() {
            @EventHandler
            void slow(Ping p) throws InterruptedException {
                inside.countDown();
                release.await(30, TimeUnit.SECONDS);
                handlerEnded.set(true);
            }
        }).get(0);
        Thread poster = new Thread(() -> bus.post(new Ping()));
        poster.start();
        assertTrue(inside.await(30, TimeUnit.SECONDS));

        AtomicBoolean returned = new AtomicBoolean();
        AtomicBoolean idle = new AtomicBoolean();
        AtomicBoolean handlerEndedWhenItReturned = new AtomicBoolean();
        // A deadline so far away that only the call ending can end the wait.
        Thread waiter = new Thread(() -> {
            idle.set(sub.awaitIdle(Long.MAX_VALUE / 2));
            handlerEndedWhenItReturned.set(handlerEnded.get());
            returned.set(true);
        });
        waiter.setDaemon(true);
        waiter.start();
        // Wait until the waiter is parked in the wait or has already returned; either way it is settled.
        while (waiter.getState() == Thread.State.NEW || waiter.getState() == Thread.State.RUNNABLE) Thread.onSpinWait();
        assertFalse(returned.get(), "awaitIdle returned while the handler was still running; it must wait for the call "
                + "to end");
        assertEquals(Thread.State.TIMED_WAITING, waiter.getState(), "awaitIdle should be waiting on the handler");

        release.countDown();
        waiter.join(TimeUnit.SECONDS.toMillis(30));
        assertTrue(returned.get(), "the handler ending must wake the wait; it was still waiting for its deadline");
        assertTrue(idle.get(), "awaitIdle should report idle once the call ended");
        assertTrue(handlerEndedWhenItReturned.get(), "awaitIdle returned before the handler had finished");
        poster.join();
    }

    @Test
    @Nondeterministic(reason = "Subscription.awaitIdle takes a System.nanoTime deadline, so testing it reads that clock; every margin is seconds")
    void awaitIdleFromInsideTheHandlerDoesntWaitForItself() {
        List<EventBus.Subscription> subs = new ArrayList<>();
        List<Boolean> idle = new ArrayList<>();
        subs.addAll(subscribe(new Listener() {
            @EventHandler
            void self(Ping p) {
                idle.add(subs.get(0).awaitIdle(System.nanoTime()));
            }
        }));
        bus.post(new Ping());
        assertEquals(List.of(true), idle);
    }

    // ---- observers (/hadron trace) ----

    /** Records what an observer is told; hears one class and its subclasses. */
    static class Watcher implements EventBus.Observer {
        final Class<?> type;
        final List<String> seen = new ArrayList<>();
        Object state = "watched";

        Watcher(Class<?> type) {
            this.type = type;
        }

        @Override
        public boolean hears(Class<?> asked) {
            return type.isAssignableFrom(asked);
        }

        @Override
        public Object before(Event event) {
            seen.add("before " + event.getClass().getSimpleName());
            return state;
        }

        @Override
        public void after(Event event, Object state, String canceller) {
            seen.add("after " + state + " " + canceller);
        }
    }

    @Test
    void anObserverHearsAnEventNoPluginListensTo() {
        Watcher watcher = new Watcher(Ping.class);
        assertFalse(bus.listens(Ping.class));
        bus.observe(watcher);
        assertTrue(bus.listens(Ping.class), "a chokepoint must build the event for the observer");
        bus.post(new Ping());
        assertEquals(List.of("before Ping", "after watched null"), watcher.seen);
        assertFalse(bus.listens(EventBusTest.Unheard.class));
    }

    static class Unheard extends Event {}

    @Test
    void anObserverSeesTheHandlersRunAndWhoCancelled() {
        subscribe(new Listener() {
            @EventHandler(priority = EventPriority.LOW)
            void cancel(Stoppable s) {
                s.heard.add("cancel");
                s.setCancelled(true);
            }

            @EventHandler(priority = EventPriority.HIGH)
            void after(Stoppable s) {
                s.heard.add("after");
            }
        });
        Watcher watcher = new Watcher(Ping.class);
        bus.observe(watcher);
        Stoppable event = new Stoppable();
        bus.post(event);
        assertEquals(List.of("cancel", "after"), event.heard, "observing never changes which handlers run");
        assertEquals("before Stoppable", watcher.seen.get(0));
        assertTrue(watcher.seen.get(1).startsWith("after watched Test: ") && watcher.seen.get(1).endsWith(".cancel"),
                watcher.seen.toString());
    }

    @Test
    void aHandlerThatUncancelsClearsTheCanceller() {
        subscribe(new Listener() {
            @EventHandler(priority = EventPriority.LOW)
            void cancel(Stoppable s) {
                s.setCancelled(true);
            }

            @EventHandler(priority = EventPriority.HIGH)
            void allow(Stoppable s) {
                s.setCancelled(false);
            }
        });
        Watcher watcher = new Watcher(Stoppable.class);
        bus.observe(watcher);
        bus.post(new Stoppable());
        assertEquals(List.of("before Stoppable", "after watched null"), watcher.seen);
    }

    /** Hears every event, but only those some handler handles. */
    static class HandledWatcher extends Watcher {
        HandledWatcher() {
            super(Event.class);
        }

        @Override
        public boolean onlyHandled() {
            return true;
        }
    }

    @Test
    void anObserverOfHandledEventsNeverMakesAnUnheardEventBuilt() {
        HandledWatcher watcher = new HandledWatcher();
        bus.observe(watcher);
        assertFalse(bus.listens(Ping.class), "with no handler, a chokepoint must still skip building the event");
        bus.post(new Ping());
        assertEquals(List.of(), watcher.seen);
    }

    @Test
    void anObserverOfHandledEventsHearsWhatAHandlerHandles() {
        HandledWatcher watcher = new HandledWatcher();
        bus.observe(watcher);
        bus.post(new Ping());
        subscribe(new Listener() {
            @EventHandler
            void cancel(Stoppable s) {
                s.setCancelled(true);
            }
        });
        bus.post(new Stoppable());
        assertEquals(2, watcher.seen.size(), watcher.seen.toString());
        assertEquals("before Stoppable", watcher.seen.get(0));
        assertTrue(watcher.seen.get(1).endsWith(".cancel"), "it is told who cancelled: " + watcher.seen);
        assertFalse(bus.listens(Unheard.class));
    }

    @Test
    void anObserverReturningNullSkipsThatEvent() {
        Watcher watcher = new Watcher(Ping.class);
        watcher.state = null;
        bus.observe(watcher);
        bus.post(new Ping());
        assertEquals(List.of("before Ping"), watcher.seen);
    }

    @Test
    void aThrowingObserverCostsThePluginsNothing() {
        subscribe(new Listener() {
            @EventHandler
            void on(Ping p) {
                p.heard.add("handler");
            }
        });
        bus.observe(new Watcher(Ping.class) {
            @Override
            public Object before(Event event) {
                throw new IllegalStateException("observer bug");
            }
        });
        Ping ping = new Ping();
        bus.post(ping);
        assertEquals(List.of("handler"), ping.heard);
    }

    @Test
    void unobservingOneObserverKeepsTheOthers() {
        Watcher gone = new Watcher(Ping.class);
        Watcher kept = new Watcher(Ping.class);
        bus.observe(gone);
        bus.observe(kept);
        bus.unobserve(gone);
        bus.post(new Ping());
        assertEquals(List.of(), gone.seen);
        assertEquals(List.of("before Ping", "after watched null"), kept.seen);
    }

    @Test
    void anUnobservedObserverHearsNothingMore() {
        Watcher watcher = new Watcher(Ping.class);
        bus.observe(watcher);
        bus.post(new Ping());
        bus.unobserve(watcher);
        bus.unobserve(watcher); // twice is fine
        assertFalse(bus.listens(Ping.class));
        bus.post(new Ping());
        assertEquals(2, watcher.seen.size(), watcher.seen.toString());
    }

    @Test
    void aForgottenPluginsEventClassesAreNoLongerRemembered() throws Exception {
        dev.iredsc.hadron.api.plugin.JavaPlugin gone = TestPlugins.create("Gone", null);
        dev.iredsc.hadron.api.plugin.JavaPlugin kept = TestPlugins.create("Kept", null);
        Class<?> goneType = TestPlugins.event(gone);
        Class<?> keptType = TestPlugins.event(kept);
        bus.post((Event) goneType.getConstructor().newInstance());
        bus.post((Event) keptType.getConstructor().newInstance());
        bus.post(new Ping());
        assertTrue(bus.seen().contains(goneType) && bus.seen().contains(keptType));

        bus.forget(gone.getClass().getClassLoader());
        assertFalse(bus.seen().contains(goneType), "an unloaded version's class would keep its loader alive");
        assertTrue(bus.seen().contains(keptType) && bus.seen().contains(Ping.class), "only that loader's classes go");
        assertFalse(bus.listens(Ping.class), "the bus still works after forgetting");
    }
}
