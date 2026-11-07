package dev.iredsc.hadron.neoforge.mixin.net;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Builds a tab list packet with chosen entries; only ever used on a packet Hadron just created. */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface PlayerInfoUpdatePacketAccessor {
    @Mutable
    @Accessor("entries")
    void hadron$setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
