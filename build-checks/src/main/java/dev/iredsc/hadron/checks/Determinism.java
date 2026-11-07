package dev.iredsc.hadron.checks;

import static com.google.errorprone.BugPattern.SeverityLevel.ERROR;
import static com.google.errorprone.matchers.method.MethodMatchers.constructor;
import static com.google.errorprone.matchers.method.MethodMatchers.staticMethod;
import static com.google.errorprone.matchers.method.MethodMatchers.instanceMethod;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MemberReferenceTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.NewClassTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import javax.inject.Inject;

/**
 * Refuses the wall clock, sleeps and unseeded randomness where Hadron promises determinism: its {@code common} host
 * and tests. A test that sleeps or reads the clock passes or fails by how busy the machine is; Hadron's time is
 * server ticks and its randomness is seeded. It catches the common ways to read the clock, sleep or draw unseeded
 * randomness, not every one.
 */
@AutoService(BugChecker.class)
@BugPattern(
        summary = "Wall-clock time, sleeps and unseeded randomness make results depend on the machine; use ticks "
                + "and seeded randomness",
        severity = ERROR,
        suppressionAnnotations = {})
public final class Determinism extends BugChecker
        implements MethodInvocationTreeMatcher, MemberReferenceTreeMatcher, NewClassTreeMatcher {
    static final String EXEMPTION = "dev.iredsc.hadron.checks.Nondeterministic";

    private final boolean exemptions;

    /** For ServiceLoader, which finds the check; Error Prone itself makes it with its flags, below. */
    public Determinism() {
        this(ErrorProneFlags.empty());
    }

    @Inject
    public Determinism(ErrorProneFlags flags) {
        exemptions = flags.getBoolean(NoStaticMutableState.ALLOW_EXEMPTIONS).orElse(false);
    }

    private static final String TICKS = " Time in Hadron is server ticks: in a test, t.tick(n) or "
            + "t.await(condition, ticks); in a plugin or Hadron's host, the scheduler's runTaskLater(ticks) or the "
            + "server's tick count.";

    private static final Matcher<ExpressionTree> CLOCK = Matchers.anyOf(
            staticMethod().onClass("java.lang.System").namedAnyOf("currentTimeMillis", "nanoTime"),
            // The system clock itself: whatever reads it later (millis(), instant(), Instant.now(clock)) reads the
            // wall clock. Clock.fixed(...) and Clock.offset of a fixed clock stay allowed.
            staticMethod().onClass("java.time.Clock")
                    .namedAnyOf("systemUTC", "systemDefaultZone", "system", "tickSeconds", "tickMinutes", "tickMillis"),
            staticMethod().onClass("java.time.InstantSource").named("system"),
            // The old clock: new Date() and a Calendar made for now.
            constructor().forClass("java.util.Date").withNoParameters(),
            constructor().forClass("java.util.GregorianCalendar").withNoParameters(),
            staticMethod().onClass("java.util.Calendar").named("getInstance"),
            // now() and now(ZoneId) read the system clock; now(Clock) reads the clock it's given.
            Matchers.allOf(
                    staticMethod().onClassAny("java.time.Instant", "java.time.LocalDateTime", "java.time.LocalTime",
                                    "java.time.ZonedDateTime", "java.time.OffsetDateTime", "java.time.OffsetTime",
                                    "java.time.LocalDate", "java.time.Year", "java.time.YearMonth",
                                    "java.time.MonthDay")
                            .named("now"),
                    Matchers.not(staticMethod().anyClass().named("now").withParameters("java.time.Clock"))));
    private static final Matcher<ExpressionTree> SLEEP = Matchers.anyOf(
            staticMethod().onClass("java.lang.Thread").named("sleep"),
            instanceMethod().onExactClass("java.util.concurrent.TimeUnit").named("sleep"));
    private static final Matcher<ExpressionTree> RANDOM = Matchers.anyOf(
            staticMethod().onClass("java.lang.Math").named("random"),
            staticMethod().onClass("java.util.concurrent.ThreadLocalRandom").named("current"),
            staticMethod().onClass("net.minecraft.util.RandomSource").named("create").withNoParameters(),
            constructor().forClass("java.util.Random").withNoParameters(),
            constructor().forClass("java.util.SplittableRandom").withNoParameters(),
            // RandomGenerator.of(name) and a factory's create() seed themselves; create(seed) doesn't.
            staticMethod().onClass("java.util.random.RandomGenerator").namedAnyOf("getDefault", "of"),
            instanceMethod().onExactClass("java.util.random.RandomGeneratorFactory").named("create")
                    .withNoParameters(),
            // shuffle(list) draws from a shared unseeded Random; shuffle(list, random) uses the one it's given.
            staticMethod().onClass("java.util.Collections").named("shuffle").withParameters("java.util.List"),
            Determinism::unseededAnonymous);

    /** new Random() {}: an anonymous subclass calls the same unseeded constructor. */
    private static boolean unseededAnonymous(ExpressionTree tree, VisitorState state) {
        if (!(tree instanceof NewClassTree created) || created.getClassBody() == null
                || !created.getArguments().isEmpty()) {
            return false;
        }
        return state.getTypes().erasure(ASTHelpers.getType(created.getIdentifier())).tsym.getQualifiedName()
                .contentEquals("java.util.Random");
    }

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        return check(tree, state);
    }

    /** System::nanoTime and Thread::sleep are the same calls, made later. */
    @Override
    public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
        return check(tree, state);
    }

    @Override
    public Description matchNewClass(NewClassTree tree, VisitorState state) {
        return check(tree, state);
    }

    private Description check(ExpressionTree tree, VisitorState state) {
        String message;
        if (CLOCK.matches(tree, state)) {
            message = "Reading the wall clock makes a result depend on how fast the machine runs." + TICKS
                    + " Code that takes a java.time.Clock can be given Clock.fixed(...).";
        } else if (SLEEP.matches(tree, state)) {
            message = "Sleeping waits a fixed wall time, so it passes on a fast machine and fails on a busy one."
                    + TICKS + " To wait for another thread (an async task, a chunk loading), in a test, "
                    + "t.awaitAsync(\"what\", condition, ticks); elsewhere, wait on what it signals (a "
                    + "CompletableFuture, a latch).";
        } else if (RANDOM.matches(tree, state)) {
            message = "Unseeded randomness can't be repeated when a test fails. Pass a seed: new Random(seed), "
                    + "new SplittableRandom(seed), RandomSource.create(seed), Collections.shuffle(list, random), or use the level's own RandomSource, "
                    + "so the same seed gives the same result.";
        } else {
            return Description.NO_MATCH;
        }
        return report(tree, state, message);
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
            if (!exemptions) {
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
        return buildDescription(tree).setMessage(message + (exemptions ? " If the wall clock is the point (a deadline "
                + "for another thread, a timing printed for people), annotate the method "
                + "@Nondeterministic(reason = \"...\")." : "")).build();
    }
}
