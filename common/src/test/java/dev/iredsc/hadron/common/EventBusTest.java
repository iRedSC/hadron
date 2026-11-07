package dev.iredsc.hadron.common;

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
import org.junit.jupiter.api.Test;
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

    private final EventBus bus = new EventBus();
    private final Errors errors = new Errors();

    private List<EventBus.Subscription> subscribe(Listener listener) {
        return bus.subscribe(listener, "Test", errors.logger);
    }

    // ---- order ----

    @Test
    void handlersRunLowestToMonitor() {
        // COVERS: EventBus#post
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
        // COVERS: EventBus#post
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
    void aLaterRegisteredHigherPriorityStillRunsAfter() {
        // COVERS: EventBus#post
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

    @Test
    void ignoreCancelledSkipsWhatAnEarlierHandlerCancelled() {
        // COVERS: EventHandler#ignoreCancelled
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

    @Test
    void anUncancelledEventReachesIgnoreCancelledHandlers() {
        // COVERS: EventHandler#ignoreCancelled
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
        // COVERS: EventBus#post
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
        // COVERS: EventBus#post
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
        // COVERS: EventBus#listens
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
        // COVERS: EventBus#listens
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
        // COVERS: EventBus.Subscription#remove
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
        subs.forEach(EventBus.Subscription::remove); // twice is fine
    }

    @Test
    void aHandlerAddedAfterAPostIsHeardByTheNext() {
        // COVERS: EventBus#subscribe
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
        // COVERS: EventBus.Subscription#remove
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
        // COVERS: EventBus#subscribe
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
        // COVERS: EventBus#subscribe
        subscribe(new ChildListener());
        Ping ping = new Ping();
        bus.post(ping);
        assertEquals(3, ping.heard.size(), ping.heard.toString());
        assertTrue(ping.heard.containsAll(List.of("base", "child overridden", "static")), ping.heard.toString());
    }

    @Test
    void aHandlerThatDoesntTakeOneEventIsRefusedAndNothingIsRegistered() {
        // COVERS: EventBus#subscribe
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
        // COVERS: EventBus#subscribe
        assertThrows(IllegalArgumentException.class, () -> subscribe(new Listener() {
            @EventHandler
            void two(Ping a, Ping b) {}
        }));
    }

    // ---- failures ----

    @Test
    void aThrowingHandlerCostsOnlyItsOwnCall() {
        // COVERS: EventBus#post
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
        // COVERS: EventBus#post
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
        // COVERS: EventBus#post
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
    void awaitIdleWaitsForACallOnAnotherThread() throws Exception {
        // COVERS: EventBus.Subscription#awaitIdle
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
    void awaitIdleFromInsideTheHandlerDoesntWaitForItself() {
        // COVERS: EventBus.Subscription#awaitIdle
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
}
