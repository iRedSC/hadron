package dev.iredsc.hadron.checks;

import com.google.errorprone.CompilationTestHelper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TooLongTest {
    private final CompilationTestHelper helper = CompilationTestHelper.newInstance(TooLong.class, getClass());

    /** A class with one method of [methodLines] lines, padded with blank lines to [fileLines] in all. */
    private static String[] source(int methodLines, int fileLines, boolean expectFile, boolean expectMethod) {
        List<String> lines = new ArrayList<>();
        lines.add("package com.example;");
        if (expectFile) lines.add("// BUG: Diagnostic contains: This file is");
        lines.add("class Big {");
        if (expectMethod) lines.add("  // BUG: Diagnostic contains: Method 'run' is");
        lines.add("  void run() {");
        for (int i = 0; i < methodLines - 2; i++) lines.add("    int a" + i + " = " + i + ";");
        lines.add("  }");
        lines.add("}");
        while (lines.size() < fileLines) lines.add("// padding");
        return lines.toArray(String[]::new);
    }

    @Test
    void warnsAboutALongFileAndALongMethod() {
        helper.addSourceLines("com/example/Big.java", source(TooLong.METHOD_LINES + 1, TooLong.FILE_LINES + 1, true, true))
                .doTest();
    }

    @Test
    void acceptsAFileAndMethodAtTheLimit() {
        helper.addSourceLines("com/example/Big.java", source(TooLong.METHOD_LINES, TooLong.FILE_LINES, false, false))
                .doTest();
    }
}
