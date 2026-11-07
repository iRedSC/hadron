package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A mod's network message from this player is about to be handled: a keybind action, a GUI button, a gadget's mode
 * switch. The catch-all for things only the server sees; a bridge usually turns them into clearer events. Fired on
 * the server thread. Cancel it and the mod never handles the message.
 */
@FiredBy(core = FULL,
        coreSees = "every mod packet",
        bridges = "nothing required; a bridge usually turns its mod's packets into clearer events")
public class PlayerModPacketEvent extends PlayerEvent implements Cancellable {
    private final CustomPacketPayload payload;
    private boolean cancelled;

    public PlayerModPacketEvent(ServerPlayer player, CustomPacketPayload payload) {
        super(player);
        this.payload = payload;
    }

    /** The message's id, such as {@code ae2:config_button}; its namespace is the mod's id. */
    public ResourceLocation getPayloadId() {
        return payload.type().id();
    }

    /** The decoded message, the mod's own class. */
    public CustomPacketPayload getPayload() {
        return payload;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
