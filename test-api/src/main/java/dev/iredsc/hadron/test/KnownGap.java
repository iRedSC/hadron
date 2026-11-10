package dev.iredsc.hadron.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@link HadronTest} that shows something Hadron doesn't do yet, so it is expected to fail. Write the test as it
 * should pass once the gap is closed, and say here what is missing:
 *
 * <pre>{@code
 * @HadronTest
 * @KnownGap("EntityTeleportEvent states where a teleport asked to go, not where it settles")
 * void aChorusTeleportStatesWhereItLands(TestContext t) { ... }
 * }</pre>
 *
 * <p>Its failure is reported as {@code GAP}, with its message, and doesn't fail the run. Once it passes, the run fails
 * and says to remove this annotation, so a closed gap is never left marked open. A gap test that hangs or can't run
 * still fails the run: only its assertions are expected to fail.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface KnownGap {
    /** What Hadron doesn't do yet, in one sentence. */
    String value();
}
