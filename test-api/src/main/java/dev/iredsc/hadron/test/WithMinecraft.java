package dev.iredsc.hadron.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * On a JUnit test class: boots Minecraft's registries (once per JVM) before its tests, so they can use
 * {@code ItemStack}, {@code BlockState}, data components and the built-in registries without a server.
 *
 * <pre>{@code
 * @WithMinecraft
 * class PriceTest {
 *     @Test
 *     void diamondsCostMoreThanDirt() {
 *         assertTrue(Prices.of(new ItemStack(Items.DIAMOND)) > Prices.of(new ItemStack(Items.DIRT)));
 *     }
 * }
 * }</pre>
 *
 * Runs with {@code ./gradlew test}, apart from the in-game {@link HadronTest}s, which run with
 * {@code ./gradlew hadronTest}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@ExtendWith(MinecraftBootstrap.class)
public @interface WithMinecraft {}
