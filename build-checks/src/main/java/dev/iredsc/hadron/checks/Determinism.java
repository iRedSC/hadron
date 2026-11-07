package dev.iredsc.hadron.checks;

import static com.google.errorprone.BugPattern.SeverityLevel.ERROR;
import static com.google.errorprone.matchers.method.MethodMatchers.constructor;
import static com.google.errorprone.matchers.method.MethodMatchers.staticMethod;
import static com.google.errorprone.matchers.method.MethodMatchers.instanceMethod;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.NewClassTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;

/**
 * Refuses the wall clock, sleeps and unseeded randomness where Hadron promises determinism: its {@code common} host
 * and tests. A test that sleeps or reads the clock passes or fails by how busy the machine is; Hadron's time is
 * server ticks and its randomness is seeded.
 */
@AutoService(BugChecker.class)
@BugPattern(
        summary = "Wall-clock time, sleeps and unseeded randomness make results depend on the machine; use ticks "
                + "and seeded randomness",
        severity = ERROR,
        suppressionAnnotations = {})
public final class Determinism extends BugChecker implements MethodInvocationTreeMatcher, NewClassTreeMatcher {
    static final String EXEMPTION = "dev.iredsc.hadron.checks.Nondeterministic";

    private static final String TICKS = " Time in Hadron is server ticks: in a test, t.tick(n) or "
            + "t.await(condition, ticks); in a plugin or Hadron's host, the scheduler's runTaskLater(ticks) or the "
            + "server's tick count.";

    private static final Matcher<ExpressionTree> CLOCK = staticMethod().onClass("java.lang.System")
            .namedAnyOf("currentTimeMillis", "nanoTime");
    private static final Matcher<ExpressionTree> NOW = staticMethod()
            .onClassAny("java.time.Instant", "java.time.LocalDateTime", "java.time.LocalTime", "java.time.ZonedDateTime",
                    "java.time.OffsetDateTime", "java.time.LocalDate")
            .named("now");
    private static final Matcher<ExpressionTree> SLEEP = Matchers.anyOf(
            staticMethod().onClass("java.lang.Thread").named("sleep"),
            instanceMethod().onExactClass("java.util.concurrent.TimeUnit").named("sleep"));
    private static final Matcher<ExpressionTree> RANDOM = Matchers.anyOf(
            staticMethod().onClass("java.lang.Math").named("random"),
            staticMethod().onClass("java.util.concurrent.ThreadLocalRandom").named("current"));
    private static final Matcher<ExpressionTree> NEW_RANDOM = constructor().forClass("java.util.Random")
            .withNoParameters();

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        String message;
        if (CLOCK.matches(tree, state) || NOW.matches(tree, state)) {
            message = "Reading the wall clock makes a result depend on how fast the machine runs." + TICKS;
        } else if (SLEEP.matches(tree, state)) {
            message = "Sleeping waits a fixed wall time, so it passes on a fast machine and fails on a busy one."
                    + TICKS + " To wait for another thread, wait on what it signals (a CompletableFuture, a latch).";
        } else if (RANDOM.matches(tree, state)) {
            message = "Unseeded randomness can't be repeated when a test fails. Take a java.util.Random with a seed "
                    + "(new Random(seed)), or the level's own RandomSource, so the same seed gives the same result.";
        } else {
            return Description.NO_MATCH;
        }
        return report(tree, state, message);
    }

    @Override
    public Description matchNewClass(NewClassTree tree, VisitorState state) {
        if (!NEW_RANDOM.matches(tree, state)) return Description.NO_MATCH;
        return report(tree, state, "new Random() is seeded from the clock, so a failure can't be repeated. Pass a "
                + "seed: new Random(seed), or use the level's own RandomSource.");
    }

    private Description report(Tree tree, VisitorState state, String message) {
        for (TreePath path = state.getPath(); path != null; path = path.getParentPath()) {
            Tree leaf = path.getLeaf();
            Symbol sym = switch (leaf.getKind()) {
                case METHOD, VARIABLE, CLASS -> ASTHelpers.getSymbol(leaf);
                default -> null;
            };
            if (sym == null) continue;
            Attribute.Compound exemption = NoStaticMutableState.exemption(sym, EXEMPTION);
            if (exemption == null) continue;
            if (!NoStaticMutableState.isHadron(sym)) {
                return buildDescription(tree).setMessage("@Nondeterministic is only for Hadron's own code. "
                        + message).build();
            }
            if (NoStaticMutableState.reason(exemption).isBlank()) {
                return buildDescription(tree).setMessage("@Nondeterministic needs a reason that says what waits on "
                        + "the wall clock and why ticks can't stand in, e.g. @Nondeterministic(reason = \"grace "
                        + "period for async tasks on disable\").").build();
            }
            return Description.NO_MATCH;
        }
        return buildDescription(tree).setMessage(message + " If the wall clock is the point (a deadline for another "
                + "thread, a timing printed for people), annotate the method @Nondeterministic(reason = \"...\").")
                .build();
    }
}
