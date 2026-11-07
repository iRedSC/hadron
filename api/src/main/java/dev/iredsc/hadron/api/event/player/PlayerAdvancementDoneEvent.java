package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.level.ServerPlayer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/** A player has completed an advancement. After the fact: not cancellable. */
@FiredBy(core = FULL)
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
