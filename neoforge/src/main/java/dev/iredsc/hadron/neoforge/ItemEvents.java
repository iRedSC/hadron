package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.inventory.InventoryCloseEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.item.EquipmentChangeEvent;
import dev.iredsc.hadron.api.event.item.ItemDespawnEvent;
import dev.iredsc.hadron.api.event.item.ItemDropEvent;
import dev.iredsc.hadron.api.event.item.ItemPickupEvent;
import dev.iredsc.hadron.neoforge.mixin.item.ItemEntityAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/**
 * Hadron's item and inventory events ({@code dev.iredsc.hadron.api.event.item}, {@code ...event.inventory}) from
 * NeoForge's events and the item mixins. The mixins' logic is in {@link ItemHooks}.
 *
 * <p>Listeners run at LOWEST so they see what other mods decided, and only act on what Hadron's plugins decide.
 */
final class ItemEvents {
    private ItemEvents() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ItemEvents::toss);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ItemEvents::pickup);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ItemEvents::expire);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ItemEvents::startUsing);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ItemEvents::equipment);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ItemEvents::close);
    }

    /**
     * Every way a player drops an item ends here, with the item already out of their inventory. NeoForge would delete
     * it on cancel, so Hadron puts it back instead, and lets what doesn't fit drop rather than vanish.
     */
    private static void toss(ItemTossEvent event) {
        Player player = event.getPlayer();
        ItemEntity entity = event.getEntity();
        if (!(player.level() instanceof ServerLevel level) || !Hooks.listens(ItemDropEvent.class)) return;
        ItemStack stack = entity.getItem();
        ItemDropEvent hadron = Hooks.post(new ItemDropEvent(level, entity.position(), stack, ItemHooks.actor(player)));
        if (!hadron.isCancelled()) return;
        player.getInventory().add(stack); // takes what fits out of stack
        if (stack.isEmpty()) {
            event.setCanceled(true);
        } else {
            entity.setItem(stack); // no room for the rest: it drops rather than vanish
        }
        ItemHooks.resync(player);
    }

    private static void pickup(ItemEntityPickupEvent.Pre event) {
        if (event.canPickup().isFalse() || !Hooks.listens(ItemPickupEvent.class)) return;
        Player player = event.getPlayer();
        ItemEntity item = event.getItemEntity();
        if (!(player.level() instanceof ServerLevel level)) return;
        // NeoForge asks on every touch; only a pickup that will really happen fires (rule 8).
        if (!event.canPickup().isTrue()) {
            if (item.hasPickUpDelay() || item.getTarget() != null && !item.getTarget().equals(player.getUUID())) return;
        }
        if (player.getInventory().getSlotWithRemainingSpace(item.getItem()) < 0
                && player.getInventory().getFreeSlot() < 0) return;
        ItemPickupEvent hadron = Hooks.post(new ItemPickupEvent(level, item, new InventoryHolder.Entity(player),
                ItemHooks.actor(player)));
        if (hadron.isCancelled()) event.setCanPickup(net.neoforged.neoforge.common.util.TriState.FALSE);
    }

    private static void expire(ItemExpireEvent event) {
        ItemEntity item = event.getEntity();
        if (!(item.level() instanceof ServerLevel level) || !Hooks.listens(ItemDespawnEvent.class)) return;
        if (item.getAge() < item.lifespan + event.getExtraLife()) return; // another mod already kept it
        if (Hooks.post(new ItemDespawnEvent(level, item, ItemHooks.nature(item))).isCancelled()) {
            ((ItemEntityAccessor) item).hadron$setAge(0); // a whole new lifespan
        }
    }

    private static void startUsing(LivingEntityUseItemEvent.Start event) {
        if (ItemHooks.mobStartUsing(event.getEntity(), event.getItem(), event.getHand())) event.setCanceled(true);
    }

    private static void equipment(LivingEquipmentChangeEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || !Hooks.listens(EquipmentChangeEvent.class)) {
            return;
        }
        Hooks.post(new EquipmentChangeEvent(level, event.getEntity(), event.getSlot(), event.getFrom(), event.getTo(),
                ItemHooks.actor(event.getEntity())));
    }

    private static void close(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !Hooks.listens(InventoryCloseEvent.class)) return;
        Hooks.post(new InventoryCloseEvent(player, InventoryHolders.ofMenu(event.getContainer(), player),
                event.getContainer(), ItemHooks.actor(player)));
    }
}
