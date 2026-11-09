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
    /** The system property holding the harness's {@code Bridge} for {@code hadron_test_target}. */
    public static final String BRIDGE_PROPERTY = "hadron.test.bridge";

    public HarnessMod(IEventBus modBus) {
        TestRunner.register(modBus);
        // Claimed here, in a mod's constructor, as a real bridge claims, so BridgeClaimTests sees the loader's path.
        // The handle goes where self-tests, which can't see the harness's classes, can fire through it.
        System.getProperties().put(BRIDGE_PROPERTY, dev.iredsc.hadron.api.bridge.Bridge.claim(TargetMod.MOD_ID));
    }
}
