package dev.iredsc.hadron.checks;

import static com.google.errorprone.BugPattern.SeverityLevel.ERROR;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.VariableTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type;
import java.util.List;
import javax.inject.Inject;
import javax.lang.model.element.ElementKind;
import javax.tools.JavaFileObject;

/**
 * Refuses the common ways to keep state in a static field: one that isn't final, or a final one holding a mutable
 * kind of value (a java.util collection or map that isn't an unmodifiable form, a non-empty array, an atomic,
 * ThreadLocal, ClassValue, StringBuilder, or a constant of this build's enum that keeps state). A plugin's classes
 * are reloaded with a new class loader, so static state either outlives the reload (keeping the old class loader
 * alive) or silently starts empty, and the plugin looks fine until a later reload.
 *
 * <p>It never refuses valid code, and it isn't a proof: any other value passes, so other ways to hide state (a
 * plugin's own class with a mutable field, a mutable object inside {@code List.of(...)}) aren't caught.
 */
@AutoService(BugChecker.class)
@BugPattern(
        summary = "Static mutable state survives or silently resets on a plugin reload; keep it on an instance",
        severity = ERROR,
        suppressionAnnotations = {})
public final class NoStaticMutableState extends BugChecker implements VariableTreeMatcher {
    static final String EXEMPTION = "dev.iredsc.hadron.checks.StaticState";
    /** Set only for Hadron's own modules, which may grant a reasoned exemption; plugins can't. */
    static final String ALLOW_EXEMPTIONS = "Hadron:AllowExemptions";

    private final boolean exemptions;

    /** For ServiceLoader, which finds the check; Error Prone itself makes it with its flags, below. */
    public NoStaticMutableState() {
        this(ErrorProneFlags.empty());
    }

    @Inject
    public NoStaticMutableState(ErrorProneFlags flags) {
        exemptions = flags.getBoolean(ALLOW_EXEMPTIONS).orElse(false);
    }

    /** Mutable however they're built. */
    private static final List<String> MUTABLE = List.of("java.lang.ThreadLocal", "java.lang.ClassValue",
            "java.lang.StringBuilder", "java.lang.StringBuffer");

    /** Mutable unless built by one of {@link #unmodifiable}'s factories. */
    private static final List<String> COLLECTIONS = List.of("java.util.Collection", "java.util.Map");

    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
        VarSymbol sym = ASTHelpers.getSymbol(tree);
        if (sym == null || !sym.isStatic()
                || (sym.getKind() != ElementKind.FIELD && sym.getKind() != ElementKind.ENUM_CONSTANT)) {
            return Description.NO_MATCH;
        }
        // Mixin @Shadow fields are Minecraft's, declared again only so the mixin can name them.
        if (ASTHelpers.hasAnnotation(sym, "org.spongepowered.asm.mixin.Shadow", state)) return Description.NO_MATCH;

        String problem = (sym.flags() & Flags.FINAL) == 0
                ? "isn't final, so it can be reassigned"
                : held(sym.type, tree.getInitializer(), state);
        if (problem == null) return Description.NO_MATCH;

