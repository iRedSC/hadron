package dev.iredsc.hadron.api.actor;

import dev.iredsc.hadron.test.Covers;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The actor stack decides who every event is attributed to; none of it needs Minecraft. */
class ActorsTest {
    private static final Actor CONSOLE = new Actor.Server("Server");
    private static final Actor BLOCK = new Actor.Server("@");

    @BeforeEach
    @AfterEach
    void emptyStack() {
        // A test that fails mid-scope must not leave its actors to the next one.
        clearAll();
    }

    /** Closes every frame on this thread, whatever its token. */
    private static void clearAll() {
        try {
            Object stack = stack();
            Field depth = stack.getClass().getDeclaredField("depth");
            depth.setAccessible(true);
            Field actors = stack.getClass().getDeclaredField("actors");
            actors.setAccessible(true);
            Field operations = stack.getClass().getDeclaredField("operations");
            operations.setAccessible(true);
            java.util.Arrays.fill((Object[]) actors.get(stack), null);
            java.util.Arrays.fill((Object[]) operations.get(stack), null);
            depth.setInt(stack, 0);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object stack() throws ReflectiveOperationException {
        Field field = Actors.class.getDeclaredField("STACK");
        field.setAccessible(true);
        return ((ThreadLocal<?>) field.get(null)).get();
    }

    private static void setNextToken(long value) throws ReflectiveOperationException {
        Object stack = stack();
        Field next = stack.getClass().getDeclaredField("nextToken");
        next.setAccessible(true);
        next.setLong(stack, value);
    }

    // ---- push and pop ----

    @Covers(value = Actors.class, method = "current")
    @Covers(value = Actors.class, method = "currentOr")
    @Covers(value = Actors.class, method = "isEmpty")
    @Test
    void anEmptyStackHasNoActorAndCurrentOrGivesTheFallback() {
        assertTrue(Actors.isEmpty());
        assertEquals(Optional.empty(), Actors.current());
        assertSame(Actor.Unknown.INSTANCE, Actors.currentOr(Actor.Unknown.INSTANCE));
    }

    @Covers(value = Actors.class, method = "push")
    @Test
    void aPushedActorIsOnTopUntilItsScopeCloses() {
        try (Actors.Scope scope = Actors.push(CONSOLE)) {
            assertEquals(Optional.of(CONSOLE), Actors.current());
            assertSame(CONSOLE, Actors.currentOr(Actor.Nature.INSTANCE));
            assertFalse(Actors.isEmpty());
        }
        assertTrue(Actors.isEmpty());
    }

    @Covers(value = Actors.class, method = "push")
    @Covers(value = Actors.Scope.class, method = "close")
    @Test
    void nestedPushesUnwindInReverseOrder() {
        try (Actors.Scope outer = Actors.push(CONSOLE)) {
            try (Actors.Scope inner = Actors.push(BLOCK)) {
                assertSame(BLOCK, Actors.current().orElseThrow());
            }
            assertSame(CONSOLE, Actors.current().orElseThrow());
        }
        assertTrue(Actors.isEmpty());
    }

    @Covers(value = Actors.class, method = "push")
    @Test
    void pushingNullIsRefusedWithTheFix() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> Actors.push(null));
        assertTrue(e.getMessage().contains("Actor.Unknown.INSTANCE"), e.getMessage());
        assertTrue(Actors.isEmpty());
    }

    @Covers(value = Actors.Scope.class, method = "close")
    @Test
    void closingAScopeTwiceDoesNothingTheSecondTime() {
        Actors.Scope first = Actors.push(CONSOLE);
        first.close();
        try (Actors.Scope second = Actors.push(BLOCK)) {
            first.close(); // same index as second, older token: must not pop second
            assertSame(BLOCK, Actors.current().orElseThrow());
        }
    }

    @Covers(value = Actors.Scope.class, method = "close")
    @Test
    void closingAnOuterScopeClosesInnerOnesLeftOpen() {
        Actors.Scope outer = Actors.push(CONSOLE);
        Actors.push(BLOCK); // leaked: never closed
        Actors.push(Actor.Nature.INSTANCE); // leaked
        outer.close();
        assertTrue(Actors.isEmpty());
    }

