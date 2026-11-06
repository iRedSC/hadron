package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.ActionEvent;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * Something that happens to a world as a whole or to an area of it: explosions, weather, time, chunks, portals.
 * Listen for this to hear all of them. Every one says which level and which area, so a region plugin can decide
 * without knowing the leaf: {@link #getArea()} is empty only when the whole level is affected (weather, time).
 */
public abstract class WorldEvent extends ActionEvent {
    private final ServerLevel level;
    private final BoundingBox area;

    /** {@code area} null means the whole level. */
    protected WorldEvent(Actor actor, ServerLevel level, BoundingBox area) {
        super(actor);
        this.level = Objects.requireNonNull(level, "level");
        this.area = area;
    }

    public ServerLevel getLevel() {
        return level;
    }

    /** The blocks this can touch; empty when it affects the whole level, such as weather or time. */
    public Optional<BoundingBox> getArea() {
        return Optional.ofNullable(area);
    }

    /** The blocks within {@code radius} of a point. */
    protected static BoundingBox around(Vec3 center, double radius) {
        int r = (int) Math.ceil(Math.max(0, radius));
        BlockPos c = BlockPos.containing(center);
        return new BoundingBox(c.getX() - r, c.getY() - r, c.getZ() - r, c.getX() + r, c.getY() + r, c.getZ() + r);
    }

    /** One chunk, full height. */
    protected static BoundingBox chunk(ServerLevel level, ChunkPos pos) {
        return new BoundingBox(pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ(),
                pos.getMaxBlockX(), level.getMaxBuildHeight() - 1, pos.getMaxBlockZ());
    }
}
