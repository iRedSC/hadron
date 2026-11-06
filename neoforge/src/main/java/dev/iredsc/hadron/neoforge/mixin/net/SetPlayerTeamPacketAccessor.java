package dev.iredsc.hadron.neoforge.mixin.net;

import java.util.Collection;
import java.util.Optional;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Copies a team packet with other member names, for name tags. */
@Mixin(ClientboundSetPlayerTeamPacket.class)
public interface SetPlayerTeamPacketAccessor {
    @Accessor("method")
    int hadron$method();

    @Invoker("<init>")
    static ClientboundSetPlayerTeamPacket hadron$create(String name, int method,
            Optional<ClientboundSetPlayerTeamPacket.Parameters> parameters, Collection<String> players) {
        throw new AssertionError();
    }
}
