package dev.iredsc.hadron.neoforge.mixin.world;

import dev.iredsc.hadron.neoforge.AreaIndex;
import dev.iredsc.hadron.neoforge.AreaLevel;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds each level's watched areas where the setBlock hook reads them with one field load, and closes them when the
 * level itself closes: not on {@code LevelEvent.Unload}, which a mod's listener throwing first could skip.
 */
@Mixin(ServerLevel.class)
public abstract class AreasServerLevelMixin implements AreaLevel {
    @Unique
    private @Nullable AreaIndex hadron$areas;
    @Unique
    private int hadron$vanillaWork;

    @Override
    public @Nullable AreaIndex hadron$areas() {
        return hadron$areas;
    }

    @Override
    public void hadron$setAreas(@Nullable AreaIndex areas) {
        hadron$areas = areas;
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void hadron$closeAreas(CallbackInfo ci) {
        AreaIndex.closeAll((ServerLevel) (Object) this);
    }

    @Override
    public int hadron$vanillaWork() {
        return hadron$vanillaWork;
    }

    @Override
    public void hadron$setVanillaWork(int depth) {
        hadron$vanillaWork = depth;
    }
}
