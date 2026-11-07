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
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type;
import java.util.List;
import javax.inject.Inject;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.tools.JavaFileObject;

/**
 * Refuses a static field that holds state: one that isn't final, or is final but holds anything not known to be
 * immutable (a collection, an array, a ThreadLocal, an object with a field that can change). A plugin's classes are reloaded with a new class loader, so static state either outlives the
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

    /**
     * Types whose instances never change once built. Anything else might, so a static final field of another type is
     * refused unless its initializer is known to build an unmodifiable value ({@link #immutableValue}).
     */
    private static final Set<String> IMMUTABLE = Set.of(
            "java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Character", "java.lang.Short",
            "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Class",
            "java.util.regex.Pattern", "java.lang.StackWalker",
            // A logger is looked up by name from the logging system's own registry, so it holds nothing of the
            // class that keeps it and pins no class loader.
            "org.slf4j.Logger", "org.apache.logging.log4j.Logger",
            // Built once and never changed; Minecraft's codecs are made of stateless functions by design.
            "com.google.gson.Gson", "com.mojang.serialization.Codec", "com.mojang.serialization.MapCodec",
            "net.minecraft.network.codec.StreamCodec",
            // Minecraft's block position; its mutable form is BlockPos.MutableBlockPos, a different class.
            "net.minecraft.core.BlockPos");

    /** Types whose fields look immutable but whose value is kept elsewhere, per thread or per class. */
    private static final List<String> THREAD_LOCAL = List.of("java.lang.ThreadLocal", "java.lang.ClassValue");

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
            if (!exemptions) {
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
                + "final with an immutable value: List.of(...), Set.of(...), Map.of(...), a String, a primitive, an "
                + "enum, or a record or final class whose fields are all final and immutable too." + (exemptions ? " In Hadron's own code, if it must be static, annotate it "
                + "@StaticState(reason = \"...\") saying what clears it." : ""));
    }

    /** Why the field holds state, or null when it doesn't. */
    private String problem(VarSymbol sym, ExpressionTree init, VisitorState state) {
        if ((sym.flags() & Flags.ENUM) != 0) return null;
        if ((sym.flags() & Flags.FINAL) == 0) return "isn't final, so it can be reassigned";
        Type type = sym.type;
        if (type instanceof Type.ArrayType) {
            return emptyArray(init) ? null : "holds an array, whose elements can be changed";
        }
        // A lambda or a Type::method made in a static initializer captures nothing but other static fields, which
        // are checked where they're declared.
        if (init instanceof LambdaExpressionTree) return null;
        if (init instanceof MemberReferenceTree ref && ASTHelpers.getSymbol(ref.getQualifierExpression())
                instanceof Symbol.TypeSymbol) {
            return null;
        }
        // Another static final field's value, compiled from source with this check, so checked where it's declared.
        if (init != null && ASTHelpers.getSymbol(init) instanceof VarSymbol other && other.isStatic()
                && (other.flags() & Flags.FINAL) != 0 && other.getKind() == ElementKind.FIELD
                && other.enclClass().classfile != null
                && other.enclClass().classfile.getKind() == JavaFileObject.Kind.SOURCE) {
            return null;
        }
        // The value's own type, when the initializer says more than the field's declared type: new Foo(...) holds
        // exactly a Foo, and SomeClass.CONSTANT holds that constant's type.
        Type held = init == null || ASTHelpers.getType(init) == null ? type : ASTHelpers.getType(init);
        for (String name : THREAD_LOCAL) {
            if (ASTHelpers.isSubtype(held, state.getTypeFromString(name), state)) {
                return "holds a " + name(held, state) + ", whose value per "
                        + "thread or class is state";
            }
        }
        if (knownImmutable(held, init instanceof NewClassTree, state, 0) || immutableValue(type, init, state)) {
            return null;
        }
        return "holds a " + name(held, state) + ", which isn't known to be immutable";
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

    /**
     * Primitives, {@link #IMMUTABLE}, enums, records of known immutable components, and classes whose every
     * instance field (theirs and their superclasses') is final and itself known immutable, as far down as it goes.
     * The class must be final unless {@code exact}, when {@code new} made exactly it. A type variable or an interface
     * could be anything, so it isn't. In Hadron's own code, a class annotated {@code @StaticState(reason = ...)}
     * vouches for its instances.
     */
    private boolean knownImmutable(Type type, boolean exact, VisitorState state, int depth) {
        if (type.isPrimitive()) return true;
        if (depth > 8) return false;
        Symbol.TypeSymbol tsym = state.getTypes().erasure(type).tsym;
        if (IMMUTABLE.contains(tsym.getQualifiedName().toString())) return true;
        if (!(tsym instanceof Symbol.ClassSymbol cls)) return false;
        if ((cls.flags() & Flags.ENUM) != 0) return true;
        Attribute.Compound vouched = exemption(cls);
        if (vouched != null && exemptions && !reason(vouched).isBlank()) return true;
        if (cls.isRecord()) {
            for (Symbol.RecordComponent component : cls.getRecordComponents()) {
                if (!knownImmutable(component.type, false, state, depth + 1)) return false;
            }
            return true;
        }
        if (cls.isInterface() || (!exact && (cls.flags() & Flags.FINAL) == 0)) return false;
        Type superclass = cls.getSuperclass();
        if (superclass.tsym != null && !superclass.tsym.getQualifiedName().contentEquals("java.lang.Object")
                && !knownImmutable(superclass, true, state, depth + 1)) {
            return false;
        }
        for (Symbol member : cls.getEnclosedElements()) {
            if (!(member instanceof VarSymbol field) || field.isStatic()) continue;
            if ((field.flags() & Flags.FINAL) == 0 || !knownImmutable(field.type, false, state, depth + 1)) {
                return false;
            }
        }
        return true;
    }

    /** new T[0] or {}: an array with no elements has nothing to change. */
    private static boolean emptyArray(ExpressionTree init) {
        if (!(init instanceof NewArrayTree array)) return false;
        if (array.getInitializers() != null) return array.getInitializers().isEmpty();
        return array.getDimensions().size() == 1 && array.getDimensions().get(0) instanceof LiteralTree size
                && Integer.valueOf(0).equals(size.getValue());
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
