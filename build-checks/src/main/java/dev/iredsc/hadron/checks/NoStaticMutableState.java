package dev.iredsc.hadron.checks;

import static com.google.errorprone.BugPattern.SeverityLevel.ERROR;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.VariableTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.ElementKind;

/**
 * Refuses a static field that holds state: one that isn't final, or is final but holds a mutable collection, map,
 * array or counter. A plugin's classes are reloaded with a new class loader, so static state either outlives the
 * reload (keeping the old class loader alive) or silently starts empty, and the plugin looks fine until a later
 * reload.
 */
@AutoService(BugChecker.class)
@BugPattern(
        summary = "Static mutable state survives or silently resets on a plugin reload; keep it on an instance",
        severity = ERROR,
        suppressionAnnotations = {})
public final class NoStaticMutableState extends BugChecker implements VariableTreeMatcher {
    static final String EXEMPTION = "dev.iredsc.hadron.checks.StaticState";
    private static final String HADRON = "dev.iredsc.hadron";

    /** Types whose instances change after they're built, unless the initializer is one of {@link #IMMUTABLE}. */
    private static final List<String> MUTABLE = List.of(
            "java.util.Collection", "java.util.Map", "java.util.BitSet",
            "com.google.common.collect.Multimap", "com.google.common.collect.Table",
            "java.lang.StringBuilder", "java.lang.StringBuffer",
            "java.util.concurrent.atomic.AtomicBoolean", "java.util.concurrent.atomic.AtomicInteger",
            "java.util.concurrent.atomic.AtomicLong", "java.util.concurrent.atomic.AtomicReference",
            "java.util.concurrent.atomic.AtomicIntegerArray", "java.util.concurrent.atomic.AtomicLongArray",
            "java.util.concurrent.atomic.AtomicReferenceArray", "java.util.concurrent.atomic.LongAdder",
            "java.util.concurrent.atomic.DoubleAdder");

    /** Owner classes whose static factories return unmodifiable collections. */
    private static final Set<String> IMMUTABLE_OWNERS = Set.of(
            "java.util.List", "java.util.Set", "java.util.Map", "java.util.EnumSet");

    /** java.util.Collections' factories that return unmodifiable views or empty collections. */
    private static final Set<String> COLLECTIONS_IMMUTABLE = Set.of(
            "emptyList", "emptySet", "emptyMap", "emptyNavigableMap", "emptyNavigableSet", "emptySortedMap",
            "emptySortedSet", "singleton", "singletonList", "singletonMap", "unmodifiableCollection",
            "unmodifiableList", "unmodifiableMap", "unmodifiableNavigableMap", "unmodifiableNavigableSet",
            "unmodifiableSet", "unmodifiableSequencedCollection", "unmodifiableSequencedMap",
            "unmodifiableSequencedSet", "unmodifiableSortedMap", "unmodifiableSortedSet", "nCopies");

    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
        VarSymbol sym = ASTHelpers.getSymbol(tree);
        if (sym == null || sym.getKind() != ElementKind.FIELD || !sym.isStatic()) return Description.NO_MATCH;
        // Mixin @Shadow fields are Minecraft's, declared again only so the mixin can name them.
        if (ASTHelpers.hasAnnotation(sym, "org.spongepowered.asm.mixin.Shadow", state)) return Description.NO_MATCH;

        String problem = problem(sym, tree.getInitializer(), state);
        if (problem == null) return Description.NO_MATCH;

