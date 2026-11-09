package dev.iredsc.hadron.api.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.test.Covers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Claims last the JVM, so each test claims mod ids of its own. */
class BridgeTest {
    static final class Ping extends Event {}

    static final class Pong extends Event {}

    /** Stands in for the loader: which mod is constructing, which are installed, who listens, what was fired. */
    static final class Loader implements Bridge.Backend {
        @Nullable String constructing;
        final Set<String> installed = new HashSet<>();
        final Set<Class<?>> heard = new HashSet<>();
        final List<Event> fired = new ArrayList<>();

        @Override
        public @Nullable String loadingMod() {
            return constructing;
        }

        @Override
        public boolean isModLoaded(String modId) {
            return installed.contains(modId);
        }

        @Override
        public boolean listens(Class<? extends Event> type) {
            return heard.contains(type);
        }

        @Override
        public void post(Event event) {
            fired.add(event);
        }
    }

    /** The backend installs once per JVM, as the port's does; each test starts it afresh. */
    private static final Loader LOADER = new Loader();

    static {
        Bridge.Port.install(LOADER);
    }

    private final Loader loader = LOADER;

    @BeforeEach
    void reset() {
        loader.constructing = null;
        loader.installed.clear();
        loader.heard.clear();
        loader.fired.clear();
    }

