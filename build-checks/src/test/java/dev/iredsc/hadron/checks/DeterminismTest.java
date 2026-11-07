package dev.iredsc.hadron.checks;

import com.google.errorprone.CompilationTestHelper;
import org.junit.jupiter.api.Test;

class DeterminismTest {
    private final CompilationTestHelper helper = CompilationTestHelper.newInstance(Determinism.class, getClass())
            .addSourceLines("net/minecraft/util/RandomSource.java",
                    "package net.minecraft.util;",
                    "public interface RandomSource {",
                    "  static RandomSource create() { return null; }",
                    "  static RandomSource create(long seed) { return null; }",
                    "}")
            .addSourceLines("net/minecraft/Util.java",
                    "package net.minecraft;",
                    "public class Util {",
                    "  public static long getMillis() { return 0; }",
                    "  public static long getNanos() { return 0; }",
                    "  public static long getEpochMillis() { return 0; }",
                    "}");
    /** As Hadron's own modules compile: a reasoned exemption is allowed. */
    private final CompilationTestHelper hadron = CompilationTestHelper.newInstance(Determinism.class, getClass())
            .setArgs("-XepOpt:Hadron:AllowExemptions=true");

    @Test
    void refusesSleepsAndTheWallClock() {
        helper.addSourceLines("com/example/MyTests.java",
                "package com.example;",
                "import java.time.*;",
                "import java.util.*;",
                "import net.minecraft.Util;",
                "class MyTests {",
                "  void waits() throws InterruptedException {",
                "    // BUG: Diagnostic contains: t.tick(n)",
                "    Thread.sleep(100);",
                "    // BUG: Diagnostic contains: wall clock",
                "    long start = System.currentTimeMillis();",
                "    // BUG: Diagnostic contains: wall clock",
                "    System.nanoTime();",
                "    // BUG: Diagnostic contains: wall clock",
                "    Instant.now();",
                "    // BUG: Diagnostic contains: wall clock",
                "    LocalDate.now();",
                "    // BUG: Diagnostic contains: wall clock",
                "    Clock.systemUTC();",
                "    // BUG: Diagnostic contains: wall clock",
                "    Clock.systemDefaultZone();",
                "    // BUG: Diagnostic contains: wall clock",
                "    Clock.system(ZoneOffset.UTC);",
                "    // BUG: Diagnostic contains: wall clock",
                "    InstantSource.system();",
                "    // BUG: Diagnostic contains: wall clock",
                "    new Date();",
                "    // BUG: Diagnostic contains: wall clock",
                "    Calendar.getInstance();",
                "    // BUG: Diagnostic contains: wall clock",
                "    Util.getMillis();",
                "    // BUG: Diagnostic contains: wall clock",
                "    Util.getNanos();",
                "    // BUG: Diagnostic contains: wall clock",
                "    Util.getEpochMillis();",
                "    Runnable later = () -> {",
                "      // BUG: Diagnostic contains: wall clock",
                "      System.nanoTime();",
                "    };",
                "  }",
                "}")
                .doTest();
    }

    @Test
    void refusesUnseededRandomness() {
        helper.addSourceLines("com/example/MyTests.java",
                "package com.example;",
                "import java.security.SecureRandom;",
                "import java.util.*;",
                "import java.util.concurrent.ThreadLocalRandom;",
                "import java.util.random.RandomGenerator;",
                "import net.minecraft.util.RandomSource;",
                "class MyTests {",
                "  void rolls() {",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    new Random();",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    new SplittableRandom();",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    new SecureRandom();",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    Math.random();",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    ThreadLocalRandom.current();",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    RandomGenerator.getDefault();",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    RandomSource.create();",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    UUID.randomUUID();",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    Collections.shuffle(new ArrayList<>(List.of(1, 2)));",
                "  }",
                "}")
                .doTest();
    }

    @Test
    void refusesMethodReferences() {
        helper.addSourceLines("com/example/MyTests.java",
                "package com.example;",
                "import java.time.Instant;",
                "import java.util.*;",
                "import java.util.function.*;",
                "class MyTests {",
                "  interface Sleeper { void sleep(long ms) throws InterruptedException; }",
                "  void later() {",
                "    // BUG: Diagnostic contains: wall clock",
                "    LongSupplier nanos = System::nanoTime;",
                "    // BUG: Diagnostic contains: Sleeping",
                "    Sleeper sleeper = Thread::sleep;",
                "    // BUG: Diagnostic contains: wall clock",
                "    Supplier<Instant> now = Instant::now;",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    Supplier<Random> random = Random::new;",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    Supplier<UUID> id = UUID::randomUUID;",
                "  }",
                "}")
                .doTest();
    }

    @Test
    void acceptsSeededFormsFixedClocksAndTicks() {
        helper.addSourceLines("com/example/MyTests.java",
                "package com.example;",
                "import java.security.SecureRandom;",
                "import java.time.*;",
                "import java.util.*;",
                "import net.minecraft.util.RandomSource;",
                "class MyTests {",
                "  int ticks;",
                "  void tick(int n) { ticks += n; }",
                "  void fixed(long seed) {",
                "    Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);",
                "    Instant at = Instant.now(clock);",
                "    LocalDate day = LocalDate.now(clock);",
                "    Date epoch = new Date(0L);",
                "    new Random(seed).nextInt(6);",
                "    new SplittableRandom(seed);",
                "    new SecureRandom(new byte[] {1});",
                "    RandomSource.create(seed);",
                "    new UUID(seed, seed);",
                "    Collections.shuffle(new ArrayList<>(List.of(1, 2)), new Random(seed));",
                "    tick(20);",
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
