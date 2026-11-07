package dev.iredsc.hadron.checks;

import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.CompilationUnitTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.MethodTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.tree.JCTree;

/**
 * Warns about a file over 800 lines or a method over 120: past that, a reader (often an agent with a limited context)
 * can't hold it at once, and mistakes hide in it. A warning for now, never a failure.
 */
@AutoService(BugChecker.class)
@BugPattern(
        summary = "A file over 800 lines or a method over 120 is hard to hold at once; split it",
        severity = WARNING)
public final class TooLong extends BugChecker implements CompilationUnitTreeMatcher, MethodTreeMatcher {
    static final int FILE_LINES = 800;
    static final int METHOD_LINES = 120;

    @Override
    public Description matchCompilationUnit(CompilationUnitTree tree, VisitorState state) {
        CharSequence source;
        try {
            source = tree.getSourceFile().getCharContent(true);
        } catch (java.io.IOException e) {
            return Description.NO_MATCH;
        }
        long lines = source.chars().filter(c -> c == '\n').count();
        if (source.length() > 0 && source.charAt(source.length() - 1) != '\n') lines++;
        if (lines <= FILE_LINES) return Description.NO_MATCH;
        // Anchored on the first type, since a compilation unit itself has no position to point at.
        var anchor = tree.getTypeDecls().isEmpty() ? tree : tree.getTypeDecls().get(0);
        return buildDescription(anchor).setMessage("This file is " + lines + " lines, over " + FILE_LINES
                + ". Split it: move a group of related methods into its own class.").build();
    }

    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
        if (tree.getBody() == null) return Description.NO_MATCH;
        LineMap map = state.getPath().getCompilationUnit().getLineMap();
        var body = tree.getBody();
        long start = map.getLineNumber(((JCTree) tree).getStartPosition());
        long end = map.getLineNumber(state.getEndPosition(body));
        long lines = end - start + 1;
        if (lines <= METHOD_LINES) return Description.NO_MATCH;
        return buildDescription(tree).setMessage("Method '" + tree.getName() + "' is " + lines + " lines, over "
                + METHOD_LINES + ". Split it: pull each step into a method named for what it does.").build();
    }
}
