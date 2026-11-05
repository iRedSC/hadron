package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.common.Platform;
import java.io.File;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

final class NeoForgePlatform implements Platform {
    @Override
    public String loader() {
        return "neoforge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public File configFolder() {
        return FMLPaths.CONFIGDIR.get().toFile();
    }
}
