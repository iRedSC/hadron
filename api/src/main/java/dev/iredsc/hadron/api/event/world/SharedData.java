package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.Places;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;

/** Which levels share a level's weather and time: those whose data is, or wraps, its own. */
final class SharedData {
    private SharedData() {}

    static Places levels(ServerLevel level) {
        Object own = level.getLevelData();
        List<Place> more = new ArrayList<>();
        MinecraftServer server = level.getServer();
        for (ServerLevel other : server.getAllLevels()) {
            if (other == level) continue;
            Object data = other.getLevelData();
            if (data == own) {
                more.add(new Place.Level(other));
            } else if (data instanceof DerivedLevelData derived) {
                Object wrapped = wrapped(derived);
                if (wrapped == own) {
                    more.add(new Place.Level(other));
                } else if (wrapped == null) {
                    // What it wraps can't be read: it may see the change, and nobody can say.
                    more.add(new Place.Unknown(new Place.Level(other), Place.Unknown.Reason.UNPLACEABLE_OPERATION));
                }
            }
        }
        return Places.of(new Place.Level(level), more);
    }

    /** The data a derived level's wraps, read from its field by type; null when it can't be read. */
    private static @org.jspecify.annotations.Nullable Object wrapped(DerivedLevelData derived) {
        for (Field f : DerivedLevelData.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || !ServerLevelData.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                return f.get(derived);
            } catch (ReflectiveOperationException | RuntimeException hidden) {
                return null;
            }
        }
        return null;
    }
}
