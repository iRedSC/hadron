package dev.iredsc.hadron.neoforge.mixin.net;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Entity id to its tracker; the value is a {@link TrackedEntityAccessor}, whose class isn't public. */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("entityMap")
    Int2ObjectMap<Object> hadron$entityMap();
}
