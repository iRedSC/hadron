package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.bridge.Bridge;
import dev.iredsc.hadron.api.event.Event;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import org.jspecify.annotations.Nullable;

/** Lets bridge mods claim their mods and fire events through the same path core's chokepoints use. */
final class BridgePort implements Bridge.Backend {
    private BridgePort() {}

    static void register() {
        Bridge.Port.install(new BridgePort());
    }

    @Override
    public @Nullable String constructingMod() {
        // FML sets the active container while a mod constructs or handles its loading events, and reports minecraft
        // outside them; ModList is null before mods are listed.
        if (ModList.get() == null) return null;
        ModContainer active = ModLoadingContext.get().getActiveContainer();
        String id = active.getModId();
        return id.equals("minecraft") ? null : id;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean listens(Class<? extends Event> type) {
        return Hooks.listens(type);
    }

    @Override
    public void post(Event event) {
        Hooks.post(event);
    }
}
