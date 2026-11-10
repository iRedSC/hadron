package dev.iredsc.hadron.checks;

import com.google.errorprone.CompilationTestHelper;
import org.junit.jupiter.api.Test;

class HostOnlyTest {
    private static final String[] HOST_KEY = {
            "package dev.iredsc.hadron.api;",
            "public final class HostKey {",
            "  private HostKey() {}",
            "  public static HostKey claim() { return null; }",
            "}"};
    private static final String[] HADRON = {
            "package dev.iredsc.hadron.api;",
            "public final class Hadron {",
            "  public static Object getServer() { return null; }",
            "  public static final class Lifecycle {",
            "    public static void install(HostKey key, Object server) {}",
            "    public static Object running() { return null; }",
            "  }",
            "}"};
    private static final String[] ACTORS = {
            "package dev.iredsc.hadron.api.actor;",
            "public final class Actors {",
            "  public static AutoCloseable push(Object actor) { return null; }",
            "  public static final class Port {",
            "    public static long enter(dev.iredsc.hadron.api.HostKey key, Object actor) { return 0; }",
            "    public static void exit(dev.iredsc.hadron.api.HostKey key, long handle) {}",
            "  }",
            "}"};
    private static final String[] HOST = {
            "package dev.iredsc.hadron.common;",
            "public final class PluginHost {",
            "  public PluginHost(dev.iredsc.hadron.api.HostKey key) {}",
            "}"};

    /** As a plugin's build compiles. */
    private final CompilationTestHelper plugin = CompilationTestHelper.newInstance(HostOnly.class, getClass())
            .addSourceLines("dev/iredsc/hadron/api/HostKey.java", HOST_KEY)
            .addSourceLines("dev/iredsc/hadron/api/Hadron.java", HADRON)
            .addSourceLines("dev/iredsc/hadron/api/actor/Actors.java", ACTORS)
            .addSourceLines("dev/iredsc/hadron/common/PluginHost.java", HOST);
    /** As Hadron's own modules compile. */
    private final CompilationTestHelper hadron = CompilationTestHelper.newInstance(HostOnly.class, getClass())
            .setArgs("-XepOpt:Hadron:AllowExemptions=true")
            .addSourceLines("dev/iredsc/hadron/api/HostKey.java", HOST_KEY)
            .addSourceLines("dev/iredsc/hadron/api/Hadron.java", HADRON)
            .addSourceLines("dev/iredsc/hadron/common/PluginHost.java", HOST);

    @Test
    void refusesAPluginReachingForTheHostKeyAndSaysWhatToUse() {
        plugin.addSourceLines("com/example/MyPlugin.java",
                "package com.example;",
                "import dev.iredsc.hadron.api.Hadron;",
                "import dev.iredsc.hadron.api.HostKey;",
                "import dev.iredsc.hadron.common.PluginHost;",
                "import java.util.function.Supplier;",
                "class MyPlugin {",
                "  @SuppressWarnings(\"HostOnly\")",
                "  void takeOver() {",
                "    // BUG: Diagnostic contains: HostKey.claim is for Hadron's own host",
                "    HostKey key = HostKey.claim();",
                "    // BUG: Diagnostic contains: Hadron.Lifecycle.install is for Hadron's own host",
                "    Hadron.Lifecycle.install(null, null);",
                "    // BUG: Diagnostic contains: Hadron.getServer()",
                "    Hadron.Lifecycle.install(key, null);",
                "    // BUG: Diagnostic contains: new PluginHost is for Hadron's own host",
                "    new PluginHost(null);",
                "    // BUG: Diagnostic contains: HostKey.claim is for Hadron's own host",
                "    Supplier<HostKey> later = HostKey::claim;",
                "    // BUG: Diagnostic contains: Actors.Port.enter is for Hadron's own host",
                "    long handle = dev.iredsc.hadron.api.actor.Actors.Port.enter(null, this);",
                "    // BUG: Diagnostic contains: Actors.push(actor)",
                "    dev.iredsc.hadron.api.actor.Actors.Port.exit(null, handle);",
                "  }",
                "}")
                .doTest();
    }

    @Test
    void passesWhatPluginsUseAndHadronsOwnCode() {
        plugin.addSourceLines("com/example/MyPlugin.java",
                "package com.example;",
                "import dev.iredsc.hadron.api.Hadron;",
                "class MyPlugin {",
                "  Object server() {",
                "    Hadron.Lifecycle.running();",
                "    return Hadron.getServer();",
                "  }",
                "}")
                .doTest();
        hadron.addSourceLines("dev/iredsc/hadron/neoforge/HadronMod.java",
                "package dev.iredsc.hadron.neoforge;",
                "import dev.iredsc.hadron.api.Hadron;",
                "import dev.iredsc.hadron.api.HostKey;",
                "class HadronMod {",
                "  HadronMod() {",
                "    HostKey key = HostKey.claim();",
                "    Hadron.Lifecycle.install(key, null);",
                "  }",
                "}")
                .doTest();
    }
}
