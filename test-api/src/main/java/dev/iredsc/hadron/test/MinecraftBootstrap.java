package dev.iredsc.hadron.test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Boots Minecraft's registries once per JVM, with no server or world, so a fast test (plain JUnit, run by
 * {@code ./gradlew test}) can build {@code ItemStack}s, {@code BlockState}s, data components and
 * {@code ResourceLocation}s. Put {@link WithMinecraft @WithMinecraft} on the test class; outside JUnit, call
 * {@link #ensure()}. Tests that don't touch Minecraft's registries need neither, and don't pay the second or so it
 * takes.
 *
 * <p>Fast tests are for code that decides (a price, a rule, a parser). Anything that needs a world, a player or a
 * packet is an {@code @HadronTest}, which runs in a real server with {@code ./gradlew hadronTest}.
 */
public final class MinecraftBootstrap implements BeforeAllCallback {
    private static volatile boolean done;

    /** JUnit makes one through {@link WithMinecraft}; nothing else needs to. */
    public MinecraftBootstrap() {}

    /** Boots the registries if nothing has yet in this JVM. Safe from any thread, and cheap after the first call. */
    public static void ensure() {
        if (done) return;
        synchronized (MinecraftBootstrap.class) {
            if (done) return;
            try {
                SharedConstants.tryDetectVersion();
                Bootstrap.bootStrap();
            } catch (LinkageError e) {
                throw new IllegalStateException("Minecraft isn't on this test's classpath, so its registries can't "
                        + "boot. In build.gradle.kts add neoForge.addModdingDependenciesTo(sourceSets.test.get())", e);
            }
            done = true;
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        ensure();
    }
}
