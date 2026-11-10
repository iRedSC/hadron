package dev.iredsc.hadron.selftest.entities;

import dev.iredsc.hadron.test.TestPlayer;
import java.util.Optional;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.critereon.DefaultBlockInteractionTrigger;
import net.minecraft.advancements.critereon.ItemUsedOnLocationTrigger;
import net.minecraft.advancements.critereon.PlayerInteractTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;

/**
 * Whether a click counted toward advancements: a throwaway advancement on one vanilla trigger, matching any use, so a
 * test sees exactly what the server counted. Close it, or the trigger keeps a listener for the player.
 */
final class CriterionWatch<T extends CriterionTriggerInstance> implements AutoCloseable {
    private final CriterionTrigger<T> trigger;
    private final PlayerAdvancements progress;
    private final AdvancementHolder advancement;
    private final CriterionTrigger.Listener<T> listener;

    private CriterionWatch(TestPlayer player, CriterionTrigger<T> trigger, T instance, String name) {
        this.trigger = trigger;
        this.progress = player.player().getAdvancements();
        this.advancement = Advancement.Builder.advancement().addCriterion("click", trigger.createCriterion(instance))
                .build(ResourceLocation.fromNamespaceAndPath("hadron_selftest", name));
        this.listener = new CriterionTrigger.Listener<>(instance, advancement, "click");
        trigger.addPlayerListener(progress, listener);
    }

    /** Any right-click on an entity that the server counted as done. */
    static CriterionWatch<PlayerInteractTrigger.TriggerInstance> entityClicks(TestPlayer player) {
        return new CriterionWatch<>(player, CriteriaTriggers.PLAYER_INTERACTED_WITH_ENTITY,
                new PlayerInteractTrigger.TriggerInstance(Optional.empty(), Optional.empty(), Optional.empty()),
                "entity_clicks");
    }

    /** Any use of a block with an empty hand, or that the block handled itself, that the server counted as done. */
    static CriterionWatch<DefaultBlockInteractionTrigger.TriggerInstance> blockUses(TestPlayer player) {
        return new CriterionWatch<>(player, CriteriaTriggers.DEFAULT_BLOCK_USE,
                new DefaultBlockInteractionTrigger.TriggerInstance(Optional.empty(), Optional.empty()), "block_uses");
    }

    /** Any item used on a block that the server counted as done. */
    static CriterionWatch<ItemUsedOnLocationTrigger.TriggerInstance> itemsOnBlocks(TestPlayer player) {
        return new CriterionWatch<>(player, CriteriaTriggers.ITEM_USED_ON_BLOCK,
                new ItemUsedOnLocationTrigger.TriggerInstance(Optional.empty(), Optional.empty()), "items_on_blocks");
    }

    boolean counted() {
        return progress.getOrStartProgress(advancement).isDone();
    }

    /** Forgets what was counted, for the next click. */
    void reset() {
        progress.revoke(advancement, "click");
        trigger.addPlayerListener(progress, listener); // revoking re-registers listeners only for known advancements
    }

    @Override
    public void close() {
        trigger.removePlayerListener(progress, listener);
    }
}
