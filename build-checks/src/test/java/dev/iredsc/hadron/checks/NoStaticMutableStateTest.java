package dev.iredsc.hadron.checks;

import com.google.errorprone.CompilationTestHelper;
import org.junit.jupiter.api.Test;

class NoStaticMutableStateTest {
    private final CompilationTestHelper helper = CompilationTestHelper.newInstance(NoStaticMutableState.class, getClass());

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
                "  // BUG: Diagnostic contains: holds a Map",
                "  static final Map<UUID, Integer> SCORES = new HashMap<>();",
                "  // BUG: Diagnostic contains: holds a ConcurrentHashMap",
                "  static final ConcurrentHashMap<String, String> NAMES = new ConcurrentHashMap<>();",
                "  // BUG: Diagnostic contains: holds a List",
                "  static final List<String> SEEN = new ArrayList<>();",
                "  // BUG: Diagnostic contains: holds a Set",
                "  static final Set<String> MODES = EnumSet.noneOf(Thread.State.class).isEmpty() ? new HashSet<>() : Set.of();",
                "  // BUG: Diagnostic contains: holds an array",
                "  static final String[] WORDS = {\"a\", \"b\"};",
                "  // BUG: Diagnostic contains: holds a AtomicInteger",
                "  static final AtomicInteger COUNT = new AtomicInteger();",
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
                "  static final Pattern NAME = Pattern.compile(\"[a-z]+\");",
                "  private final Map<UUID, Integer> scores = new HashMap<>();",
                "  private int joins;",
                "}")
                .doTest();
    }

    @Test
    void hadronsOwnCodeMayExemptAFieldWithAReason() {
        helper.addSourceLines("dev/iredsc/hadron/neoforge/Hooks.java",
                "package dev.iredsc.hadron.neoforge;",
                "import dev.iredsc.hadron.checks.StaticState;",
                "class Hooks {",
                "  @StaticState(reason = \"set on server start, cleared on server stop\")",
                "  static volatile Object host;",
                "  // BUG: Diagnostic contains: annotate it @StaticState",
                "  static Object forgotten;",
                "  // BUG: Diagnostic contains: needs a reason",
                "  @StaticState(reason = \" \") static Object blank;",
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
