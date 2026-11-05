package dev.iredsc.hadron.common;

import java.io.File;

/** What only the loader knows. Each loader port implements this once; everything else in common is shared. */
public interface Platform {
    /** The loader's name as plugin.yml's {@code loader} spells it, such as {@code neoforge}. */
    String loader();

    boolean isModLoaded(String modId);

    /** The loader's shared config folder. */
    File configFolder();
}
