package dev.iredsc.hadron.selftest.bridges;

import dev.iredsc.hadron.api.bridge.Bridge;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;

/**
 * A bridge proves which mod it is by claiming from its mod's constructor, which the loader reports. Once the server
 * runs no mod is constructing, so a plugin can't claim a bridge and fire events in a bridge's name.
 */
public final class BridgeClaimTests {
    @HadronTest
    @Covers(value = Bridge.class, method = "claim")
    void aPluginCannotClaimABridgeWhileTheServerRuns(TestContext t) {
        String refused = "no refusal: the claim went through";
        try {
            Bridge.claim("neoforge_test_absent");
        } catch (IllegalStateException e) {
            refused = e.getMessage();
        }
        t.assertEquals("Bridge.claim was called outside a mod's constructor, so Hadron can't tell which bridge is "
                + "claiming: call it from your bridge mod's constructor", refused, "the refusal");
        t.assertFalse(Bridge.isInstalled("neoforge_test_absent"), "nothing was claimed");
    }
}
