package dev.iredsc.hadron.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a test proves: an event it fires and checks a listener receives, or an API method it calls and checks the effect
 * of. Repeat it for each thing the test proves. The build reads these and fails, naming what's missing, when a shipped
 * event or public API method has no test that covers it ({@code coverageCheck}).
 *
 * <pre>{@code
 * @HadronTest
 * @Covers(BlockBreakEvent.class)
 * void cancellingABreakKeepsTheBlock(TestContext t) { ... }
 *
 * @Test
 * @Covers(value = Economy.class, method = "transfer")   // every overload named transfer
 * void transferMovesExactAmounts() { ... }
 * }</pre>
 *
 * <p>Put it on the test method itself, next to {@link HadronTest} or JUnit's {@code @Test}; the build refuses it
 * anywhere else, on a {@code @Disabled} test, on an abstract event, on a non-event type without a method, and on a
 * method the type doesn't have, so nothing looks covered that isn't.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Covers.List.class)
public @interface Covers {
    /** The event the test fires, or the API type whose {@link #method} it calls. */
    Class<?> value();

    /** The method's name, covering every overload of it; empty when {@link #value} is an event. */
    String method() default "";

    /** Holds repeated {@link Covers}; write {@code @Covers} several times instead of using this. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {
        Covers[] value();
    }
}
