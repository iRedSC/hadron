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
            Bridge.claim("any_mod");
        } catch (IllegalStateException e) {
            refused = e.getMessage();
        }
        t.assertEquals("Bridge.claim was called outside a mod's constructor, so Hadron can't tell which bridge is "
                + "claiming: call it from your bridge mod's constructor", refused, "the refusal");
        t.assertFalse(Bridge.isInstalled("any_mod"), "nothing was claimed");
    }

    @HadronTest
    @Covers(value = Bridge.class, method = "isInstalled")
    void aBridgeClaimedInItsModsConstructorIsInstalled(TestContext t) {
        // The harness claims hadron_test_target from its constructor, as a real bridge does.
        t.assertTrue(Bridge.isInstalled("hadron_test_target"), "the harness's claim should be installed");
        t.assertFalse(Bridge.isInstalled("hadron_test"), "the claimant isn't itself bridged");
    }
}
