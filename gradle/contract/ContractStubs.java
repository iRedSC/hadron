import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Writes contract stubs of Hadron's api and test-api: every public and protected type, constructor, method and field,
 * with its Javadoc, annotations and generics as written, and every body replaced by a throw. Part of contractBundle
 * (gradle/hadron-contract.gradle.kts); ContractLeakCheck checks what this writes, with its own reading of the rules.
 *
 * <p>Usage: {@code java --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
 * --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED ContractStubs.java <out> <name>=<srcDir>...}
 *
 * <p>The rules, kept in step with docs/testing.md "Blind tests":
 * <ul>
 * <li>Kept: top-level public types; nested public or protected types (every nested type of an interface or annotation
 *     that isn't private); in those, public and protected constructors, methods and fields, and every non-private member
 *     of an interface or annotation; enum constants.
 * <li>Left out: private and package-private members and types, initializer blocks, and the port-only types named in
 *     {@link #PORT_TYPES} with everything they nest (the same names coverageCheck skips as wiring plugins never use).
 * <li>Bodies: every method and constructor body becomes {@link #THROW}; abstract methods and annotation elements
 *     (with their defaults) are kept as written. Enum constants lose their arguments and bodies.
 * <li>Fields keep their initializer only when it is a constant: literals, operators on them, casts to a primitive or
 *     String, and references to UPPER_CASE constants. Any other initializer becomes a stand-in ({@code null}, 0 or
 *     false) behind {@link #NOT_CONTRACT}, so the stub still parses without showing the value.
 * <li>A class that declares constructors, none of them public or protected, gets a comment saying so, so it doesn't
 *     read as having a default public constructor.
 * <li>A kept signature that names a left-out type is an error, so nothing dangles silently.
 * </ul>
 */
public final class ContractStubs {
    static final String THROW = "throw new UnsupportedOperationException(\"contract stub\");";
    static final Set<String> PORT_TYPES = Set.of("Port", "Lifecycle", "Backend", "NetBackend", "Memory", "PluginContext", "HostKey");
    static final Pattern CONSTANT_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    static final String NOT_CONTRACT = "/* value not part of the contract; see the Javadoc */";
    static final Set<String> BOXED =Set.of("Byte", "Short", "Integer", "Long", "Float", "Double", "Character");
    static final Pattern IDENT =Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    final List<String> errors = new ArrayList<>();
    String src;
    SourcePositions positions;
    CompilationUnitTree unit;
    String file;
    /** Names of left-out types a kept signature must not mention. */
    final Set<String> leftOut = new TreeSet<>(PORT_TYPES);

    public static void main(String[] args) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("usage: ContractStubs <out> <name>=<srcDir>...");
        Path out = Path.of(args[0]);
        ContractStubs stubs = new ContractStubs();
        int written = 0;
        for (int i = 1; i < args.length; i++) {
            String[] pair = args[i].split("=", 2);
            written += stubs.module(Path.of(pair[1]), out.resolve(pair[0]));
        }
        if (!stubs.errors.isEmpty()) {
            System.err.println("contractBundle can't write the stubs:");
            stubs.errors.forEach(e -> System.err.println("  " + e));
            System.exit(1);
        }
        System.out.println("Contract stubs: " + written + " files in " + out);
    }

    int module(Path srcDir, Path outDir) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(srcDir)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        JavacTask task = (JavacTask) javac.getTask(null, fm, diagnostics, List.of("-proc:none"), null,
                fm.getJavaFileObjectsFromPaths(files));
        List<CompilationUnitTree> units = new ArrayList<>();
        task.parse().forEach(units::add);
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) errors.add(d.toString());
        }
        positions = Trees.instance(task).getSourcePositions();
        // Package-private top-level types are left out; a kept signature naming one would dangle.
        for (CompilationUnitTree u : units) {
            for (Tree t : u.getTypeDecls()) {
                if (t instanceof ClassTree c && !c.getModifiers().getFlags().contains(Modifier.PUBLIC)) {
                    leftOut.add(c.getSimpleName().toString());
                }
            }
        }
        int written = 0;
        for (CompilationUnitTree u : units) {
            unit = u;
            src = u.getSourceFile().getCharContent(true).toString();
            file = srcDir.toAbsolutePath().relativize(Path.of(u.getSourceFile().toUri())).toString();
            String stub = unit();
            if (stub == null) continue;
            Path target = outDir.resolve(file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, stub);
            written++;
        }
        return written;
    }

    long start(Tree t) { return positions.getStartPosition(unit, t); }
    long end(Tree t) {
        long e = positions.getEndPosition(unit, t);
        if (e < 0) throw new IllegalStateException("no end position for " + t.getKind() + " in " + file);
        return e;
    }
    String text(Tree t) { return src.substring((int) start(t), (int) end(t)); }

    /** The stub of one file, or null when it holds no kept type. */
    String unit() {
        StringBuilder types = new StringBuilder();
        for (Tree t : unit.getTypeDecls()) {
            if (t instanceof ClassTree c && keptType(c, null)) {
                types.append('\n');
                type(c, types);
            }
        }
        if (types.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        out.append("package ").append(unit.getPackageName()).append(";\n");
        boolean any = false;
        // Imports the stub still uses (Javadoc's {@link}s count), so ones only bodies needed don't hint at them.
        Set<String> used = new TreeSet<>();
        Matcher words = IDENT.matcher(types);
        while (words.find()) used.add(words.group());
        for (ImportTree imp : unit.getImports()) {
            String name = imp.getQualifiedIdentifier().toString();
            String last = name.substring(name.lastIndexOf('.') + 1);
            boolean namesLeftOut = false;
            for (String part : name.split("\\.")) if (PORT_TYPES.contains(part)) namesLeftOut = true;
            if (namesLeftOut || leftOut.contains(last) && !imp.isStatic()) continue;
            if (!last.equals("*") && !used.contains(last)) continue;
            if (!any) out.append('\n');
            any = true;
            out.append(text(imp)).append('\n');
        }
        return out.append(types).toString();
    }

    boolean keptType(ClassTree c, ClassTree outer) {
        if (PORT_TYPES.contains(c.getSimpleName().toString())) return false;
        Set<Modifier> flags = c.getModifiers().getFlags();
        if (flags.contains(Modifier.PRIVATE)) return false;
        if (outer == null) return flags.contains(Modifier.PUBLIC);
        return flags.contains(Modifier.PUBLIC) || flags.contains(Modifier.PROTECTED) || isInterface(outer);
    }

    static boolean isInterface(ClassTree c) {
        return c.getKind() == Tree.Kind.INTERFACE || c.getKind() == Tree.Kind.ANNOTATION_TYPE;
    }

    boolean keptMember(Tree m, ClassTree owner) {
        Set<Modifier> flags = switch (m) {
            case MethodTree mt -> mt.getModifiers().getFlags();
            case VariableTree v -> v.getModifiers().getFlags();
            default -> Set.of();
        };
        if (flags.contains(Modifier.PRIVATE)) return false;
        return flags.contains(Modifier.PUBLIC) || flags.contains(Modifier.PROTECTED) || isInterface(owner);
    }

    /** The whitespace a member's line starts with. */
    String indentOf(long pos) {
        int line = src.lastIndexOf('\n', (int) pos - 1) + 1;
        int i = line;
        while (i < pos && (src.charAt(i) == ' ' || src.charAt(i) == '\t')) i++;
        return src.substring(line, i);
    }

    /** The Javadoc comment right before a declaration, with its indent, or "". */
    String javadoc(Tree t) {
        int i = (int) start(t) - 1;
        while (i >= 0 && Character.isWhitespace(src.charAt(i))) i--;
        if (i < 1 || src.charAt(i) != '/' || src.charAt(i - 1) != '*') return "";
        int open = src.lastIndexOf("/*", i - 1);
        if (open < 0 || !src.startsWith("/**", open) || open + 2 >= i - 1) return "";
        return indentOf(open) + src.substring(open, i + 1) + "\n";
    }

    /** Fails when kept declaration text names a left-out type (comments and strings aside). */
    void checkNames(String declaration, String what) {
        Matcher m = IDENT.matcher(stripCommentsAndStrings(declaration));
        while (m.find()) {
            if (leftOut.contains(m.group())) {
                errors.add(file + ": " + what + " names " + m.group() + ", which the contract leaves out. Leave this "
                        + "member out too (make it non-public, or name it in PORT_TYPES if it is port wiring), or "
                        + "make " + m.group() + " public API.");
            }
        }
    }

    static String stripCommentsAndStrings(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ")
                .replaceAll("(?s)\"\"\".*?\"\"\"", "\"\"").replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"")
                .replaceAll("'(\\\\.|[^'\\\\])*'", "' '");
    }

    /** Index of the '{' opening a class body: the first at parenthesis depth 0, outside comments and literals. */
    int bodyOpen(ClassTree c) {
        int depth = 0;
        for (int i = (int) start(c); i < src.length(); i++) {
            char ch = src.charAt(i);
            if (src.startsWith("//", i)) i = src.indexOf('\n', i);
            else if (src.startsWith("/*", i)) i = src.indexOf("*/", i + 2) + 1;
            else if (src.startsWith("\"\"\"", i)) i = src.indexOf("\"\"\"", i + 3) + 2;
            else if (ch == '"' || ch == '\'') {
                i++;
                while (src.charAt(i) != ch) i += src.charAt(i) == '\\' ? 2 : 1;
            } else if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (ch == '{' && depth == 0) return i;
        }
        throw new IllegalStateException("no class body in " + file);
    }

    void type(ClassTree c, StringBuilder out) {
        String indent = indentOf(start(c));
        String name = c.getSimpleName().toString();
        out.append(javadoc(c));
        int open = bodyOpen(c);
        String header = src.substring((int) start(c), open).stripTrailing();
        checkNames(header.replaceFirst("\\b(class|interface|enum|record)\\s+" + Pattern.quote(name) + "\\b", ""),
                "the header of " + name);
        out.append(indent).append(header).append(" {\n");
        String inner = indent + "    ";
        boolean isEnum = c.getKind() == Tree.Kind.ENUM;
        boolean anyConstructor = false;
        boolean keptConstructor = false;
        List<String> members = new ArrayList<>();
        List<String> constants = new ArrayList<>();
        long lastFieldStart = -1;
        for (Tree m : c.getMembers()) {
            long flags = m instanceof JCTree.JCVariableDecl v ? v.mods.flags
                    : m instanceof JCTree.JCMethodDecl md ? md.mods.flags : 0;
            if ((flags & Flags.GENERATED_MEMBER) != 0) continue; // a record's component fields, made by the parser
            if (m instanceof VariableTree v && (flags & Flags.ENUM) != 0) {
                // An enum constant: its Javadoc and annotations, never its arguments or body.
                StringBuilder constant = new StringBuilder(javadoc(v)).append(inner);
                v.getModifiers().getAnnotations().forEach(a -> constant.append(text(a)).append(' '));
                constants.add(constant.append(v.getName()).toString());
                continue;
            }
            switch (m) {
                case ClassTree nested -> {
                    if (keptType(nested, c)) {
                        StringBuilder sb = new StringBuilder();
                        type(nested, sb);
                        members.add(sb.toString());
                    }
                }
                case MethodTree mt -> {
                    boolean constructor = mt.getName().contentEquals("<init>");
                    if (constructor) anyConstructor = true;
                    if (isEnum && constructor) continue;
                    if (!keptMember(mt, c)) continue;
                    if (constructor) keptConstructor = true;
                    members.add(method(mt));
                }
                case VariableTree v -> {
                    if (start(v) == lastFieldStart) {
                        errors.add(file + ": " + v.getName() + " shares a declaration with another field; declare each "
                                + "field on its own so its stub can't take the other's initializer.");
                    }
                    lastFieldStart = start(v);
                    if (keptMember(v, c)) members.add(field(v, c));
                }
                default -> { } // initializer blocks: implementation
            }
        }
        if (isEnum) {
            out.append(String.join(",\n", constants)).append(";\n");
            if (!members.isEmpty()) out.append('\n');
        }
        if (anyConstructor && !keptConstructor && !isEnum && c.getKind() == Tree.Kind.CLASS) {
            members.add(0, inner + "// No public or protected constructor.\n");
        }
        out.append(String.join("\n", members));
        out.append(indent).append("}\n");
    }

    String method(MethodTree m) {
        String indent = indentOf(start(m));
        String what = m.getName().contentEquals("<init>") ? "a constructor" : m.getName() + "()";
        if (m.getBody() == null) {
            String decl = text(m);
            checkNames(decl, what);
            return javadoc(m) + indent + decl + "\n";
        }
        String signature = src.substring((int) start(m), (int) start(m.getBody())).stripTrailing();
        checkNames(signature, what);
        return javadoc(m) + indent + signature + " {\n" + indent + "    " + THROW + "\n" + indent + "}\n";
    }

    String field(VariableTree v, ClassTree owner) {
        String indent = indentOf(start(v));
        ExpressionTree init = v.getInitializer();
        boolean isFinal = v.getModifiers().getFlags().contains(Modifier.FINAL) || isInterface(owner);
        String decl;
        if (init == null) {
            decl = text(v);
        } else if (isFinal && constant(init)) {
            decl = src.substring((int) start(v), (int) end(init)) + ";";
        } else {
            String head = src.substring((int) start(v), (int) start(init)).stripTrailing();
            if (!head.endsWith("=")) throw new IllegalStateException("field initializer without '=' in " + file);
            // A stand-in, marked, so the stub still parses (an interface field needs an initializer).
            String standIn = switch (v.getType().toString()) {
                case "boolean" -> "false";
                case "char" -> "'\\0'";
                case "byte", "short", "int", "long", "float", "double" -> "0";
                default -> "null";
            };
            decl = head + " " + NOT_CONTRACT + " " + standIn + ";";
        }
        checkNames(decl.contains("=") ? decl.substring(0, decl.indexOf('=')) : decl, v.getName().toString());
        return javadoc(v) + indent + decl + "\n";
    }

    /** Whether an initializer is a constant whose value is contract (see the class comment). */
    static boolean constant(ExpressionTree e) {
        return switch (e.getKind()) {
            case INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL, BOOLEAN_LITERAL, CHAR_LITERAL,
                 STRING_LITERAL -> true;
            case PARENTHESIZED -> constant(((com.sun.source.tree.ParenthesizedTree) e).getExpression());
            case UNARY_PLUS, UNARY_MINUS, BITWISE_COMPLEMENT, LOGICAL_COMPLEMENT ->
                    constant(((com.sun.source.tree.UnaryTree) e).getExpression());
            case CONDITIONAL_EXPRESSION -> {
                var c = (com.sun.source.tree.ConditionalExpressionTree) e;
                yield constant(c.getCondition()) && constant(c.getTrueExpression()) && constant(c.getFalseExpression());
            }
            case TYPE_CAST -> {
                TypeCastTree cast = (TypeCastTree) e;
                String type = cast.getType().toString();
                yield (cast.getType().getKind() == Tree.Kind.PRIMITIVE_TYPE || type.equals("String"))
                        && constant(cast.getExpression());
            }
            case IDENTIFIER -> CONSTANT_NAME.matcher(((IdentifierTree) e).getName()).matches();
            case MEMBER_SELECT -> {
                MemberSelectTree s = (MemberSelectTree) e;
                // Only the boxed types' limits (Integer.MAX_VALUE): another class's constant may be one this leaves out.
                yield CONSTANT_NAME.matcher(s.getIdentifier()).matches()
                        && BOXED.contains(s.getExpression().toString());
            }
            default -> e instanceof com.sun.source.tree.BinaryTree b
                    && constant(b.getLeftOperand()) && constant(b.getRightOperand());
        };
    }
}