        // On a class, it exempts the static fields the class declares.
        Attribute.Compound exemption = exemption(sym) != null ? exemption(sym) : exemption(sym.owner);
        if (exemption != null) {
            if (!exemptions) {
                return describe(tree, "@StaticState is only for Hadron's own code. A plugin keeps state on its "
                        + "plugin instance (a field of the class extending JavaPlugin) or in PersistentData, so a "
                        + "reload starts clean; remove @StaticState and move the field there.");
            }
            if (reason(exemption).isBlank()) {
                return describe(tree, "@StaticState needs a reason that says why this state must be static and "
                        + "what clears it, e.g. @StaticState(reason = \"cleared in the server-stopped hook\").");
            }
            return Description.NO_MATCH;
        }
        return describe(tree, "Static field '" + sym.getSimpleName() + "' " + problem + ". Static state outlives a "
                + "plugin reload: the old class loader stays alive with stale data, or the new one starts empty, and "
                + "it only shows several reloads later. Keep it in a field of the plugin instance (or an object the "
                + "plugin owns), or in PersistentData if it must survive restarts. A constant collection is fine "
                + "as List.of(...), Set.of(...), Map.of(...) or List.copyOf(...)."
                + (exemptions ? " In Hadron's own code, if it must be static, annotate it "
                        + "@StaticState(reason = \"...\") saying what clears it." : ""));
    }

    /** Why a final field of this type and initializer holds state, or null when it isn't a known-mutable kind. */
    private String held(Type declared, ExpressionTree init, VisitorState state) {
        if (init != null && init.getKind() == Tree.Kind.NULL_LITERAL) return null;
        Type type = init == null || ASTHelpers.getType(init) == null ? declared : ASTHelpers.getType(init);
        if (declared instanceof Type.ArrayType || type instanceof Type.ArrayType) {
            return emptyArray(init) ? null : "holds an array, whose elements can be changed";
        }
        for (String name : MUTABLE) {
            if (isSubtype(type, name, state)) return "holds a " + name(type, state) + ", which is state";
        }
        for (Type t = type; t != null && t.tsym != null; t = state.getTypes().supertype(t)) {
            if (t.tsym.getQualifiedName().toString().startsWith("java.util.concurrent.atomic.")) {
                return "holds a " + name(type, state) + ", which is state";
            }
        }
        for (String name : COLLECTIONS) {
            if (isSubtype(type, name, state) && !unmodifiable(type, init, state)) {
                return "holds a " + name(type, state) + ", which can be changed";
            }
        }
        // An enum constant of this build's enum, whose instance fields are its state: the singleton pattern.
        if (!(state.getTypes().erasure(type).tsym instanceof Symbol.ClassSymbol cls)) return null;
        if (cls.isAnonymous() && init instanceof NewClassTree created && created.getClassBody() != null) {
            if (!(cls.getSuperclass().tsym instanceof Symbol.ClassSymbol parent)
                    || (parent.flags() & Flags.ENUM) == 0) {
                return null;
            }
            if (statefulFields(created.getClassBody(), state)) {
                return "holds a " + name(type, state) + " whose body has a field that holds state";
            }
            cls = parent;
        }
        if ((cls.flags() & Flags.ENUM) == 0 || !fromSource(cls) || vouched(cls)) return null;
        return JavacTrees.instance(state.context).getTree(cls) instanceof ClassTree body
                && statefulFields(body, state)
                ? "holds a " + name(type, state) + ", an enum with a field that holds state" : null;
    }

    /** In Hadron's own code, an enum annotated @StaticState(reason = ...) vouches for its constants. */
    private boolean vouched(Symbol.ClassSymbol cls) {
        Attribute.Compound a = exemption(cls);
        return a != null && exemptions && !reason(a).isBlank();
    }

    /** Whether the class body declares an instance field that holds state. */
    private boolean statefulFields(ClassTree body, VisitorState state) {
        for (Tree member : body.getMembers()) {
            if (member instanceof VariableTree field && ASTHelpers.getSymbol(field) instanceof VarSymbol v
                    && !v.isStatic() && stateful(v, field.getInitializer(), state)) {
                return true;
            }
        }
        return false;
    }

    /** A field set only in a constructor is judged by its declared type, so List<String> there passes. */
    private boolean stateful(VarSymbol field, ExpressionTree init, VisitorState state) {
        if ((field.flags() & Flags.FINAL) == 0) return true;
        Symbol.TypeSymbol tsym = state.getTypes().erasure(field.type).tsym;
        if (init == null && tsym instanceof Symbol.ClassSymbol cls && cls.isInterface()) return false;
        if (tsym instanceof Symbol.ClassSymbol cls && (cls.flags() & Flags.ENUM) != 0) return false;
        return held(field.type, init, state) != null;
    }

    /** List.of, Set.copyOf, Map.ofEntries, Collections.unmodifiable*, empty* and singleton*, stream().toList(). */
    private static boolean unmodifiable(Type type, ExpressionTree init, VisitorState state) {
        if (state.getTypes().erasure(type).tsym.getQualifiedName().toString()
                .startsWith("com.google.common.collect.Immutable")) {
            return true;
        }
        if (!(init instanceof MethodInvocationTree call) || !(ASTHelpers.getSymbol(call) instanceof MethodSymbol m)) {
            return false;
        }
        String owner = m.owner.getQualifiedName().toString();
        String name = m.getSimpleName().toString();
        return switch (owner) {
            case "java.util.List", "java.util.Set", "java.util.Map" -> name.startsWith("of") || name.equals("copyOf");
            case "java.util.Collections" -> name.startsWith("unmodifiable") || name.startsWith("empty")
                    || name.startsWith("singleton") || name.equals("nCopies");
            case "java.util.stream.Stream" -> name.equals("toList");
            default -> false;
        };
    }

    /** The type's simple name, or for an anonymous class, the name of what it extends or implements. */
    private static String name(Type type, VisitorState state) {
        Symbol.TypeSymbol tsym = state.getTypes().erasure(type).tsym;
        if (tsym instanceof Symbol.ClassSymbol cls && cls.isAnonymous()) {
            Type parent = cls.getInterfaces().isEmpty() ? cls.getSuperclass() : cls.getInterfaces().head;
            return state.getTypes().erasure(parent).tsym.getSimpleName().toString();
        }
        return tsym.getSimpleName().toString();
    }

    private static boolean isSubtype(Type type, String name, VisitorState state) {
        Type other = state.getTypeFromString(name);
        return other != null && ASTHelpers.isSubtype(type, other, state);
    }

    /** Compiled from source in this build, so reloaded with the plugin, as opposed to read from a library. */
    private static boolean fromSource(Symbol.ClassSymbol cls) {
        return cls.classfile != null && cls.classfile.getKind() == JavaFileObject.Kind.SOURCE;
    }

    /** new T[0] or {}: an array with no elements has nothing to change. */
    private static boolean emptyArray(ExpressionTree init) {
        if (!(init instanceof NewArrayTree array)) return false;
        if (array.getInitializers() != null) return array.getInitializers().isEmpty();
        return array.getDimensions().size() == 1 && array.getDimensions().get(0) instanceof LiteralTree size
                && Integer.valueOf(0).equals(size.getValue());
    }

    private Description describe(VariableTree tree, String message) {
        return buildDescription(tree).setMessage(message).build();
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
