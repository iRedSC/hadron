package dev.iredsc.hadron.neoforge;

import org.jspecify.annotations.Nullable;

/** What {@code mixin.world.AreasServerLevelMixin} adds to every ServerLevel: its watched areas, null when none. */
public interface AreaLevel {
    @Nullable AreaIndex hadron$areas();

    void hadron$setAreas(@Nullable AreaIndex areas);
}
