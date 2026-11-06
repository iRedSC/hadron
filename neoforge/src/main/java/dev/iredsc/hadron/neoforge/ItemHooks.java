package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.inventory.InventoryMoveItemEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryOpenEvent;
import dev.iredsc.hadron.api.event.item.ItemBreakEvent;
import dev.iredsc.hadron.api.event.item.ItemChangeEvent;
import dev.iredsc.hadron.api.event.item.ItemConsumeEvent;
import dev.iredsc.hadron.api.event.item.ItemCraftEvent;
import dev.iredsc.hadron.api.event.item.ItemDamageEvent;
import dev.iredsc.hadron.api.event.item.ItemDropEvent;
import dev.iredsc.hadron.api.event.item.ItemEnchantEvent;
import dev.iredsc.hadron.api.event.item.ItemMergeEvent;
import dev.iredsc.hadron.api.event.item.ItemPickupEvent;
import dev.iredsc.hadron.api.event.item.ItemRepairEvent;
import dev.iredsc.hadron.api.event.item.ItemSmeltEvent;
import dev.iredsc.hadron.api.event.item.ItemUseEvent;
import dev.iredsc.hadron.api.event.item.TradeEvent;
import dev.iredsc.hadron.neoforge.mixin.item.MerchantResultSlotAccessor;
import dev.iredsc.hadron.neoforge.mixin.item.ResultSlotAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.RecipeCraftingHolder;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The item and container chokepoints the mixins call. Each one asks {@link Hooks#listens} first, so a server where no
 * plugin listens builds nothing. Each returns true when a listener cancelled, and the caller then leaves everything as
 * it was.
 */
public final class ItemHooks {
    private ItemHooks() {}

    // ---- Actors -------------------------------------------------------------------------------------------------

    /** Whoever is acting on this thread, else the entity itself. */
    public static Actor actor(Entity entity) {
        Optional<Actor> current = Actors.current();
        return current.isPresent() ? current.get() : derive(entity);
    }

    static Actor derive(Entity entity) {
        if (entity == null) return Actor.Unknown.INSTANCE;
        if (entity instanceof FakePlayer fake) return new Actor.FakePlayer(fake, Actors.remembered(fake));
        if (entity instanceof ServerPlayer player) return new Actor.Player(player);
        return new Actor.EntityActor(entity, Actors.remembered(entity));
    }

    /** Whoever is acting on this thread, else the block entity at that position as a machine. */
    public static Actor machine(Level level, BlockPos pos) {
        Optional<Actor> current = Actors.current();
        if (current.isPresent()) return current.get();
        if (!(level instanceof ServerLevel server)) return Actor.Unknown.INSTANCE;
        BlockEntity be = server.getBlockEntity(pos);
        return new Actor.Machine(server, pos, be == null ? Attribution.none() : Actors.remembered(be));
    }

    /** For a container doing something on its own (a hopper, a hopper minecart). */
    public static Actor container(Container container) {
        if (container instanceof BlockEntity be) return machine(be.getLevel(), be.getBlockPos());
        if (container instanceof Entity entity) return actor(entity);
        return Actors.current().orElse(Actor.Unknown.INSTANCE);
    }

    /** Vanilla's own processes on an entity (despawning, merging): nature, unless something else is acting. */
    static Actor nature(Entity self) {
        Optional<Actor> current = Actors.current();
        if (current.isEmpty()) return Actor.Nature.INSTANCE;
        // While an item entity ticks, the ticking entity is on the stack; its own despawn is still nature's.
        if (current.get() instanceof Actor.EntityActor e && e.entity() == self) return Actor.Nature.INSTANCE;
        return current.get();
    }

    private static InventoryHolder holder(Entity entity) {
        return entity == null ? InventoryHolder.Unknown.INSTANCE : new InventoryHolder.Entity(entity);
    }

    /** Puts a player's client back in step after a cancel it had already predicted. */
    static void resync(Entity entity) {
        if (entity instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            player.containerMenu.sendAllDataToRemote();
        }
    }

    // ---- Drops and pickups --------------------------------------------------------------------------------------

    /** A dispenser or dropper is about to spit one item out; true leaves it in the dispenser. */
    public static boolean dispense(BlockSource source, ItemStack stack) {
        if (stack.isEmpty() || !Hooks.listens(ItemDropEvent.class)) return false;
        Position at = DispenserBlock.getDispensePosition(source);
        return Hooks.post(new ItemDropEvent(source.level(), new Vec3(at.x(), at.y(), at.z()), stack.copyWithCount(1),
                machine(source.level(), source.pos()))).isCancelled();
    }

    /** A hopper or hopper minecart is about to take an item entity; true leaves it on the ground. */
    public static boolean hopperPickup(Container hopper, ItemEntity item) {
        if (!Hooks.listens(ItemPickupEvent.class) || !(item.level() instanceof ServerLevel level)) return false;
        if (!canAccept(hopper, item.getItem(), null)) return false; // nothing would move: rule 8, no phantom pickup
        return Hooks.post(new ItemPickupEvent(level, item, InventoryHolders.of(hopper, null), container(hopper)))
                .isCancelled();
    }

    /** A mob is about to pick an item entity up; true leaves it on the ground. */
    public static boolean mobPickup(Mob mob, ItemEntity item) {
        if (!Hooks.listens(ItemPickupEvent.class) || !(mob.level() instanceof ServerLevel level)) return false;
        return Hooks.post(new ItemPickupEvent(level, item, holder(mob), actor(mob))).isCancelled();
    }

    /** Two item entities are about to merge; true keeps both as they are. */
    public static boolean merge(ItemEntity destination, ItemEntity origin) {
        if (!Hooks.listens(ItemMergeEvent.class) || !(origin.level() instanceof ServerLevel level)) return false;
        return Hooks.post(new ItemMergeEvent(level, origin, destination, nature(origin))).isCancelled();
    }

    // ---- Using items --------------------------------------------------------------------------------------------

    /** A player or fake player is about to use an item in the air; true does nothing. */
    public static boolean use(ItemStack stack, Level level, Player player, InteractionHand hand) {
        if (!(level instanceof ServerLevel server) || !Hooks.listens(ItemUseEvent.class)) return false;
        boolean cancelled = Hooks.post(new ItemUseEvent(server, player, hand, stack, actor(player))).isCancelled();
        if (cancelled) resync(player);
        return cancelled;
    }

    /** A mob is about to start using an item over time; true does nothing. Players come through {@link #use}. */
    public static boolean mobStartUsing(LivingEntity entity, ItemStack stack, InteractionHand hand) {
        if (entity instanceof Player || !(entity.level() instanceof ServerLevel server)
                || !Hooks.listens(ItemUseEvent.class)) return false;
        return Hooks.post(new ItemUseEvent(server, entity, hand, stack, actor(entity))).isCancelled();
    }

    /** Something is about to finish eating or drinking; true leaves the item unconsumed. */
    public static boolean consume(ItemStack stack, Level level, LivingEntity entity) {
        if (!(level instanceof ServerLevel server) || !Hooks.listens(ItemConsumeEvent.class)) return false;
        UseAnim anim = stack.getUseAnimation();
        if (anim != UseAnim.EAT && anim != UseAnim.DRINK) return false; // a spyglass or horn finishing isn't eating
        boolean cancelled = Hooks.post(new ItemConsumeEvent(server, entity, stack, actor(entity))).isCancelled();
        if (cancelled) resync(entity);
        return cancelled;
    }

    // ---- Durability ---------------------------------------------------------------------------------------------

    /** An item is about to lose {@code damage} durability; returns how much it really loses (0 when cancelled). */
    public static int damage(ItemStack stack, ServerLevel level, LivingEntity entity, int damage) {
        if (damage <= 0 || !Hooks.listens(ItemDamageEvent.class)) return damage;
        Actor actor = entity != null ? actor(entity) : Actors.current().orElse(Actor.Unknown.INSTANCE);
        ItemDamageEvent event = Hooks.post(new ItemDamageEvent(level, holder(entity), entity, stack, damage, actor));
        return event.isCancelled() ? 0 : event.getDamage();
    }

    /** An item ran out of durability and is breaking (after). */
    public static void broke(ItemStack stack, ServerLevel level, LivingEntity entity) {
        if (!Hooks.listens(ItemBreakEvent.class)) return;
        Actor actor = entity != null ? actor(entity) : Actors.current().orElse(Actor.Unknown.INSTANCE);
        Hooks.post(new ItemBreakEvent(level, entity, stack, actor));
    }

    /** Mending is about to set an item's damage to {@code newDamage}; true leaves it damaged. */
    public static boolean mend(ServerPlayer player, ItemStack stack, int newDamage) {
        if (!Hooks.listens(ItemRepairEvent.class)) return false;
        ItemStack after = stack.copy();
        after.setDamageValue(newDamage);
        return Hooks.post(new ItemRepairEvent(player.serverLevel(), holder(player), stack, after, actor(player)))
                .isCancelled();
    }

    // ---- Menus --------------------------------------------------------------------------------------------------

    /** A menu is about to open; true keeps it closed. {@code menu} is null for horse inventories. */
    public static boolean open(ServerPlayer player, InventoryHolder holder, AbstractContainerMenu menu) {
        if (!Hooks.listens(InventoryOpenEvent.class)) return false;
        return Hooks.post(new InventoryOpenEvent(player, holder, menu, actor(player))).isCancelled();
    }

    /**
     * A player clicked in a menu. Fires {@link InventoryClickEvent}, then, if the click takes a result, the craft,
     * trade or item change it would make. True when any was cancelled; the click then does nothing.
     */
    public static boolean click(AbstractContainerMenu menu, int slotId, int button, ClickType type,
            ServerPlayer player) {
        boolean clicks = Hooks.listens(InventoryClickEvent.class);
        boolean crafts = Hooks.listens(ItemCraftEvent.class);
        boolean trades = Hooks.listens(TradeEvent.class);
        boolean changes = Hooks.listens(ItemChangeEvent.class);
        if (!clicks && !crafts && !trades && !changes) return false;
        Actor actor = actor(player);
        Slot slot = slotId >= 0 && menu.isValidSlotIndex(slotId) ? menu.getSlot(slotId) : null;
        if (clicks) {
            InventoryHolder holder = slot != null ? InventoryHolders.ofSlot(menu, slot, player)
                    : InventoryHolders.ofMenu(menu, player);
            if (Hooks.post(new InventoryClickEvent(player, holder, menu, slotId, button, type, actor)).isCancelled()) {
                return true;
            }
        }
        if (slot == null || !takesResult(menu, slot, button, type, player)) return false;
        ServerLevel level = player.serverLevel();
        ItemStack result = slot.getItem();

        if (slot instanceof ResultSlot crafting) {
            if (!crafts) return false;
            List<ItemStack> inputs = ones(((ResultSlotAccessor) crafting).hadron$craftSlots().getItems());
            RecipeHolder<?> recipe = slot.container instanceof RecipeCraftingHolder h ? h.getRecipeUsed() : null;
            return craft(level, menu, player, inputs, result, recipe, actor);
        }
        if (slot instanceof MerchantResultSlot) {
            if (!trades || !(slot.container instanceof MerchantContainer trade)) return false;
            MerchantOffer offer = trade.getActiveOffer();
            if (offer == null) return false;
            var merchant = ((MerchantResultSlotAccessor) slot).hadron$merchant();
            InventoryHolder trader = merchant instanceof Entity e ? new InventoryHolder.Entity(e)
                    : InventoryHolders.ofMenu(menu, player);
            return Hooks.post(new TradeEvent(level, trader, merchant, offer, actor)).isCancelled();
        }
        if (menu instanceof AnvilMenu anvil && slotId == anvil.getResultSlot()) {
            if (!changes) return false;
            return change(level, InventoryHolders.ofMenu(menu, player), menu.getSlot(0).getItem(), result,
                    anvil.getCost(), actor);
        }
        if (menu instanceof GrindstoneMenu && slotId == GrindstoneMenu.RESULT_SLOT) {
            if (!changes) return false;
            ItemStack before = menu.getSlot(GrindstoneMenu.INPUT_SLOT).getItem();
            if (before.isEmpty()) before = menu.getSlot(GrindstoneMenu.ADDITIONAL_SLOT).getItem();
            return change(level, InventoryHolders.ofMenu(menu, player), before, result, 0, actor);
        }
        if (!crafts) return false;
        if (menu instanceof SmithingMenu smithing && slotId == smithing.getResultSlot()) {
            return craft(level, menu, player, ones(menu, 0, SmithingMenu.ADDITIONAL_SLOT), result, null, actor);
        }
        if (menu instanceof StonecutterMenu cutter && slotId == StonecutterMenu.RESULT_SLOT) {
            int index = cutter.getSelectedRecipeIndex();
            RecipeHolder<?> recipe = index >= 0 && index < cutter.getRecipes().size()
                    ? cutter.getRecipes().get(index) : null;
            return craft(level, menu, player, ones(menu, StonecutterMenu.INPUT_SLOT, StonecutterMenu.INPUT_SLOT),
                    result, recipe, actor);
        }
        if (menu instanceof LoomMenu loom && slot == loom.getResultSlot()) {
            return craft(level, menu, player, ones(menu, 0, 1), result, null, actor); // banner and dye
        }
        if (menu instanceof CartographyTableMenu && slotId == CartographyTableMenu.RESULT_SLOT) {
            return craft(level, menu, player, ones(menu, 0, CartographyTableMenu.ADDITIONAL_SLOT), result, null,
                    actor);
        }
        return false;
    }

    private static boolean craft(ServerLevel level, AbstractContainerMenu menu, ServerPlayer player,
            List<ItemStack> inputs, ItemStack result, RecipeHolder<?> recipe, Actor actor) {
        return Hooks.post(new ItemCraftEvent(level, InventoryHolders.ofMenu(menu, player), inputs, List.of(result),
                recipe, actor)).isCancelled();
    }

    /** Taking an anvil or grindstone result: the most specific change it makes. */
    private static boolean change(ServerLevel level, InventoryHolder holder, ItemStack before, ItemStack after,
            int cost, Actor actor) {
        ItemChangeEvent event;
        if (!EnchantmentHelper.getEnchantmentsForCrafting(before)
                .equals(EnchantmentHelper.getEnchantmentsForCrafting(after))) {
            event = new ItemEnchantEvent(level, holder, before, after, cost, actor);
        } else if (before.isDamaged() && after.getDamageValue() < before.getDamageValue()) {
            event = new ItemRepairEvent(level, holder, before, after, actor);
        } else {
            event = new ItemChangeEvent(level, holder, before, after, actor);
        }
        return Hooks.post(event).isCancelled();
    }

    /** Whether this click really takes the item in a result slot, as vanilla's doClick would. */
    private static boolean takesResult(AbstractContainerMenu menu, Slot slot, int button, ClickType type,
            Player player) {
        if (!slot.hasItem() || !slot.mayPickup(player)) return false;
        ItemStack item = slot.getItem();
        ItemStack carried = menu.getCarried();
        return switch (type) {
            case PICKUP -> carried.isEmpty() || ItemStack.isSameItemSameComponents(carried, item)
                    && carried.getCount() + item.getCount() <= carried.getMaxStackSize();
            case QUICK_MOVE -> true;
            case SWAP -> (button >= 0 && button < 9 || button == 40) && player.getInventory().getItem(button).isEmpty();
            case THROW -> carried.isEmpty();
            default -> false;
        };
    }

    /** One of each non-empty stack: what one craft uses. */
    private static List<ItemStack> ones(List<ItemStack> stacks) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : stacks) if (!s.isEmpty()) out.add(s.copyWithCount(1));
        return out;
    }

    private static List<ItemStack> ones(AbstractContainerMenu menu, int from, int to) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = from; i <= to; i++) out.add(menu.getSlot(i).getItem());
        return ones(out);
    }

    // ---- Machines -----------------------------------------------------------------------------------------------

    /** A crafter block is about to craft; true consumes and makes nothing. */
    public static boolean crafterCraft(ServerLevel level, BlockPos pos, List<ItemStack> inputs,
            List<ItemStack> outputs, RecipeHolder<?> recipe) {
        if (!Hooks.listens(ItemCraftEvent.class)) return false;
        return Hooks.post(new ItemCraftEvent(level, new InventoryHolder.Block(level, pos), ones(inputs), outputs,
                recipe, machine(level, pos))).isCancelled();
    }

    /** A furnace, campfire or brewing stand is about to finish; true leaves its inputs and makes nothing. */
    public static boolean smelt(Level level, BlockPos pos, List<ItemStack> inputs, List<ItemStack> outputs,
            RecipeHolder<?> recipe) {
        if (!(level instanceof ServerLevel server) || !Hooks.listens(ItemSmeltEvent.class)) return false;
        return Hooks.post(new ItemSmeltEvent(server, new InventoryHolder.Block(server, pos), inputs, List.of(),
                outputs, recipe, machine(level, pos))).isCancelled();
    }

    // ---- Moves between inventories ------------------------------------------------------------------------------

    /**
     * A hopper, hopper minecart or dropper is about to move {@code item} between two vanilla containers. Fires only if
     * the destination has room, so a probe of a full container fires nothing. True leaves the items where they were.
     *
     * @param sourceSlot the slot the item leaves
     * @param sourceCount how many that slot held before the move; the hopper's hot path passes a count, not a copy,
     *     so nothing is allocated unless a plugin listens
     */
    public static boolean move(Container source, int sourceSlot, int sourceCount, Container destination,
            ItemStack item, Direction face, Container initiator) {
        if (item.isEmpty() || !Hooks.listens(InventoryMoveItemEvent.class)) return false;
        if (!canAccept(destination, item, face)) return false;
        ItemStack sourceBefore = item.copyWithCount(sourceCount);
        return Hooks.post(new InventoryMoveItemEvent(InventoryHolders.of(source, null), sourceSlot, sourceBefore,
                InventoryHolders.of(destination, null), item, InventoryHolders.of(initiator, null),
                container(initiator))).isCancelled();
    }

    /** As {@link #move}, into or out of a NeoForge item handler. */
    public static boolean moveHandler(InventoryHolder source, int sourceSlot, ItemStack sourceBefore,
            InventoryHolder destination, IItemHandler destinationHandler, ItemStack item, InventoryHolder initiator,
            Actor actor) {
        if (item.isEmpty() || !Hooks.listens(InventoryMoveItemEvent.class)) return false;
        if (destinationHandler != null && !canAccept(destinationHandler, item)) return false;
        return Hooks.post(new InventoryMoveItemEvent(source, sourceSlot, sourceBefore, destination, item, initiator,
                actor)).isCancelled();
    }

    /** Whether vanilla's HopperBlockEntity.addItem would move at least one of {@code stack} into the container. */
    static boolean canAccept(Container destination, ItemStack stack, Direction face) {
        if (destination instanceof WorldlyContainer worldly && face != null) {
            for (int slot : worldly.getSlotsForFace(face)) if (accepts(destination, stack, slot, face)) return true;
            return false;
        }
        for (int slot = 0; slot < destination.getContainerSize(); slot++) {
            if (accepts(destination, stack, slot, face)) return true;
        }
        return false;
    }

    private static boolean accepts(Container container, ItemStack stack, int slot, Direction face) {
        if (!container.canPlaceItem(slot, stack)) return false;
        if (container instanceof WorldlyContainer worldly && !worldly.canPlaceItemThroughFace(slot, stack, face)) {
            return false;
        }
        ItemStack in = container.getItem(slot);
        return in.isEmpty() || in.getCount() < stack.getMaxStackSize() && ItemStack.isSameItemSameComponents(in, stack);
    }

    /** Whether NeoForge's hopper code would insert {@code stack} into the handler; a simulation, so nothing moves. */
    static boolean canAccept(IItemHandler handler, ItemStack stack) {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack in = handler.getStackInSlot(slot);
            if ((in.isEmpty() || ItemStack.isSameItemSameComponents(in, stack))
                    && handler.insertItem(slot, stack, true).isEmpty()) return true;
        }
        return false;
    }
}
