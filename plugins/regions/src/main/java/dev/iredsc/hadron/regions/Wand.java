package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.block.BlockUseEvent;
import dev.iredsc.hadron.api.event.player.PlayerQuitEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The selection wand: left-click a block for the first corner, right-click for the second. Only the stick
 * {@code /region wand} hands out is a wand, so no ordinary tool ever stops breaking blocks.
 */
final class Wand implements Listener {
    private static final String MARK = "hadron_regions_wand";

    private final Feedback feedback;
    private final Map<UUID, Selection> selections = new HashMap<>();

    Wand(Feedback feedback) {
        this.feedback = feedback;
    }

    static ItemStack make() {
        ItemStack stick = new ItemStack(Items.STICK);
        stick.set(DataComponents.CUSTOM_NAME, Component.literal("Region wand").withStyle(ChatFormatting.GOLD));
        stick.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        CustomData.update(DataComponents.CUSTOM_DATA, stick, tag -> tag.putBoolean(MARK, true));
        return stick;
    }

    static boolean is(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).contains(MARK);
    }

    Optional<Selection> selection(ServerPlayer player) {
        return Optional.ofNullable(selections.get(player.getUUID()));
    }

    /** Sets a corner as if clicked with the wand: {@code first} for left, otherwise right. */
    void pick(ServerPlayer player, BlockPos pos, boolean first) {
        ResourceLocation world = player.serverLevel().dimension().location();
        Selection old = selections.getOrDefault(player.getUUID(), Selection.empty(world));
        Selection next = first ? old.withFirst(world, pos) : old.withSecond(world, pos);
        selections.put(player.getUUID(), next);
        BoundingBox shown = next.partial().orElseThrow();
        feedback.outline(player, shown, Feedback.Look.SELECTING);
        String what = next.box().map(Selection::size)
                .orElse((first ? "1" : "2") + ": " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        player.displayClientMessage(Component.literal(what).withStyle(ChatFormatting.YELLOW), true);
    }

    void clear(ServerPlayer player) {
        selections.remove(player.getUUID());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void left(BlockBreakEvent e) {
        // Only the player's own click: a machine whose owner holds the wand keeps breaking.
        if (!(e.getActor() instanceof Actor.Player a) || !is(a.player().getMainHandItem())) return;
        e.setCancelled(true);
        pick(a.player(), e.getPos(), true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void right(BlockUseEvent e) {
        if (!(e.getActor() instanceof Actor.Player a) || !is(e.getItem())) return;
        e.setCancelled(true);
        // Vanilla tries the off hand after the main hand; one click picks one corner.
        if (e.getHand() != InteractionHand.MAIN_HAND) return;
        pick(a.player(), e.getPos(), false);
    }

    @EventHandler
    private void quit(PlayerQuitEvent e) {
        selections.remove(e.getPlayer().getUUID());
        feedback.forget(e.getPlayer().getUUID());
    }
}
