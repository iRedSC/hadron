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
        SelfTestPlugin plugin = (SelfTestPlugin) t.plugin();
        if (plugin.enabledAtStartup()) {
            t.assertEquals(1, plugin.serverLoads(), "ServerLoadEvents heard by a plugin enabled at startup");
            t.assertTrue(plugin.loadedServer() == t.server(), "the ServerLoadEvent should name this server, "
                    + "but named " + plugin.loadedServer());
        } else {
            t.assertEquals(0, plugin.serverLoads(), "ServerLoadEvents heard by a plugin enabled by a reload");
        }
    }
}
