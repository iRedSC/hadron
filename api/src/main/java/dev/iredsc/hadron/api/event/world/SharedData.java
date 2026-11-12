package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.Places;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.DerivedLevelData;

/**
 * Which levels share a level's weather and time. Vanilla gives the overworld the server's main level data and every
 * other level a {@link DerivedLevelData} wrapping that same main data, whose setters do nothing. So a change to the
 * main data reaches every level whose data is it or a {@code DerivedLevelData}, and a change to any other data
 * reaches only the levels holding that very object.
 */
final class SharedData {
    private SharedData() {}

    static Places levels(ServerLevel level) {
        Object own = level.getLevelData();
        MinecraftServer server = level.getServer();
        boolean main = own == server.getWorldData().overworldData();
        List<Place> more = new ArrayList<>();
        for (ServerLevel other : server.getAllLevels()) {
            if (other == level) continue;
            Object data = other.getLevelData();
            if (data == own || (main && data instanceof DerivedLevelData)) more.add(new Place.Level(other));
        }
        return Places.of(new Place.Level(level), more);
    }
}
