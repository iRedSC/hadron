package dev.iredsc.hadron.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;

/**
 * A created world's level data: shares time, weather and game rules with the overworld like vanilla's nether and
 * end, but keeps its own spawn, saved in Hadron's world definitions.
 */
final class WorldsLevelData extends DerivedLevelData {
    interface SpawnSaver {
        void save(BlockPos pos, float angle);
    }

    private BlockPos spawn;
    private float angle;
    private final SpawnSaver saver;

    WorldsLevelData(WorldData worldData, ServerLevelData overworld, BlockPos spawn, float angle, SpawnSaver saver) {
        super(worldData, overworld);
        this.spawn = spawn;
        this.angle = angle;
        this.saver = saver;
    }

    @Override
    public BlockPos getSpawnPos() {
        return spawn;
    }

    @Override
    public float getSpawnAngle() {
        return angle;
    }

    @Override
    public void setSpawn(BlockPos pos, float angle) {
        this.spawn = pos.immutable();
        this.angle = angle;
        saver.save(this.spawn, angle);
    }
}
