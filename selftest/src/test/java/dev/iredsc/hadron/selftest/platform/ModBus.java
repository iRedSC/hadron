package dev.iredsc.hadron.selftest.platform;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * What a misbehaving mod does, for tests of how Hadron copes: a listener on NeoForge's event bus, or a dimension the
 * mod adds to the server. Through reflection, since selftest compiles against vanilla Minecraft only; these are the
 * same public NeoForge methods a mod calls.
 */
final class ModBus {
    private ModBus() {}

    /** Adds a mod's listener for a NeoForge event class (by name); closing removes it. */
    static AutoCloseable listen(String eventClass, Consumer<Object> listener) throws ReflectiveOperationException {
        ClassLoader loader = ModBus.class.getClassLoader();
        Class<?> neoForge = Class.forName("net.neoforged.neoforge.common.NeoForge", true, loader);
        Class<?> busType = Class.forName("net.neoforged.bus.api.IEventBus", true, loader);
        Class<?> priority = Class.forName("net.neoforged.bus.api.EventPriority", true, loader);
        Class<?> type = Class.forName(eventClass, true, loader);
        Object bus = neoForge.getField("EVENT_BUS").get(null);
        Consumer<Object> consumer = listener::accept;
        busType.getMethod("addListener", priority, boolean.class, Class.class, Consumer.class)
                .invoke(bus, priority.getField("NORMAL").get(null), false, type, consumer);
        Method unregister = busType.getMethod("unregister", Object.class);
        return () -> unregister.invoke(bus, consumer);
    }

    /** The level a NeoForge level event is about. */
    static Object level(Object levelEvent) {
        try {
            return levelEvent.getClass().getMethod("getLevel").invoke(levelEvent);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The server's loaded levels, as NeoForge lets a mod add to them ({@code forgeGetWorldMap}). */
    @SuppressWarnings("unchecked")
    static Map<ResourceKey<Level>, ServerLevel> worldMap(MinecraftServer server) throws ReflectiveOperationException {
        return (Map<ResourceKey<Level>, ServerLevel>) server.getClass().getMethod("forgeGetWorldMap").invoke(server);
    }
}
