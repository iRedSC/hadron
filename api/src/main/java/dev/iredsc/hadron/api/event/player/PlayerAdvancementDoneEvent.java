package dev.iredsc.hadron.api.event.player;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.level.ServerPlayer;

/** A player has completed an advancement. After the fact: not cancellable. */
public class PlayerAdvancementDoneEvent extends PlayerEvent {
    private final AdvancementHolder advancement;

    public PlayerAdvancementDoneEvent(ServerPlayer player, AdvancementHolder advancement) {
        super(player);
        this.advancement = advancement;
    }

    /** {@code getAdvancement().id()} is its id, such as {@code minecraft:story/mine_diamond}. */
    public AdvancementHolder getAdvancement() {
        return advancement;
    }
}
