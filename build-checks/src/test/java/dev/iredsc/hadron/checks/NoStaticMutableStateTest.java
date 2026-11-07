package dev.iredsc.hadron.checks;

import com.google.errorprone.CompilationTestHelper;
import org.junit.jupiter.api.Test;

class NoStaticMutableStateTest {
    private final CompilationTestHelper helper = CompilationTestHelper.newInstance(NoStaticMutableState.class, getClass());
    /** As Hadron's own modules compile: a reasoned exemption is allowed. */
    private final CompilationTestHelper hadron = CompilationTestHelper.newInstance(NoStaticMutableState.class, getClass())
            .setArgs("-XepOpt:Hadron:AllowExemptions=true");

    @Test
    void refusesTheCommonKindsOfStaticState() {
        helper.addSourceLines("com/example/MyPlugin.java",
                "package com.example;",
                "import java.util.*;",
                "import java.util.concurrent.*;",
                "import java.util.concurrent.atomic.*;",
                "class MyPlugin {",
                "  // BUG: Diagnostic contains: isn't final",
                "  static int joins;",
                "  // BUG: Diagnostic contains: holds a HashMap",
                "  static final Map<UUID, Integer> SCORES = new HashMap<>();",
                "  // BUG: Diagnostic contains: holds a ArrayList",
                "  static final List<String> SEEN = new ArrayList<>();",
                "  // BUG: Diagnostic contains: holds a EnumSet",
                "  static final Set<TimeUnit> UNITS = EnumSet.of(TimeUnit.SECONDS);",
                "  // BUG: Diagnostic contains: holds a List",
                "  static final List<String> MADE = make();",
                "  // BUG: Diagnostic contains: holds a ConcurrentHashMap",
                "  static final ConcurrentHashMap<String, String> NAMES = new ConcurrentHashMap<>();",
                "  // BUG: Diagnostic contains: holds a CopyOnWriteArrayList",
                "  static final List<String> LISTENERS = new CopyOnWriteArrayList<>();",
                "  // BUG: Diagnostic contains: holds an array",
                "  static final String[] WORDS = {\"a\", \"b\"};",
                "  // BUG: Diagnostic contains: holds an array",
                "  static final String[] SLOTS = new String[4];",
                "  // BUG: Diagnostic contains: holds a AtomicInteger",
                "  static final AtomicInteger COUNT = new AtomicInteger();",
                "  // BUG: Diagnostic contains: holds a LongAdder",
                "  static final LongAdder TOTAL = new LongAdder();",
                "  // BUG: Diagnostic contains: holds a ThreadLocal",
                "  static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();",
                "  // BUG: Diagnostic contains: holds a ThreadLocal",
                "  static final Object HIDDEN = new ThreadLocal<Object>();",
                "  // BUG: Diagnostic contains: holds a ClassValue",
                "  static final ClassValue<String> BY_CLASS = new ClassValue<>() { protected String computeValue(Class<?> c) { return c.getName(); } };",
                "  // BUG: Diagnostic contains: holds a StringBuilder",
                "  static final StringBuilder LOG = new StringBuilder();",
                "  // BUG: Diagnostic contains: holds a StringBuffer",
                "  static final StringBuffer BUF = new StringBuffer();",
                "  static List<String> make() { return List.of(); }",
                "}")
                .doTest();
    }

    @Test
    void refusesEnumConstantsThatHoldState() {
        helper.addSourceLines("com/example/MyPlugin.java",
                "package com.example;",
                "import java.util.*;",
                "import java.util.concurrent.atomic.AtomicInteger;",
                "class MyPlugin {",
                "  enum State {",
                "    // BUG: Diagnostic contains: holds a State",
                "    INSTANCE;",
                "    int joins;",
                "  }",
                "  enum Scores {",
                "    // BUG: Diagnostic contains: holds a Scores",
                "    ALL;",
                "    final Map<UUID, Integer> byPlayer = new HashMap<>();",
                "  }",
                "  enum Counted {",
                "    // BUG: Diagnostic contains: holds a Counted",
                "    ONE(new AtomicInteger());",
                "    final AtomicInteger n;",
                "    Counted(AtomicInteger n) { this.n = n; }",
                "  }",
                "  enum Odd {",
                "    // BUG: Diagnostic contains: holds a Odd",
                "    ONE { int calls; };",
                "  }",
                "  enum Shaped {",
                "    // BUG: Diagnostic contains: holds a Shaped",
                "    ROUND { };",
                "    int corners;",
                "  }",
                "  // BUG: Diagnostic contains: holds a State",
                "  static final State CURRENT = State.INSTANCE;",
                "}")
                .doTest();
    }