    @Covers(value = Actors.Scope.class, method = "close")
    @Test
    void aLeakedScopeClosedLateLeavesNewerPushesAlone() {
        Actors.Scope outer = Actors.push(CONSOLE);
        Actors.Scope leaked = Actors.push(BLOCK);
        outer.close(); // repairs the leak
        try (Actors.Scope fresh = Actors.push(Actor.Nature.INSTANCE)) {
            try (Actors.Scope above = Actors.push(CONSOLE)) {
                leaked.close(); // index 1 is now `above`, with a different token
                assertSame(CONSOLE, Actors.current().orElseThrow());
            }
            assertSame(Actor.Nature.INSTANCE, Actors.current().orElseThrow());
        }
    }

    @Covers(value = Actors.Scope.class, method = "close")
    @Test
    void closingOnAnotherThreadIsRefusedAndLeavesTheStackAlone() throws Exception {
        try (Actors.Scope scope = Actors.push(CONSOLE)) {
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    scope.close();
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });
            other.start();
            other.join();
            assertTrue(thrown.get() instanceof IllegalStateException, String.valueOf(thrown.get()));
            assertTrue(thrown.get().getMessage().contains("same thread"), thrown.get().getMessage());
            assertSame(CONSOLE, Actors.current().orElseThrow());
        }
    }

    @Covers(value = Actors.class, method = "current")
    @Test
    void eachThreadHasItsOwnStack() throws Exception {
        try (Actors.Scope scope = Actors.push(CONSOLE)) {
            AtomicReference<Optional<Actor>> seen = new AtomicReference<>();
            Thread worker = new Thread(() -> seen.set(Actors.current()));
            worker.start();
            worker.join();
            assertEquals(Optional.empty(), seen.get());
        }
    }

    @Covers(value = Actors.class, method = "push")
    @Test
    void theStackGrowsPastItsInitialSize() {
        List<Actors.Scope> scopes = new ArrayList<>();
        for (int i = 0; i < 100; i++) scopes.add(Actors.push(new Actor.Server("s" + i)));
        assertEquals("s99", ((Actor.Server) Actors.current().orElseThrow()).name());
        for (int i = scopes.size() - 1; i >= 0; i--) {
            assertEquals("s" + i, ((Actor.Server) Actors.current().orElseThrow()).name());
            scopes.get(i).close();
        }
        assertTrue(Actors.isEmpty());
    }

    @Covers(value = Actors.class, method = "acting")
    @Test
    void actingRunsTheWorkWithTheActorOnTopAndRestoresItAfter() {
        String result = Actors.acting(CONSOLE, () -> ((Actor.Server) Actors.current().orElseThrow()).name());
        assertEquals("Server", result);
        AtomicReference<Actor> seen = new AtomicReference<>();
        Actors.acting(BLOCK, () -> seen.set(Actors.currentOr(null)));
        assertSame(BLOCK, seen.get());
        assertTrue(Actors.isEmpty());
    }

    @Covers(value = Actors.class, method = "acting")
    @Test
    void actingRestoresTheStackWhenTheWorkThrows() {
        assertThrows(IllegalStateException.class, () -> Actors.acting(CONSOLE, (Runnable) () -> {
            throw new IllegalStateException("boom");
        }));
        assertTrue(Actors.isEmpty());
    }

    // ---- the port's allocation-free path ----

    @Covers(value = Actors.Port.class, method = "enter")
    @Covers(value = Actors.Port.class, method = "exit")
    @Test
    void portEnterAndExitPushAndPop() {
        long handle = Actors.Port.enter(CONSOLE);
        try {
            assertSame(CONSOLE, Actors.current().orElseThrow());
        } finally {
            Actors.Port.exit(handle);
        }
        assertTrue(Actors.isEmpty());
    }

    @Covers(value = Actors.Port.class, method = "enter")
    @Test
    void portHandlesCarryTheirDepth() {
        long outer = Actors.Port.enter(CONSOLE);
        long inner = Actors.Port.enter(BLOCK);
        assertEquals(0, outer >>> 40);
        assertEquals(1, inner >>> 40);
        Actors.Port.exit(inner);
        Actors.Port.exit(outer);
    }

    @Covers(value = Actors.Port.class, method = "exit")
    @Test
    void portExitOfAStaleHandleDoesNothing() {
        long stale = Actors.Port.enter(CONSOLE);
        Actors.Port.exit(stale);
        long fresh = Actors.Port.enter(BLOCK);
        Actors.Port.exit(stale); // same index, older token
        assertSame(BLOCK, Actors.current().orElseThrow());
        Actors.Port.exit(fresh);
        assertTrue(Actors.isEmpty());
    }

    @Covers(value = Actors.Port.class, method = "exit")
    @Test
    void portExitClosesWhatWasLeftOpenAboveIt() {
        long handle = Actors.Port.enter(CONSOLE);
        Actors.push(BLOCK); // leaked by some mod's code inside the tick
        Actors.Port.exit(handle);
        assertTrue(Actors.isEmpty());
    }

    @Covers(value = Actors.Port.class, method = "enter")
    @Test
    void portEnterRefusesNull() {
        assertThrows(NullPointerException.class, () -> Actors.Port.enter(null));
        assertTrue(Actors.isEmpty());
    }

    @Covers(value = Actors.Port.class, method = "enter")
    @Test
    void tokensWrapWithinFortyBitsAndSkipZero() throws Exception {
        long mask = (1L << 40) - 1;
        setNextToken(mask - 1);
        long last = Actors.Port.enter(CONSOLE); // token mask
        assertEquals(mask, last & mask);
        Actors.Port.exit(last);
        long wrapped = Actors.Port.enter(BLOCK); // would be 0: becomes 1
        assertEquals(1, wrapped & mask);
        assertEquals(0, wrapped >>> 40);
        assertSame(BLOCK, Actors.current().orElseThrow());
        Actors.Port.exit(last); // the pre-wrap handle at the same index must not close the new frame
        assertSame(BLOCK, Actors.current().orElseThrow());
        Actors.Port.exit(wrapped);
        assertTrue(Actors.isEmpty());
    }

    @Covers(value = Actors.Port.class, method = "installMemory")
    @Test
    void installingNullMemoryIsRefused() {
        assertThrows(NullPointerException.class, () -> Actors.Port.installMemory(null));
    }

    // ---- operations ----

    @Covers(value = Operation.class, method = "current")
    @Test
    void noOperationIsOpenOutsideBegin() {
        assertNull(Operation.current());
        try (Actors.Scope scope = Actors.push(CONSOLE)) {
            assertNull(Operation.current());
        }
    }

    @Covers(value = Operation.class, method = "begin")
    @Test
    void beginOpensAnOperationWithItsActorOnTop() {
        try (Actors.Scope scope = Operation.begin(CONSOLE)) {
            Operation open = Operation.current();
            assertSame(CONSOLE, open.actor());
            assertSame(CONSOLE, Actors.current().orElseThrow());
        }
        assertNull(Operation.current());
        assertTrue(Actors.isEmpty());
    }

    @Covers(value = Actors.class, method = "push")
    @Covers(value = Operation.class, method = "current")
    @Test
    void anActorPushedInsideAnOperationKeepsIt() {
        try (Actors.Scope op = Operation.begin(CONSOLE)) {
            Operation open = Operation.current();
            try (Actors.Scope inner = Actors.push(BLOCK)) {
                assertSame(open, Operation.current());
                assertSame(BLOCK, Actors.current().orElseThrow());
            }
            long handle = Actors.Port.enter(Actor.Nature.INSTANCE);
            assertSame(open, Operation.current());
            Actors.Port.exit(handle);
        }
    }

    @Covers(value = Operation.class, method = "begin")
    @Test
    void aNestedOperationReplacesTheOuterUntilItCloses() {
        try (Actors.Scope outer = Operation.begin(CONSOLE)) {
            Operation first = Operation.current();
            try (Actors.Scope inner = Operation.begin(BLOCK)) {
                assertNotEquals(first.id(), Operation.current().id());
                assertSame(BLOCK, Operation.current().actor());
            }
            assertSame(first, Operation.current());
        }
    }

    @Covers(value = Operation.class, method = "single")
    @Covers(value = Operation.class, method = "id")
    @Test
    void singleOperationsGetFreshIdsAndDontOpenAnything() {
        Operation a = Operation.single(CONSOLE);
        Operation b = Operation.single(CONSOLE);
        assertTrue(b.id() > a.id());
        assertNull(Operation.current());
        assertThrows(NullPointerException.class, () -> Operation.single(null));
    }

    @Covers(value = Actors.Port.class, method = "exit")
    @Test
    void anEmptyStackStaysUsableAfterRepeatedCleanup() {
        assertDoesNotThrow(() -> Actors.Port.exit(1));
        assertTrue(Actors.isEmpty());
    }
}
