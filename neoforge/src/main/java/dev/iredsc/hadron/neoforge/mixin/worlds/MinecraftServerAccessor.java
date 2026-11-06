package dev.iredsc.hadron.neoforge.mixin.worlds;

import java.util.concurrent.Executor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What a created world's ServerLevel is built with, as vanilla builds the nether and end. */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
    @Accessor("executor")
    Executor hadron$executor();

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess hadron$storageSource();
}
