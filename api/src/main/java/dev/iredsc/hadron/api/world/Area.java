package dev.iredsc.hadron.api.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** A box a plugin watches, from {@link Areas#watch}. Closing it stops the watching; closing it again does nothing. */
public interface Area extends AutoCloseable {
    ServerLevel level();

    /** A copy: moving it doesn't move the area. */
    BoundingBox box();

    /** False once closed, once its plugin disabled, or once its world unloaded. */
    boolean isOpen();

    @Override
    void close();
}