    @Test
    void aSecondBackendIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Bridge.Port.install(new Loader()));
        assertEquals("Bridge's backend is installed already, by Hadron's loader port; nothing else installs one",
                e.getMessage());
    }

    private Bridge claimAs(String bridge, String mod, String... more) {
        loader.constructing = bridge;
        loader.installed.add(bridge);
        loader.installed.add(mod);
        loader.installed.addAll(List.of(more));
        return Bridge.claim(mod, more);
    }

    @Covers(value = Bridge.class, method = "claim")
    @Covers(value = Bridge.class, method = "owner")
    @Covers(value = Bridge.class, method = "mods")
    @Test
    void theClaimNamesTheConstructingModAndEveryModItCovers() {
        Bridge bridge = claimAs("hadron_mek1", "mek1", "mek1generators");
        assertEquals("hadron_mek1", bridge.owner());
        assertEquals(List.of("mek1", "mek1generators"), bridge.mods());
    }

    @Covers(value = Bridge.class, method = "claim")
    @Test
    void aSecondBridgeForAModFailsNamingBoth() {
        claimAs("first_bridge", "contested");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> claimAs("second_bridge", "contested"));
        assertEquals("Two bridges claim contested: first_bridge and second_bridge. Keep one of them in mods/ and "
                + "remove the other", e.getMessage());
    }

    @Covers(value = Bridge.class, method = "claim")
    @Test
    void oneBridgeClaimsOnce() {
        claimAs("twice_bridge", "twice_a");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> claimAs("twice_bridge", "twice_b"));
        assertEquals("twice_bridge already claimed twice_a: claim every mod a bridge covers in one call, "
                + "Bridge.claim(\"twice_a\", \"twice_b\")", e.getMessage());
        assertFalse(Bridge.isInstalled("twice_b"));
    }

    @Covers(value = Bridge.class, method = "claim")
    @Covers(value = Bridge.class, method = "isInstalled")
    @Test
    void aRefusedClaimClaimsNothing() {
        claimAs("taker", "taken");
        assertThrows(IllegalStateException.class, () -> claimAs("greedy", "free_one", "taken"));
        assertFalse(Bridge.isInstalled("free_one"), "the claim was all or nothing");
        assertTrue(Bridge.isInstalled("taken"));
        assertEquals(List.of("free_one"), claimAs("greedy", "free_one").mods(), "and greedy can still claim");
    }

    @Covers(value = Bridge.class, method = "claim")
    @Test
    void claimingOutsideAConstructorIsRefused() {
        loader.installed.add("lonely");
        loader.constructing = null;
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Bridge.claim("lonely"));
        assertEquals("Bridge.claim was called outside a mod's constructor, so Hadron can't tell which bridge is "
                + "claiming: call it from your bridge mod's constructor", e.getMessage());
        assertFalse(Bridge.isInstalled("lonely"));
    }

    @Covers(value = Bridge.class, method = "claim")
    @Test
    void onlyAnInstalledThirdPartyModCanBeClaimed() {
        loader.constructing = "picky_bridge";
        loader.installed.add("picky_bridge");
        assertEquals("picky_bridge claims absent, which isn't installed: make absent a required dependency of your "
                + "bridge, so the loader refuses the bridge without it. An addon the bridge covers only when it's "
                + "there goes after the first id, Bridge.claim(\"mainmod\", \"absent\")",
                assertThrows(IllegalArgumentException.class, () -> Bridge.claim("absent")).getMessage());
        loader.installed.add("minecraft");
        assertEquals("minecraft isn't a mod a bridge can claim: core covers it. Claim the third-party mod your bridge "
                + "translates",
                assertThrows(IllegalArgumentException.class, () -> Bridge.claim("minecraft")).getMessage());
        assertEquals("picky_bridge claims itself: claim the mod your bridge translates, such as mekanism",
                assertThrows(IllegalArgumentException.class, () -> Bridge.claim("picky_bridge")).getMessage());
        assertEquals("'Mek' isn't a mod id: claim the id the mod is installed under, such as mekanism",
                assertThrows(IllegalArgumentException.class, () -> Bridge.claim("Mek")).getMessage());
        loader.installed.add("dup");
        assertEquals("picky_bridge names dup twice in Bridge.claim: list each mod once",
                assertThrows(IllegalArgumentException.class, () -> Bridge.claim("dup", "dup")).getMessage());
        assertFalse(Bridge.isInstalled("dup"));
    }

    @Covers(value = Bridge.class, method = "claim")
    @Covers(value = Bridge.class, method = "mods")
    @Test
    void anAddonThatIsntInstalledIsLeftOut() {
        loader.constructing = "addon_bridge";
        loader.installed.addAll(List.of("addon_bridge", "addon_main", "addon_one"));
        Bridge bridge = Bridge.claim("addon_main", "addon_one", "addon_absent");
        assertEquals(List.of("addon_main", "addon_one"), bridge.mods());
        assertFalse(Bridge.isInstalled("addon_absent"), "an absent addon stays free for a bridge installed later");
    }

    @Covers(value = Bridge.class, method = "isCoveredByCore")
    @Test
    void coreCoversMinecraftTheLoaderAndHadron() {
        assertTrue(Bridge.isCoveredByCore("minecraft") && Bridge.isCoveredByCore("neoforge")
                && Bridge.isCoveredByCore("hadron"));
        assertFalse(Bridge.isCoveredByCore("mekanism"));
    }

    @Covers(value = Bridge.class, method = "listens")
    @Test
    void listensAsksTheLoaderPerEventClass() {
        Bridge bridge = claimAs("listen_bridge", "listen_mod");
        loader.heard.add(Ping.class);
        assertTrue(bridge.listens(Ping.class));
        assertFalse(bridge.listens(Pong.class));
    }

    @Covers(value = Bridge.class, method = "fire")
    @Test
    void fireRunsTheHandlersAndReturnsTheSameEvent() {
        Bridge bridge = claimAs("fire_bridge", "fire_mod");
        Ping ping = new Ping();
        assertSame(ping, bridge.fire(ping));
        assertEquals(List.of(ping), loader.fired);
    }

    @Test
    void aDataKindMustBeInOneOfTheBridgesMods() {
        Bridge bridge = claimAs("kind_bridge", "colonies", "colonies_extra");
        assertNull(bridge.kindRefusal("colonies"));
        assertNull(bridge.kindRefusal("colonies_extra"));
        assertEquals("kind_bridge fired a data kind in occultism's namespace, but it bridges colonies, colonies_extra: "
                + "name the kind in the namespace of the mod whose data it is, such as colonies:job",
                bridge.kindRefusal("occultism"));
    }

    @Covers(value = Bridge.class, method = "toString")
    @Test
    void itsTextNamesTheBridgeAndItsMods() {
        assertEquals("bridge text_bridge for text_a, text_b", claimAs("text_bridge", "text_a", "text_b").toString());
    }
}
