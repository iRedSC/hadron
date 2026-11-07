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
    void refusesMemberReferencesSystemClocksAndOtherUnseededSources() {
        helper.addSourceLines("net/minecraft/util/RandomSource.java",
                        "package net.minecraft.util;",
                        "public interface RandomSource {",
                        "  static RandomSource create() { return null; }",
                        "  static RandomSource create(long seed) { return null; }",
                        "}")
                .addSourceLines("com/example/MyTests.java",
                "package com.example;",
                "import java.time.*;",
                "import java.util.*;",
                "import java.util.function.*;",
                "import net.minecraft.util.RandomSource;",
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
                "    // BUG: Diagnostic contains: wall clock",
                "    long millis = Clock.systemUTC().millis();",
                "    // BUG: Diagnostic contains: wall clock",
                "    Instant instant = Clock.systemDefaultZone().instant();",
                "    // BUG: Diagnostic contains: wall clock",
                "    InstantSource source = InstantSource.system();",
                "    // BUG: Diagnostic contains: wall clock",
                "    LocalDate today = LocalDate.now(ZoneId.of(\"UTC\"));",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    SplittableRandom split = new SplittableRandom();",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    RandomSource mc = RandomSource.create();",
                "  }",
                "}")
                .doTest();
    }

    @Test
    void refusesOldClocksDefaultGeneratorsAndAnonymousRandoms() {
        helper.addSourceLines("com/example/MyTests.java",
                "package com.example;",
                "import java.util.*;",
                "import java.util.random.*;",
                "class MyTests {",
                "  void old() {",
                "    // BUG: Diagnostic contains: wall clock",
                "    Date now = new Date();",
                "    // BUG: Diagnostic contains: wall clock",
                "    Calendar cal = Calendar.getInstance();",
                "    // BUG: Diagnostic contains: wall clock",
                "    Calendar utc = Calendar.getInstance(TimeZone.getTimeZone(\"UTC\"));",
                "    // BUG: Diagnostic contains: wall clock",
                "    Calendar greg = new GregorianCalendar();",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    RandomGenerator g = RandomGenerator.getDefault();",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    RandomGenerator named = RandomGenerator.of(\"L64X128MixRandom\");",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    RandomGenerator made = RandomGeneratorFactory.getDefault().create();",
                "    // BUG: Diagnostic contains: Pass a seed",
                "    Random anon = new Random() {};",
                "  }",
                "}")
                .doTest();
    }

    @Test
    void acceptsFixedDatesAndSeededGenerators() {
        helper.addSourceLines("com/example/MyTests.java",
                "package com.example;",
                "import java.util.*;",
                "import java.util.random.*;",
                "class MyTests {",
                "  void fixed(long seed) {",
                "    Date epoch = new Date(0L);",
                "    Calendar greg = new GregorianCalendar(2026, Calendar.JANUARY, 1);",
                "    RandomGenerator made = RandomGeneratorFactory.of(\"L64X128MixRandom\").create(seed);",
                "    Random anon = new Random(seed) {};",
                "  }",
                "}")
                .doTest();
    }

    @Test
    void acceptsFixedClocksAndSeededSources() {
        helper.addSourceLines("net/minecraft/util/RandomSource.java",
                        "package net.minecraft.util;",
                        "public interface RandomSource {",
                        "  static RandomSource create() { return null; }",
                        "  static RandomSource create(long seed) { return null; }",
                        "}")
                .addSourceLines("com/example/MyTests.java",
                "package com.example;",
                "import java.time.*;",
                "import java.util.*;",
                "import net.minecraft.util.RandomSource;",
                "class MyTests {",
                "  void fixed(long seed) {",
                "    Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);",
                "    Instant at = Instant.now(clock);",
                "    LocalDate day = LocalDate.now(clock);",
                "    long millis = Clock.offset(clock, Duration.ofSeconds(5)).millis();",
                "    SplittableRandom split = new SplittableRandom(seed);",
                "    RandomSource mc = RandomSource.create(seed);",
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
