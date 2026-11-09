package dev.iredsc.hadron.api.bridge;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.entity.EntityDataChangeEvent;
import dev.iredsc.hadron.checks.StaticState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A bridge's claim on the mods it translates, and the only way it fires Hadron's events. A bridge mod claims every
 * mod it covers once, in its mod's constructor, and keeps the handle. The first mod is the one the bridge needs; the
 * rest are addons it covers when they are installed:
 *
 * <pre>{@code
 * public MekanismBridge(IEventBus modBus) {
 *     BRIDGE = Bridge.claim("mekanism", "mekanismgenerators", "mekanismtools", "mekanismadditions");
 * }
 * ...
 * if (BRIDGE.listens(ExplosionEvent.class) && BRIDGE.fire(new ExplosionEvent(...)).isCancelled()) return;
 * }</pre>
 *
 * <p>Each mod id has at most one bridge, so two bridges never translate the same mod: a second claim fails the load,
 * naming both. Attribution needs no handle: push actors with {@code Actors} and open operations with
 * {@code Operation}, as core does. See docs/bridges.md for what a bridge hooks and when it fires.
 */
public final class Bridge {
    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    private static final Set<String> NOT_BRIDGEABLE = Set.of("minecraft", "hadron", "neoforge", "fabric");

    @StaticState(reason = "bridges claim their mods once, while mods construct; mods never unload, so a claim lasts "
            + "the JVM")
    private static final Map<String, Bridge> CLAIMS = new HashMap<>();

    @StaticState(reason = "the loader port installs itself once when Hadron's mod constructs")
    private static volatile @Nullable Backend backend;

    private final String owner;
    private final List<String> mods;
    private final Backend firing;

    private Bridge(String owner, List<String> mods, Backend firing) {
        this.owner = owner;
        this.mods = List.copyOf(mods);
        this.firing = firing;
    }

    /**
     * Claims {@code modId}, which must be installed, and each of {@code alsoIfInstalled} that is (Mekanism's
     * generators and tools are optional addons, each its own mod), for the bridge mod being constructed now. Call it
     * once, from your mod's constructor. {@link #mods()} says which were claimed.
     *
     * @throws IllegalStateException when called outside your mod's constructor, before Hadron loads, a second time
     *         from one bridge, or for a mod another bridge already claimed.
     * @throws IllegalArgumentException when {@code modId} isn't installed, or any id isn't a mod id a bridge can claim.
     */
    public static Bridge claim(String modId, String... alsoIfInstalled) {
        Backend port = backend;
        if (port == null) {
            throw new IllegalStateException("Hadron hasn't loaded yet, so it can't take a bridge's claim: make your "
                    + "bridge depend on hadron, loading after it (in neoforge.mods.toml, a [[dependencies.<your mod "
                    + "id>]] entry with modId = \"hadron\", type = \"required\", ordering = \"AFTER\")");
        }
        String claimant = port.loadingMod();
        if (claimant == null) {
            throw new IllegalStateException("Bridge.claim was called outside a mod's constructor, so Hadron can't tell "
                    + "which bridge is claiming: call it from your bridge mod's constructor");
        }
        Set<String> wanted = new LinkedHashSet<>();
        wanted.add(Objects.requireNonNull(modId, "modId"));
        for (String more : alsoIfInstalled) {
            if (!wanted.add(Objects.requireNonNull(more, "alsoIfInstalled"))) {
                throw new IllegalArgumentException(claimant + " names " + more + " twice in Bridge.claim: list each "
                        + "mod once");
            }
        }
        for (String mod : wanted) {
            String why = unbridgeable(mod, claimant);
            if (why != null) throw new IllegalArgumentException(why);
        }
        if (!port.isModLoaded(modId)) {
            throw new IllegalArgumentException(claimant + " claims " + modId + ", which isn't installed: make " + modId
                    + " a required dependency of your bridge, so the loader refuses the bridge without it. An addon "
                    + "the bridge covers only when it's there goes after the first id, Bridge.claim(\"mainmod\", \""
                    + modId + "\")");
        }
        wanted.removeIf(mod -> !port.isModLoaded(mod));
        synchronized (CLAIMS) {
            for (Bridge existing : CLAIMS.values()) {
                if (existing.owner.equals(claimant)) {
                    Set<String> all = new LinkedHashSet<>(existing.mods);
                    all.addAll(wanted);
                    throw new IllegalStateException(claimant + " already claimed " + String.join(", ", existing.mods)
                            + ": claim every mod a bridge covers in one call, Bridge.claim(\""
                            + String.join("\", \"", all) + "\")");
                }
            }
            for (String mod : wanted) {
                Bridge existing = CLAIMS.get(mod);
                if (existing != null) {
                    throw new IllegalStateException("Two bridges claim " + mod + ": " + existing.owner + " and "
                            + claimant + ". Keep one of them in mods/ and remove the other");
                }
            }
            Bridge bridge = new Bridge(claimant, new ArrayList<>(wanted), port);
            for (String mod : wanted) CLAIMS.put(mod, bridge);
            return bridge;
        }
    }

