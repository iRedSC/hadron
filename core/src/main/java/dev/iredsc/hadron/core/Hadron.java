package dev.iredsc.hadron.core;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Hadron.MOD_ID)
public final class Hadron {
    public static final String MOD_ID = "hadron";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Hadron(IEventBus modBus, ModContainer container) {
        LOGGER.info("Hadron {} loaded; plugin host not built yet", container.getModInfo().getVersion());
    }
}
