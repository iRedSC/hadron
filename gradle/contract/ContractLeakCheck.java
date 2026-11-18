import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * Fails unless a contract folder holds only contract: run by contractBundle before it assembles the bundle. Written
 * apart from ContractStubs, with its own reading of the rules, so a bug in one doesn't hide a leak from the other.
 *
 * <p>Usage: {@code java --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
 * --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED ContractLeakCheck.java <contractDir> <implNames>
 * <forbidden> [<extra path>...]}, where implNames lists every top-level class name in common and neoforge, one a
 * line; forbidden lists doc rules as {@code <doc>\t<text>} lines ({@code *} for every doc): text that must not appear
 * there; and extra paths are other files the blind agent reads (the README, the example tests), scanned for names.
 *
 * <p>It fails on: a file other than {@code src/**.java} or {@code docs/*.md}; a Java file that doesn't parse, or is
 * outside Hadron's api and test packages; a private member or type; a package-private top-level type, or a
 * package-private member of a class; an initializer block; a method or constructor body other than the one stub
 * throw; a field initializer that isn't a constant; an enum constant with arguments or a body; a port-only type;
 * anywhere in the folder, an implementation class name (whole word, case-sensitive) or "mixin" (any case); and any
 * forbidden doc text.
 */
public final class ContractLeakCheck {
    static final String STUB_THROW = "thrownewUnsupportedOperationException(\"contractstub\");";
    static final Set<String> PORT_TYPES = Set.of("Port", "Lifecycle", "Backend", "NetBackend", "Memory", "PluginContext", "HostKey");
    static final Set<String> BOXED = Set.of("Byte", "Short", "Integer", "Long", "Float", "Double", "Character");
    /** English uses of a word that is also an implementation class name; reviewed, exact phrases only. */
    static final List<String> ENGLISH = List.of("Platform APIs");
    /**
     * The only binary files the bundle's test project may hold, relative to it: Hadron's jars, which are its real
     * implementation by design, and Gradle's wrapper. Anything else that isn't text fails the check.
     */
    static final Set<String> SHIPPED_BINARIES = Set.of("libs/hadron.jar", "libs/hadron-test.jar", "libs/hadron-api.jar",
            "libs/hadron-test-api.jar", "gradle/wrapper/gradle-wrapper.jar");

    final List<String> problems = new ArrayList<>();
    String file;

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]);
        List<String> implNames = Files.readAllLines(Path.of(args[1])).stream().map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
        if (implNames.size() < 20) {
            throw new IllegalStateException("Only " + implNames.size() + " implementation class names were given; the "
                    + "scan would be too weak to mean anything. Check how contractBundle lists common and neoforge.");
        }
        List<String[]> forbidden = Files.readAllLines(Path.of(args[2])).stream().filter(l -> !l.isBlank())
                .map(l -> l.split("\t", 2)).toList();
        ContractLeakCheck check = new ContractLeakCheck();
        check.run(root, implNames, forbidden);
        // Everything else in the bundle (the README, the test project and its build files): no implementation names.
        for (int i = 3; i < args.length; i++) {
            Path base = Path.of(args[i]);
            try (Stream<Path> walk = Files.walk(base)) {
                for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String rel = (Files.isDirectory(base) ? base.relativize(p) : p.getFileName()).toString()
                            .replace('\\', '/');
                    if (SHIPPED_BINARIES.contains(rel)) continue;
                    String text;
                    try {
                        text = Files.readString(p);
                    } catch (java.nio.charset.MalformedInputException e) {
                        check.problems.add(p + ": isn't UTF-8 text, so it can't be checked for leaks. Leave it out of "
                                + "the bundle, or, if it is reviewed, add it to SHIPPED_BINARIES in ContractLeakCheck.");
                        continue;
                    }
                    check.scan(p.toString(), text);
                }
            }
        }
        if (!check.problems.isEmpty()) {
            System.err.println("The contract leaks implementation (" + check.problems.size() + " problems):");
            check.problems.forEach(p -> System.err.println("  " + p));
            System.exit(1);
        }
        System.out.println("Contract leak check: clean.");
    }

    void run(Path root, List<String> implNames, List<String[]> forbidden) throws IOException {
        List<Path> all;
        try (Stream<Path> walk = Files.walk(root)) {
            all = walk.filter(Files::isRegularFile).sorted().toList();
        }
        List<Path> java = new ArrayList<>();
        int docs = 0;
        for (Path p : all) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (rel.startsWith("src/") && rel.endsWith(".java")) java.add(p);
            else if (rel.matches("docs/[^/]+\\.md")) docs++;
            else problems.add(rel + ": only src/**.java and docs/*.md belong in the contract.");
        }
        if (java.size() < 50 || docs == 0) {
            problems.add("The contract holds " + java.size() + " Java files and " + docs + " docs; it should hold all "
                    + "of api's and test-api's public types and the spec docs.");
        }
        impl = Pattern.compile("\\b(" + String.join("|", implNames.stream().map(Pattern::quote).toList())
                + ")\\b");
        for (Path p : all) {
            String rel = root.relativize(p).toString();
            String text = Files.readString(p);
            scan(rel, text);
            String name = p.getFileName().toString();
            for (String[] rule : forbidden) {
                if (rel.startsWith("docs/") && (rule[0].equals("*") || rule[0].equals(name)) && text.contains(rule[1])) {
                    problems.add(rel + ": still has \"" + rule[1] + "\", which the stripping rules remove.");
                }
            }
        }
        java(root, java);
    }

    Pattern impl;

    /** An implementation class name or a mixin anywhere in the text. */
    void scan(String where, String text) {
        String scanned = text;
        for (String phrase : ENGLISH) scanned = scanned.replace(phrase, "");
        var m = impl.matcher(scanned);
        while (m.find()) problems.add(where + ": names " + m.group() + ", a class in common or neoforge.");
        if (text.toLowerCase().contains("mixin")) problems.add(where + ": mentions a mixin.");
    }

    void java(Path root, List<Path> files) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        JavacTask task = (JavacTask) javac.getTask(null, fm, diagnostics, List.of("-proc:none"), null,
                fm.getJavaFileObjectsFromPaths(files));
        Iterable<? extends CompilationUnitTree> units = task.parse();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) problems.add("doesn't parse: " + d);
        }
        for (CompilationUnitTree unit : units) {
            file = root.toAbsolutePath().relativize(Path.of(unit.getSourceFile().toUri())).toString();
            String pkg = String.valueOf(unit.getPackageName());
            if (!pkg.equals("dev.iredsc.hadron.api") && !pkg.startsWith("dev.iredsc.hadron.api.")
                    && !pkg.equals("dev.iredsc.hadron.test") && !pkg.startsWith("dev.iredsc.hadron.test.")) {
                problems.add(file + ": package " + pkg + " is not Hadron's api or test-api.");
            }
            for (Tree t : unit.getTypeDecls()) {
                if (t instanceof ClassTree c) {
                    if (!c.getModifiers().getFlags().contains(Modifier.PUBLIC)) {
                        problems.add(file + ": top-level type " + c.getSimpleName() + " isn't public.");
                    }
                    type(c);
                } else if (t.getKind() != Tree.Kind.EMPTY_STATEMENT) {
                    problems.add(file + ": unexpected top-level " + t.getKind());
                }
            }
        }
    }

    void type(ClassTree c) {
        String name = c.getSimpleName().toString();
        if (PORT_TYPES.contains(name)) problems.add(file + ": " + name + " is port-only wiring.");
        boolean iface = c.getKind() == Tree.Kind.INTERFACE || c.getKind() == Tree.Kind.ANNOTATION_TYPE;
        for (Tree m : c.getMembers()) {
            String where = file + ": " + name + ".";
            switch (m) {
                case ClassTree nested -> {
                    access(nested.getModifiers().getFlags(), iface, where + nested.getSimpleName());
                    type(nested);
                }
                case MethodTree mt -> {
                    access(mt.getModifiers().getFlags(), iface, where + mt.getName());
                    BlockTree body = mt.getBody();
                    if (body != null && (body.getStatements().size() != 1
                            || !(body.getStatements().get(0) instanceof ThrowTree)
                            || !body.getStatements().get(0).toString().replaceAll("\\s", "").equals(STUB_THROW))) {
                        problems.add(where + mt.getName() + " has a body other than the stub throw: " + body);
                    }
                }
                case VariableTree v -> {
                    if (v.getInitializer() instanceof NewClassTree n && c.getKind() == Tree.Kind.ENUM
                            && n.getIdentifier().toString().equals(name)) {
                        if (!n.getArguments().isEmpty() || n.getClassBody() != null) {
                            problems.add(where + v.getName() + ": an enum constant with arguments or a body.");
                        }
                        continue;
                    }
                    // A record's component fields, which the parser makes from the header.
                    if ((((com.sun.tools.javac.tree.JCTree.JCVariableDecl) v).mods.flags
                            & com.sun.tools.javac.code.Flags.RECORD) != 0) continue;
                    access(v.getModifiers().getFlags(), iface, where + v.getName());
                    if (v.getInitializer() != null && !constant(v.getInitializer())) {
                        problems.add(where + v.getName() + " has a non-constant initializer: " + v.getInitializer());
                    }
                }
                case BlockTree b -> problems.add(where + "<initializer>: an initializer block.");
                default -> problems.add(where + ": unexpected member " + m.getKind());
            }
        }
    }

    void access(Set<Modifier> flags, boolean inInterface, String where) {
        if (flags.contains(Modifier.PRIVATE)) problems.add(where + " is private.");
        else if (!inInterface && !flags.contains(Modifier.PUBLIC) && !flags.contains(Modifier.PROTECTED)) {
            problems.add(where + " is package-private.");
        }
    }

    static boolean constant(ExpressionTree e) {
        return switch (e) {
            case com.sun.source.tree.LiteralTree l -> true; // null is a stub's stand-in for a value left out
            case com.sun.source.tree.ParenthesizedTree p -> constant(p.getExpression());
            case com.sun.source.tree.UnaryTree u -> constant(u.getExpression());
            case com.sun.source.tree.BinaryTree b -> constant(b.getLeftOperand()) && constant(b.getRightOperand());
            case com.sun.source.tree.ConditionalExpressionTree c ->
                    constant(c.getCondition()) && constant(c.getTrueExpression()) && constant(c.getFalseExpression());
            case TypeCastTree t -> (t.getType().getKind() == Tree.Kind.PRIMITIVE_TYPE
                    || t.getType().toString().equals("String")) && constant(t.getExpression());
            case IdentifierTree i -> i.getName().toString().matches("[A-Z][A-Z0-9_]*");
            case MemberSelectTree s -> s.getIdentifier().toString().matches("[A-Z][A-Z0-9_]*")
                    && BOXED.contains(s.getExpression().toString());
            default -> false;
        };
    }
}
