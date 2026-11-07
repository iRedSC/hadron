package dev.iredsc.hadron.checks;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lets a method (or a field's initializer) read the wall clock, sleep or use unseeded randomness, which
 * {@code Determinism} otherwise refuses in Hadron's {@code common} and in tests. For waits on other threads with a
 * deadline, and timings printed for people; never for a decision a test needs to repeat.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Nondeterministic {
    /** What waits on the wall clock and why ticks can't stand in, e.g. "grace period for async tasks on disable". */
    String reason();
}
