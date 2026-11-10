package dev.iredsc.hadron.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class BridgeDependTest {
    @Test
    void aLoadedBridgeMeetsIt() {
        assertNull(PluginHost.missingBridge("mekanism", true, true));
    }

    @Test
    void aMissingBridgeIsNamed() {
        assertEquals("uses Hadron's bridge for mekanism, which isn't installed: add the Hadron bridge for mekanism to "
                + "mods/, or remove this plugin", PluginHost.missingBridge("mekanism", true, false));
    }

    @Test
    void aMissingModAsksForBothTheModAndItsBridge() {
        assertEquals("uses Hadron's bridge for the mekanism mod, but mekanism isn't installed: add mekanism and its "
                + "Hadron bridge to mods/, or remove this plugin", PluginHost.missingBridge("mekanism", false, false));
    }
}
