package dev.iredsc.hadron.selftest.server;

import dev.iredsc.hadron.api.event.server.ServerLoadEvent;
import dev.iredsc.hadron.selftest.SelfTestPlugin;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;

public final class ServerLoadTests {
    /**
     * ServerLoadEvent fires once per server start, after every plugin has enabled, and never on a reload. A copy of
     * the plugin enabled at startup heard exactly one, naming this server; a copy enabled later by a reload (another
     * suite ran first, or a warm rerun) heard none.
     */
    @HadronTest
    @Covers(ServerLoadEvent.class)
    void theServerLoadIsHeardOnceAtStartupAndNeverOnAReload(TestContext t) {
        if (SelfTestPlugin.enabledAtStartup) {
            t.assertEquals(1, SelfTestPlugin.serverLoads, "ServerLoadEvents heard by a plugin enabled at startup");
            t.assertTrue(SelfTestPlugin.loadedServer == t.server(), "the ServerLoadEvent should name this server, "
                    + "but named " + SelfTestPlugin.loadedServer);
        } else {
            t.assertEquals(0, SelfTestPlugin.serverLoads, "ServerLoadEvents heard by a plugin enabled by a reload");
        }
    }
}