    /** Why {@code mod} can't be claimed by {@code claimant}, or null when it can. */
    private static @Nullable String unbridgeable(String mod, String claimant) {
        if (!MOD_ID.matcher(mod).matches()) {
            return "'" + mod + "' isn't a mod id: claim the id the mod is installed under, such as mekanism";
        }
        if (NOT_BRIDGEABLE.contains(mod)) {
            return mod + " isn't a mod a bridge can claim: core covers it. Claim the third-party mod your bridge "
                    + "translates";
        }
        if (mod.equals(claimant)) {
            return claimant + " claims itself: claim the mod your bridge translates, such as mekanism";
        }
        return null;
    }

    /** Whether core covers this mod id itself (Minecraft, the loader, Hadron), so no bridge for it can exist. */
    public static boolean isCoveredByCore(String modId) {
        return NOT_BRIDGEABLE.contains(modId);
    }

    /** Whether a bridge for that mod id is loaded. A plugin that needs one lists it under bridge-depend. */
    public static boolean isInstalled(String modId) {
        synchronized (CLAIMS) {
            return CLAIMS.containsKey(modId);
        }
    }

    /** The mod ids this bridge covers, in the order it claimed them. */
    public List<String> mods() {
        return mods;
    }

    /** The bridge's own mod id. */
    public String owner() {
        return owner;
    }

    /** Whether any plugin hears this event class right now. Ask first on hot paths, so nothing is built for nobody. */
    public boolean listens(Class<? extends Event> type) {
        return firing.listens(type);
    }

    /**
     * Runs every plugin's handlers for the event on this thread, and returns it so you can read what they changed.
     * Fire before the mod's first change, and if it comes back cancelled, make none.
     *
     * @throws IllegalArgumentException for an {@link EntityDataChangeEvent} whose kind isn't in one of this bridge's
     *         mods' namespaces.
     */
    public <E extends Event> E fire(E event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof EntityDataChangeEvent data) {
            String why = kindRefusal(data.getKind().getNamespace());
            if (why != null) throw new IllegalArgumentException(why);
        }
        firing.post(event);
        return event;
    }

    /** Why a data kind in that namespace can't come from this bridge, or null when it can. */
    @Nullable String kindRefusal(String namespace) {
        if (mods.contains(namespace)) return null;
        return owner + " fired a data kind in " + namespace + "'s namespace, but it bridges " + String.join(", ", mods)
                + ": name the kind in the namespace of the mod whose data it is, such as " + mods.get(0) + ":job";
    }

    /** Such as {@code bridge hadron_mekanism for mekanism, mekanismgenerators}. */
    @Override
    public String toString() {
        return "bridge " + owner + " for " + String.join(", ", mods);
    }

    /** What a bridge needs from the loader port. */
    public interface Backend {
        /**
         * The mod whose loading code is running on this thread (its constructor, or a handler for one of its loading
         * events), or null outside mod loading: once the server runs, never a mod.
         */
        @Nullable String loadingMod();

        boolean isModLoaded(String modId);

        boolean listens(Class<? extends Event> type);

        /** Fires the event if a server is running; does nothing between servers. */
        void post(Event event);
    }

    /** For the loader port only. */
    public static final class Port {
        private Port() {}

        /**
         * Installs the port's backend, once: a second install would let other code decide which mod is claiming.
         *
         * @throws IllegalStateException if one is installed already.
         */
        public static synchronized void install(Backend installed) {
            Objects.requireNonNull(installed, "backend");
            if (backend != null) {
                throw new IllegalStateException("Bridge's backend is installed already, by Hadron's loader port; "
                        + "nothing else installs one");
            }
            backend = installed;
        }
    }
}
