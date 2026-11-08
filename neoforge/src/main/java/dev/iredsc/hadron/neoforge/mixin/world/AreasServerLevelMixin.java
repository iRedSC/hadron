package dev.iredsc.hadron.neoforge.mixin.world;

import dev.iredsc.hadron.neoforge.AreaIndex;
import dev.iredsc.hadron.neoforge.AreaLevel;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Holds each level's watched areas where the setBlock hook reads them with one field load. */
@Mixin(ServerLevel.class)
public abstract class AreasServerLevelMixin implements AreaLevel {
    @Unique
    private @Nullable AreaIndex hadron$areas;

    @Override
    public @Nullable AreaIndex hadron$areas() {
        return hadron$areas;
    }

    @Override
    public void hadron$setAreas(@Nullable AreaIndex areas) {
        hadron$areas = areas;
    }
}
