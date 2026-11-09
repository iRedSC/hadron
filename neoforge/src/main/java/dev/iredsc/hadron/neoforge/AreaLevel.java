package dev.iredsc.hadron.neoforge;

import org.jspecify.annotations.Nullable;

/** What {@code mixin.world.AreasServerLevelMixin} adds to every ServerLevel: its watched areas, null when none. */
public interface AreaLevel {
    @Nullable AreaIndex hadron$areas();

    void hadron$setAreas(@Nullable AreaIndex areas);

    /** How deep vanilla's own work runs in this level now ({@link AreaIndex#enterVanillaWork}). */
    int hadron$vanillaWork();

    void hadron$setVanillaWork(int depth);
}