    @Test
    void acceptsTheConstantsAPluginWrites() {
        // PersistentDataType comes from a class file here, as the api jar does for a plugin.
        helper.addSourceLines("com/example/MyPlugin.java",
                "package com.example;",
                "import dev.iredsc.hadron.api.data.PersistentDataType;",
                "import java.io.File;",
                "import java.math.*;",
                "import java.nio.charset.*;",
                "import java.time.*;",
                "import java.util.*;",
                "import java.util.concurrent.TimeUnit;",
                "import java.util.regex.Pattern;",
                "class MyPlugin {",
                "  enum Mode { ON, OFF }",
                "  enum Rank {",
                "    MEMBER(\"m\", List.of()), ADMIN(\"a\", List.of(\"op\"));",
                "    final String tag; final List<String> perms;",
                "    Rank(String tag, List<String> perms) { this.tag = tag; this.perms = perms; }",
                "  }",
                "  enum Shaped { ROUND { @Override int corners() { return 0; } }; int corners() { return 4; } }",
                "  record Names(String first, String last) {}",
                "  static final int MAX = 3;",
                "  static final String PREFIX = \"[hello] \";",
                "  static final BigDecimal COST = new BigDecimal(\"1.00\");",
                "  static final Charset UTF8 = StandardCharsets.UTF_8;",
                "  static final UUID ID = UUID.fromString(\"00000000-0000-0000-0000-000000000001\");",
                "  static final Duration COOLDOWN = Duration.ofSeconds(5);",
                "  static final Comparator<String> NATURAL = Comparator.naturalOrder();",
                "  static final Comparator<String> NO_CASE = String.CASE_INSENSITIVE_ORDER;",
                "  static final File CONFIG = new File(\"config.yml\");",
                "  static final Object NOTHING = null;",
                "  static final Names OWNER = new Names(\"Ada\", \"Lovelace\");",
                "  static final PersistentDataType<String, String> TEXT = PersistentDataType.STRING;",
                "  static final PersistentDataType<String, Mode> MODE =",
                "      PersistentDataType.adapt(PersistentDataType.STRING, Mode.class, Mode::valueOf, Mode::name);",
                "  static final System.Logger LOG = System.getLogger(\"hello\");",
                "  static final Mode DEFAULT = Mode.ON;",
                "  static final Rank START = Rank.MEMBER;",
                "  static final Shaped SHAPE = Shaped.ROUND;",
                "  static final TimeUnit UNIT = TimeUnit.SECONDS;",
                "  static final Pattern NAME = Pattern.compile(\"[a-z]+\");",
                "  static final Runnable IDLE = () -> {};",
                "  static final Object LOCK = new Object();",
                "  static final List<String> WORDS = List.of(\"a\", \"b\");",
                "  static final Set<String> TAGS = Set.copyOf(WORDS);",
                "  static final Map<String, Integer> COSTS = Map.of(\"a\", 1);",
                "  static final List<String> NONE = Collections.emptyList();",
                "  static final List<String> VIEW = Collections.unmodifiableList(new ArrayList<>(WORDS));",
                "  static final List<String> UPPER = WORDS.stream().map(String::toUpperCase).toList();",
                "  static final String[] NO_WORDS = new String[0];",
                "  static final int[] NO_NUMBERS = {};",
                "  private final Map<UUID, Integer> scores = new HashMap<>();",
                "  private int joins;",
                "}")
                .doTest();
    }

    @Test
    void hadronsOwnCodeMayExemptAFieldOrClassWithAReason() {
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
                "  @StaticState(reason = \"cleared when the server stops\")",
                "  static final class Registry { static final java.util.List<String> NAMES = new java.util.ArrayList<>(); }",
                "  @StaticState(reason = \"set once when the mod loads\")",
                "  enum Port { INSTANCE; Object backend; }",
                "  static final Port PORT = Port.INSTANCE;",
                "}")
                .doTest();
    }

    @Test
    void aPluginCantExemptItself() {
        helper.addSourceLines("com/example/MyPlugin.java",
                "package com.example;",
                "class MyPlugin {",
                "  // BUG: Diagnostic contains: keeps state on its plugin instance",
                "  @dev.iredsc.hadron.checks.StaticState(reason = \"a plugin can't exempt itself\") static int sneaky;",
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
