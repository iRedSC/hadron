package dev.iredsc.hadron.harness;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * The in-game test harness as a mod of its own, {@code hadron_test}, apart from Hadron's: only the test server's runs
 * load it, and Hadron's release jar is built from sources that can't see it, so no test code reaches a server owner.
 * It still does nothing unless the server was started in test or watch mode.
 */
@Mod(HarnessMod.MOD_ID)
public final class HarnessMod {
    public static final String MOD_ID = "hadron_test";

    public HarnessMod(IEventBus modBus) {
        TestRunner.register(modBus);
    }
}
