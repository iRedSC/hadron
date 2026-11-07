package dev.iredsc.hadron.checks;

import com.google.errorprone.CompilationTestHelper;
import org.junit.jupiter.api.Test;

class DeterminismTest {
    private final CompilationTestHelper helper = CompilationTestHelper.newInstance(Determinism.class, getClass());
    /** As Hadron's own modules compile: a reasoned exemption is allowed. */
    private final CompilationTestHelper hadron = CompilationTestHelper.newInstance(Determinism.class, getClass())
            .setArgs("-XepOpt:Hadron:AllowExemptions=true");

    @Test
    void refusesClockSleepAndUnseededRandom() {
        helper.addSourceLines("com/example/MyTests.java",
                "package com.example;",
                "import java.util.Random;",
                "import java.util.concurrent.ThreadLocalRandom;",
                "import java.util.concurrent.TimeUnit;",
                "class MyTests {",
                "  void waits() throws InterruptedException {",
                "    // BUG: Diagnostic contains: t.tick(n)",
                "    Thread.sleep(100);",
                "    // BUG: Diagnostic contains: Sleeping",
                "    TimeUnit.SECONDS.sleep(1);",
                "    // BUG: Diagnostic contains: wall clock",
                "    long start = System.currentTimeMillis();",
                "    // BUG: Diagnostic contains: wall clock",
                "    while (System.nanoTime() - start < 5) {}",
                "    // BUG: Diagnostic contains: wall clock",
                "    java.time.Instant.now();",
                "    // BUG: Diagnostic contains: new Random(seed)",
                "    double d = Math.random();",
                "    // BUG: Diagnostic contains: new Random(seed)",
                "    int i = ThreadLocalRandom.current().nextInt();",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    Random r = new Random();",
                "    Runnable later = () -> {",
                "      // BUG: Diagnostic contains: wall clock",
                "      System.nanoTime();",
                "    };",
                "  }",
                "}")
                .doTest();
    }

    @Test
    void acceptsSeededRandomnessAndTicks() {
        helper.addSourceLines("com/example/MyTests.java",
                "package com.example;",
                "import java.util.Random;",
                "class MyTests {",
                "  int ticks;",
                "  void tick(int n) { ticks += n; }",
                "  int roll(long seed) {",
                "    tick(20);",
                "    return new Random(seed).nextInt(6);",
                "  }",
                "}")
                .doTest();
    }

    @Test
    void hadronMayExemptAMethodWithAReason() {
        hadron.addSourceLines("dev/iredsc/hadron/common/Host.java",
                "package dev.iredsc.hadron.common;",
                "import dev.iredsc.hadron.checks.Nondeterministic;",
                "class Host {",
                "  @Nondeterministic(reason = \"grace period for async tasks on disable\")",
                "  long deadline() { return System.nanoTime() + 1000; }",
                "  @Nondeterministic(reason = \"\")",
                "  long blank() {",
                "    // BUG: Diagnostic contains: needs a reason",
                "    return System.nanoTime();",
                "  }",
                "}")
                .doTest();
    }

    @Test
    void aPluginCantExemptItself() {
        // Even in Hadron's package, as examples/hello is: only Hadron's own builds allow exemptions.
        helper.addSourceLines("dev/iredsc/hadron/examples/hello/MyTests.java",
                "package dev.iredsc.hadron.examples.hello;",
                "class MyTests {",
                "  @dev.iredsc.hadron.checks.Nondeterministic(reason = \"a plugin can't exempt itself\")",
                "  void waits() throws InterruptedException {",
                "    // BUG: Diagnostic contains: only for Hadron's own code",
                "    Thread.sleep(5);",
                "  }",
                "}")
                .doTest();
    }
}
