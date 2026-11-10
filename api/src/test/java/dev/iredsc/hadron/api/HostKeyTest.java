package dev.iredsc.hadron.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.bridge.Bridge;
import dev.iredsc.hadron.api.data.PersistentData;
import dev.iredsc.hadron.api.economy.Economy;
import dev.iredsc.hadron.api.net.NetBackend;
import dev.iredsc.hadron.api.plugin.Server;
import dev.iredsc.hadron.api.world.Areas;
import dev.iredsc.hadron.api.world.Worlds;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Only the holder of the one {@link HostKey} can replace or clear what the host installed. Here {@link TestHost}
 * holds it, as Hadron's mod does in game; everything else plays a plugin, which can't get it.
 */
class HostKeyTest {
    private static <T> T stub(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            throw new UnsupportedOperationException(method.getName());
        }));
    }

    /** Refused with a message naming what was called and what a plugin uses instead. */
    private static void refused(Executable call, String member, String instead) {
        IllegalStateException e = assertThrows(IllegalStateException.class, call, member);
        assertTrue(e.getMessage().startsWith(member + " is for Hadron's own host"), e.getMessage());
        assertTrue(e.getMessage().contains(instead), e.getMessage());
    }

    @Test
    void claimingTheKeyOnceTheHostHoldsItIsRefusedSayingWhatToUse() {
        assertSame(TestHost.KEY, TestHost.KEY, "the host holds its key");
        IllegalStateException e = assertThrows(IllegalStateException.class, HostKey::claim);
        assertTrue(e.getMessage().contains("Hadron.getServer()"), e.getMessage());
        assertTrue(e.getMessage().contains("@HadronTest"), e.getMessage());
    }

    @Test
    void replacingOrClearingTheServerWithoutTheKeyIsRefusedAndChangesNothing() {
        Server before = Hadron.Lifecycle.running();
        Server other = stub(Server.class);
        refused(() -> Hadron.Lifecycle.install(null, other), "Hadron.Lifecycle.install", "Hadron.getServer()");
        assertSame(before, Hadron.Lifecycle.running(), "still the host's server");
        Hadron.Lifecycle.install(TestHost.KEY, other);
        try {
            refused(() -> Hadron.Lifecycle.install(null, null), "Hadron.Lifecycle.install", "Hadron.getServer()");
            assertSame(other, Hadron.getServer(), "a refused clear leaves the server running");
        } finally {
            Hadron.Lifecycle.install(TestHost.KEY, before);
        }
    }

    @Test
    void aKeyMadeByReflectionIsNotTheHostsKey() throws Exception {
        Constructor<HostKey> make = HostKey.class.getDeclaredConstructor();
        make.setAccessible(true);
        HostKey forged = make.newInstance();
        Server before = Hadron.Lifecycle.running();
        refused(() -> Hadron.Lifecycle.install(forged, stub(Server.class)), "Hadron.Lifecycle.install",
                "Hadron.getServer()");
        assertSame(before, Hadron.Lifecycle.running());
    }

    @Test
    void replacingOrClearingTheEconomyWithoutTheKeyIsRefusedAndChangesNothing() {
        Economy before = Economy.Lifecycle.running();
        Economy other = stub(Economy.class);
        refused(() -> Economy.Lifecycle.install(null, other), "Economy.Lifecycle.install", "Economy.get()");
        assertSame(before, Economy.Lifecycle.running());
        Economy.Lifecycle.install(TestHost.KEY, other);
        try {
            refused(() -> Economy.Lifecycle.install(null, null), "Economy.Lifecycle.install", "EconomyProvider");
            assertSame(other, Economy.get(), "a refused clear leaves the economy installed");
        } finally {
            Economy.Lifecycle.install(TestHost.KEY, before);
        }
    }

    @Test
    void declaringOrReleasingAnotherOwnersNodesWithoutTheKeyIsRefused() {
        ClassLoader owner = new ClassLoader(getClass().getClassLoader()) {};
        String id = "hostkeytest.held";
        Node node = Node.Lifecycle.declare(TestHost.KEY, id, Default.OPS, "", owner);
        refused(() -> Node.Lifecycle.release(null, owner), "Node.Lifecycle.release", "Node.declare");
        assertSame(node, Node.get(id), "a refused release leaves the owner's node declared");
        refused(() -> Node.Lifecycle.declare(null, "hadron.stolen", Default.EVERYONE, "", owner),
                "Node.Lifecycle.declare", "Node.declare");
        refused(() -> Node.Lifecycle.declareImplicit(null, "hostkeytest.implicit", "", owner),
                "Node.Lifecycle.declareImplicit", "Node.declare");
        assertEquals(null, Node.get("hadron.stolen"));
        Node.Lifecycle.release(TestHost.KEY, owner);
        assertEquals(null, Node.get(id), "the host's release still works");
    }

    @Test
    void installingAPortBackendWithoutTheKeyIsRefusedSayingWhatToUse() {
        refused(() -> NetBackend.Port.install(null, stub(NetBackend.class)), "NetBackend.Port.install", "Packets");
        refused(() -> Actors.Port.installMemory(null, stub(Actors.Memory.class)), "Actors.Port.installMemory",
                "Actors.current()");
        refused(() -> PersistentData.Port.installStore(null, stub(PersistentData.Port.Store.class)),
                "PersistentData.Port.installStore", "PersistentData.of");
        refused(() -> Areas.Port.install(null, null), "Areas.Port.install", "Areas.watch");
        refused(() -> Worlds.Port.install(null, null), "Worlds.Port.install", "Worlds");
        refused(() -> Bridge.Port.install(null, null), "Bridge.Port.install", "Bridge.claim");
    }
}
