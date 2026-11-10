package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.HostKey;

/**
 * The {@link HostKey} Hadron's mod claims as it loads, before any plugin runs. A constant, so the mixins, which have no
 * constructor to be handed it, can pass it to {@code Actors.Port} on hot paths for the cost of no more than a constant.
 * Public only for those mixins: a plugin's class loader never links this package (PluginLoader.HOST_PACKAGES).
 */
public final class PortKey {
    public static final HostKey KEY = HostKey.claim();

    private PortKey() {}
}
