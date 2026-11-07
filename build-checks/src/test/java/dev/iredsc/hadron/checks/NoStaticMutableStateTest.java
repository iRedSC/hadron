package dev.iredsc.hadron.checks;

import com.google.errorprone.CompilationTestHelper;
import org.junit.jupiter.api.Test;

class NoStaticMutableStateTest {
    private final CompilationTestHelper helper = CompilationTestHelper.newInstance(NoStaticMutableState.class, getClass());
    /** As Hadron's own modules compile: a reasoned exemption is allowed. */
    private final CompilationTestHelper hadron = CompilationTestHelper.newInstance(NoStaticMutableState.class, getClass())
            .setArgs("-XepOpt:Hadron:AllowExemptions=true");

    @Test
    void refusesStaticStateInAPlugin() {
        helper.addSourceLines("com/example/MyPlugin.java",
                "package com.example;",
                "import java.util.*;",
                "import java.util.concurrent.ConcurrentHashMap;",
                "import java.util.concurrent.atomic.AtomicInteger;",
                "class MyPlugin {",
                "  // BUG: Diagnostic contains: isn't final",
                "  static int joins;",
                "  // BUG: Diagnostic contains: holds a HashMap",
                "  static final Map<UUID, Integer> SCORES = new HashMap<>();",
                "  // BUG: Diagnostic contains: holds a ConcurrentHashMap",
                "  static final ConcurrentHashMap<String, String> NAMES = new ConcurrentHashMap<>();",
                "  // BUG: Diagnostic contains: holds a ArrayList",
                "  static final List<String> SEEN = new ArrayList<>();",
                "  // BUG: Diagnostic contains: holds a Set",
                "  static final Set<String> MODES = EnumSet.noneOf(Thread.State.class).isEmpty() ? new HashSet<>() : Set.of();",
                "  // BUG: Diagnostic contains: holds an array",
                "  static final String[] WORDS = {\"a\", \"b\"};",
                "  // BUG: Diagnostic contains: holds an array",
                "  static final String[] SLOTS = new String[4];",
                "  // BUG: Diagnostic contains: holds a AtomicInteger",
                "  static final AtomicInteger COUNT = new AtomicInteger();",
                "  // BUG: Diagnostic contains: holds a ThreadLocal",
                "  static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();",
                "  // BUG: Diagnostic contains: holds a PrintStream",
                "  static final java.io.PrintStream OUT = System.out;",
                "  // BUG: Diagnostic contains: holds a ClassValue, whose value",
                "  static final ClassValue<String> NAMES_BY_CLASS = new ClassValue<>() { protected String computeValue(Class<?> c) { return c.getName(); } };",
                "  // BUG: Diagnostic contains: holds a ThreadLocal",
                "  static final Object HIDDEN = new ThreadLocal<Object>();",
                "  static final class Holder { final Map<String, Integer> counts = new HashMap<>(); }",
                "  // BUG: Diagnostic contains: holds a Holder",
                "  static final Holder HOLDER = new Holder();",
                "  record Bag(List<String> items) {}",
                "  // BUG: Diagnostic contains: holds a Bag",
                "  static final Bag BAG = new Bag(new ArrayList<>());",
                "  static final class Counter { int n; }",
                "  // BUG: Diagnostic contains: holds a Counter",
                "  static final Counter COUNTER = new Counter();",
                "  // BUG: Diagnostic contains: holds a Runnable",
                "  static final Runnable TASK = HOLDER.counts::clear;",
                "  static class Open { final int n = 1; }",
                "  // BUG: Diagnostic contains: holds a Open",
                "  static final Open OPEN = make();",
                "  static Open make() { return new Open(); }",
                "  @dev.iredsc.hadron.checks.StaticState(reason = \"a plugin can't vouch for a class\")",
                "  static final class Vouched { final Runnable r = () -> {}; }",
                "  // BUG: Diagnostic contains: holds a Vouched",
                "  static final Vouched VOUCHED = new Vouched();",
                "  // BUG: Diagnostic contains: keeps state on its plugin instance",
                "  @dev.iredsc.hadron.checks.StaticState(reason = \"a plugin can't exempt itself\") static int sneaky;",
                "}")
                .doTest();
    }

