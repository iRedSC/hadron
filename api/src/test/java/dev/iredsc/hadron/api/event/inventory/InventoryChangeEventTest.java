package dev.iredsc.hadron.api.event.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent.Change;
import dev.iredsc.hadron.test.WithMinecraft;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/** What an inventory change can say: a real slot, a move that comes out of its source, and stacks no listener edits. */
@WithMinecraft
class InventoryChangeEventTest {
    private static final InventoryHolder CHEST = InventoryHolder.Unknown.INSTANCE;
    private static final Actor NOBODY = Actor.Unknown.INSTANCE;

    private static ItemStack iron(int count) {
        return new ItemStack(Items.IRON_INGOT, count);
    }

    @Test
    void aSlotBelowAnySlotIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Change(CHEST, -2, ItemStack.EMPTY, iron(1)));
        assertTrue(e.getMessage().contains("Slot -2 isn't a slot")
                && e.getMessage().contains("Change.ANY_SLOT"), e.getMessage());
    }

    @Test
    void anySlotSaysOnlyWhatArrivesOrLeaves() {
        IllegalArgumentException both = assertThrows(IllegalArgumentException.class,
                () -> new Change(CHEST, Change.ANY_SLOT, iron(1), iron(2)));
        assertTrue(both.getMessage().contains("Change.arriving(holder, items) or Change.leaving(holder, items)"),
                both.getMessage());
        IllegalArgumentException neither = assertThrows(IllegalArgumentException.class,
                () -> Change.arriving(CHEST, ItemStack.EMPTY));
        assertTrue(neither.getMessage().contains("exactly one of before and after is ItemStack.EMPTY"),
                neither.getMessage());

        Change arriving = Change.arriving(CHEST, iron(4));
        assertTrue(arriving.before().isEmpty());
        assertEquals(4, arriving.after().getCount());
    }

    @Test
    void aChangesStacksAreNewOnEveryReadSoAListenerCantEditThem() {
        ItemStack before = iron(1);
        InventoryChangeEvent event = new InventoryChangeEvent(List.of(new Change(CHEST, 0, before, iron(5))), NOBODY);
        before.setCount(30);

        Change change = event.getChanges().get(0);
        change.after().setCount(64);
        change.before().setCount(64);

        assertEquals(1, change.before().getCount(), "the caller's stack or a listener's edit reached before");
        assertEquals(5, event.getChanges().get(0).after().getCount(), "a listener's edit reached after");
    }

    @Test
    void aMoveTakesWhatMovesOutOfTheSourceSlot() {
        InventoryMoveItemEvent move = new InventoryMoveItemEvent(CHEST, 3, iron(5), CHEST, iron(2), CHEST, NOBODY);

        Change source = move.getChanges().get(0);
        assertEquals(3, source.slot());
        assertEquals(5, source.before().getCount());
        assertEquals(3, source.after().getCount());
        Change destination = move.getChanges().get(1);
        assertEquals(Change.ANY_SLOT, destination.slot());
        assertEquals(2, destination.after().getCount());

        move.getItem().setCount(64);
        assertEquals(2, move.getItem().getCount(), "a listener's edit reached the moving items");
    }

    @Test
    void aMoveOfItemsTheSourceSlotDoesntHoldIsRefused() {
        IllegalArgumentException other = assertThrows(IllegalArgumentException.class,
                () -> new InventoryMoveItemEvent(CHEST, 0, new ItemStack(Items.GOLD_INGOT, 5), CHEST, iron(1), CHEST,
                        NOBODY));
        assertTrue(other.getMessage().contains("aren't in the source slot's stack")
                && other.getMessage().contains("sourceBefore.copyWithCount(n)"), other.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> new InventoryMoveItemEvent(CHEST, 0, iron(1), CHEST, iron(2), CHEST, NOBODY),
                "more moving than the slot held");

        ItemStack named = iron(1);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Sword of Bob"));
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryMoveItemEvent(CHEST, 0, iron(5), CHEST, named, CHEST, NOBODY),
                "the moving item's components differ from the slot's");
    }

    @Test
    void aMoveNeedsARealSlotAndSomethingToMove() {
        IllegalArgumentException anySlot = assertThrows(IllegalArgumentException.class,
                () -> new InventoryMoveItemEvent(CHEST, Change.ANY_SLOT, iron(1), CHEST, iron(1), CHEST, NOBODY));
        assertTrue(anySlot.getMessage().contains("new InventoryMoveItemEvent(source, destination, item, initiator, "
                + "actor)"), anySlot.getMessage());

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> new InventoryMoveItemEvent(CHEST, CHEST, ItemStack.EMPTY, CHEST, NOBODY));
        assertTrue(empty.getMessage().contains("fire no InventoryMoveItemEvent"), empty.getMessage());
    }

    @Test
    void aMoveFromAnUnnamedSlotSaysWhatLeaves() {
        InventoryMoveItemEvent move = new InventoryMoveItemEvent(CHEST, CHEST, iron(2), CHEST, NOBODY);

        Change source = move.getChanges().get(0);
        assertEquals(Change.ANY_SLOT, source.slot());
        assertEquals(2, source.before().getCount());
        assertTrue(source.after().isEmpty());
    }
}