        Attribute.Compound exemption = exemption(sym);
        if (exemption != null) {
            if (!isHadron(sym)) {
                return describe(tree, "@StaticState is only for Hadron's own code. A plugin keeps state on its "
                        + "plugin instance (a field of the class extending JavaPlugin) or in PersistentData, so a "
                        + "reload starts clean; remove @StaticState and move the field there.");
            }
            String reason = reason(exemption);
            if (reason.isBlank()) {
                return describe(tree, "@StaticState needs a reason that says why this state must be static and "
                        + "what clears it, e.g. @StaticState(reason = \"cleared in the server-stopped hook\").");
            }
            return Description.NO_MATCH;
        }
        return describe(tree, "Static field '" + sym.getSimpleName() + "' " + problem + ". Static state outlives a "
                + "plugin reload: the old class loader stays alive with stale data, or the new one starts empty, and "
                + "it only shows several reloads later. Keep it in a field of the plugin instance (or an object the "
                + "plugin owns), or in PersistentData if it must survive restarts. A constant is fine as static "
                + "final with an immutable value: List.of(...), Set.of(...), Map.of(...), a record, a String or a "
                + "primitive." + (isHadron(sym) ? " In Hadron's own code, if it must be static, annotate it "
                + "@StaticState(reason = \"...\") saying what clears it." : ""));
    }

    /** Why the field holds state, or null when it doesn't. */
    private static String problem(VarSymbol sym, ExpressionTree init, VisitorState state) {
        if ((sym.flags() & Flags.ENUM) != 0) return null;
        if ((sym.flags() & Flags.FINAL) == 0) return "isn't final, so it can be reassigned";
        Type type = sym.type;
        if (type instanceof Type.ArrayType) return "holds an array, whose elements can be changed";
        for (String name : MUTABLE) {
            Type mutable = state.getTypeFromString(name);
            if (mutable != null && ASTHelpers.isSubtype(type, mutable, state)) {
                if (immutableValue(type, init, state)) return null;
                return "holds a " + state.getTypes().erasure(type).tsym.getSimpleName() + ", which can be changed";
            }
        }
        return null;
    }

    /** Whether the field's type or initializer guarantees an unmodifiable collection. */
    private static boolean immutableValue(Type type, ExpressionTree init, VisitorState state) {
        String typeName = state.getTypes().erasure(type).tsym.getQualifiedName().toString();
        if (typeName.startsWith("com.google.common.collect.Immutable")) return true;
        if (!(init instanceof MethodInvocationTree call)) return false;
        MethodSymbol method = ASTHelpers.getSymbol(call);
        if (method == null) return false;
        String owner = method.owner.getQualifiedName().toString();
        String name = method.getSimpleName().toString();
        if (owner.equals("java.util.EnumSet")) return false; // EnumSet.of is mutable
        if (IMMUTABLE_OWNERS.contains(owner) && method.isStatic()
                && (name.equals("of") || name.equals("copyOf") || name.equals("ofEntries") || name.equals("entry"))) {
            return true;
        }
        if (owner.equals("java.util.Collections") && COLLECTIONS_IMMUTABLE.contains(name)) return true;
        // stream.toList() is unmodifiable; Collectors.toList() isn't.
        if (owner.equals("java.util.stream.Stream") && name.equals("toList")) return true;
        Type returned = method.getReturnType();
        return returned != null && state.getTypes().erasure(returned).tsym.getQualifiedName().toString()
                .startsWith("com.google.common.collect.Immutable");
    }

    private Description describe(VariableTree tree, String message) {
        return buildDescription(tree).setMessage(message).build();
    }

    static boolean isHadron(Symbol sym) {
        String pkg = packageOf(sym);
        return pkg.equals(HADRON) || pkg.startsWith(HADRON + ".");
    }

    static String packageOf(Symbol sym) {
        Symbol.PackageSymbol pkg = sym.packge();
        return pkg == null ? "" : pkg.getQualifiedName().toString();
    }

    static Attribute.Compound exemption(Symbol sym) {
        return exemption(sym, EXEMPTION);
    }

    static Attribute.Compound exemption(Symbol sym, String annotation) {
        for (Attribute.Compound a : sym.getAnnotationMirrors()) {
            if (a.type.tsym.getQualifiedName().contentEquals(annotation)) return a;
        }
        return null;
    }

    static String reason(Attribute.Compound a) {
        for (var e : a.getElementValues().entrySet()) {
            if (e.getKey().getSimpleName().contentEquals("reason") && e.getValue().getValue() instanceof String s) {
                return s;
            }
        }
        return "";
    }
}