    @Test
    void acceptsImmutableConstantsAndInstanceState() {
        helper.addSourceLines("com/example/MyPlugin.java",
                "package com.example;",
                "import java.util.*;",
                "import java.util.regex.Pattern;",
                "class MyPlugin {",
                "  record Spot(int x, int z) {}",
                "  enum Mode { ON, OFF }",
                "  static final int MAX = 3;",
                "  static final String PREFIX = \"[hello] \";",
                "  static final List<String> WORDS = List.of(\"a\", \"b\");",
                "  static final Set<String> TAGS = Set.copyOf(WORDS);",
                "  static final Map<String, Integer> COSTS = Map.of(\"a\", 1);",
                "  static final List<String> NONE = Collections.emptyList();",
                "  static final List<String> VIEW = Collections.unmodifiableList(new ArrayList<>(WORDS));",
                "  static final List<String> UPPER = WORDS.stream().map(String::toUpperCase).toList();",
                "  static final Spot SPAWN = new Spot(0, 0);",
                "  record Named(String name, Mode mode, Spot spot) {}",
                "  static final Named HOME = new Named(\"home\", Mode.ON, SPAWN);",
                "  static final Mode DEFAULT = Mode.ON;",
                "  static final class Price { final String currency; final long cents; Price(String c, long n) { currency = c; cents = n; } }",
                "  static final Price FREE = new Price(\"coins\", 0);",
                "  interface Shape {}",
                "  static final class Square implements Shape { final int side = 1; }",
                "  static final Shape UNIT = new Square();",
                "  static final Shape ALIAS = UNIT;",
                "  static final Shape ROUND = new Shape() {};",
                "  static final Runnable NOTHING = () -> {};",
                "  static final java.util.function.Function<String, Integer> LENGTH = String::length;",
                "  static final UUID ID = new UUID(1, 2);",
                "  static final Object LOCK = new Object();",
                "  static final Integer BOXED = 4;",
                "  static final Class<?> TYPE = String.class;",
                "  static final Pattern NAME = Pattern.compile(\"[a-z]+\");",
                "  static final String[] NO_WORDS = new String[0];",
                "  static final int[] NO_NUMBERS = {};",
                "  private final Map<UUID, Integer> scores = new HashMap<>();",
                "  private int joins;",
                "}")
                .doTest();
    }

    @Test
    void hadronsOwnCodeMayExemptAFieldWithAReason() {
        hadron.addSourceLines("dev/iredsc/hadron/neoforge/Hooks.java",
                "package dev.iredsc.hadron.neoforge;",
                "import dev.iredsc.hadron.checks.StaticState;",
                "class Hooks {",
                "  @StaticState(reason = \"set on server start, cleared on server stop\")",
                "  static volatile Object host;",
                "  // BUG: Diagnostic contains: annotate it @StaticState",
                "  static Object forgotten;",
                "  // BUG: Diagnostic contains: needs a reason",
                "  @StaticState(reason = \" \") static Object blank;",
                "  @StaticState(reason = \"final fields of stateless functions\")",
                "  static final class Codec { final java.util.function.Function<String, Integer> parse = Integer::valueOf; }",
                "  static final Codec INT = new Codec();",
                "  static final class Unvouched { final java.util.function.Function<String, Integer> parse = Integer::valueOf; }",
                "  // BUG: Diagnostic contains: holds a Unvouched",
                "  static final Unvouched RAW = new Unvouched();",
                "}")
                .doTest();
    }

    @Test
    void ignoresMixinShadowFields() {
        helper.addSourceLines("org/spongepowered/asm/mixin/Shadow.java",
                        "package org.spongepowered.asm.mixin;",
                        "public @interface Shadow {}")
                .addSourceLines("dev/iredsc/hadron/neoforge/mixin/SomeMixin.java",
                        "package dev.iredsc.hadron.neoforge.mixin;",
                        "abstract class SomeMixin {",
                        "  @org.spongepowered.asm.mixin.Shadow private static int counter;",
                        "}")
                .doTest();
    }
}
