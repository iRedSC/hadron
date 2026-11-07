package dev.iredsc.hadron.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A test {@code ./gradlew hadronTest} runs in a headless server. Put it on a method in {@code src/test/java} that takes
 * one {@link TestContext} and returns nothing; the class needs a constructor without arguments (the default one is
 * enough), and a fresh instance runs each test:
 *
 * <pre>{@code
 * public final class ShopTests {
 *     @HadronTest
 *     void buyingTakesMoney(TestContext t) {
 *         TestPlayer alice = t.player("alice");
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <p>Each test gets its own world, its own players and its own listeners, all gone when it ends, so tests never see
 * each other's blocks, players or balances.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HadronTest {
    /**
     * The most server ticks the test may run (20 ticks are one second of game time; the harness runs them as fast as
     * the machine allows). Joining a player, breaking a block by hand and {@link TestContext#tick} all spend ticks.
     * A test past its limit fails, saying where it was waiting.
     */
    int timeout() default 600;
}
