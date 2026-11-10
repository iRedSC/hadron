package dev.iredsc.hadron.checks;

import static com.google.errorprone.BugPattern.SeverityLevel.ERROR;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MemberReferenceTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.NewClassTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import javax.inject.Inject;

/**
 * Refuses, outside Hadron's own code, every call or method reference to something that takes Hadron's
 * {@code HostKey}, and to {@code HostKey}'s own methods: {@code Hadron.Lifecycle.install}, {@code
 * Economy.Lifecycle.install}, {@code Node.Lifecycle}, the loader port's {@code install} methods, {@code
 * Actors.Port.enter} and {@code exit} (pointing at {@code Actors.push}), and anything else
 * given a {@code HostKey} parameter later. Only Hadron's mod holds the key, so in a plugin each of these throws at
 * runtime; this says so at build time, with what to use instead. Hadron's own modules ({@code
 * -XepOpt:Hadron:AllowExemptions=true}) pass. Nothing suppresses it.
 */
@AutoService(BugChecker.class)
@BugPattern(
        summary = "Only Hadron's host holds the HostKey, so what takes it always throws in a plugin; read what the "
                + "host installed instead",
        severity = ERROR,
        suppressionAnnotations = {})
public final class HostOnly extends BugChecker
        implements MethodInvocationTreeMatcher, MemberReferenceTreeMatcher, NewClassTreeMatcher {
    static final String HOST_KEY = "dev.iredsc.hadron.api.HostKey";

    private final boolean hadron;

    /** For ServiceLoader, which finds the check; Error Prone itself makes it with its flags, below. */
    public HostOnly() {
        this(ErrorProneFlags.empty());
    }

    @Inject
    public HostOnly(ErrorProneFlags flags) {
        hadron = flags.getBoolean(NoStaticMutableState.ALLOW_EXEMPTIONS).orElse(false);
    }

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        return check(tree, ASTHelpers.getSymbol(tree));
    }

    /** {@code HostKey::claim} and {@code Hadron.Lifecycle::install} are the same calls, made later. */
    @Override
    public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
        Symbol sym = ASTHelpers.getSymbol(tree);
        return sym instanceof MethodSymbol method ? check(tree, method) : Description.NO_MATCH;
    }

    @Override
    public Description matchNewClass(NewClassTree tree, VisitorState state) {
        return check(tree, ASTHelpers.getSymbol(tree));
    }

    private Description check(ExpressionTree tree, MethodSymbol method) {
        if (hadron || !hostOnly(method)) return Description.NO_MATCH;
        String name = name(method);
        String instead = name.equals("Actors.Port.enter") || name.equals("Actors.Port.exit")
                ? "Push an actor with try (Actors.Scope scope = Actors.push(actor)) { ... }, or Actors.acting(actor, "
                        + "work): both close on their own, so the actor can't be left on the stack."
                : "A plugin reads what the host installed instead: Hadron.getServer() or getServer(), Economy.get(), "
                        + "Node.declare, Packets, Visibility, DisplayNames, Worlds, Areas, PersistentData.of. A test "
                        + "gets a running server from @HadronTest.";
        return buildDescription(tree).setMessage(name + " is for Hadron's own host: it takes the HostKey, which "
                + "Hadron's mod claims before any plugin loads, so in a plugin it always throws. " + instead).build();
    }

    private static boolean hostOnly(MethodSymbol method) {
        if (method.owner.getQualifiedName().contentEquals(HOST_KEY)) return true;
        for (Symbol.VarSymbol p : method.getParameters()) {
            if (p.type.tsym != null && p.type.tsym.getQualifiedName().contentEquals(HOST_KEY)) return true;
        }
        return false;
    }

    /** {@code Hadron.Lifecycle.install}, {@code new PluginHost}: the owner without its package. */
    private static String name(MethodSymbol method) {
        String owner = method.owner.getQualifiedName().toString();
        String pkg = method.owner.packge().getQualifiedName().toString();
        String simple = pkg.isEmpty() ? owner : owner.substring(pkg.length() + 1);
        return method.isConstructor() ? "new " + simple : simple + "." + method.getSimpleName();
    }
}
