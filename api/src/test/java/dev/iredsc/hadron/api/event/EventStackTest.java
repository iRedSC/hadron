package dev.iredsc.hadron.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.test.WithMinecraft;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/** What an event's stack hands out: a copy no listener can edit through, or the live stack where the event says so. */
@WithMinecraft
class EventStackTest {
    @Test
    void aCopyIsNewOnEveryReadSoAnEditReachesNoOneElse() {
        ItemStack original = new ItemStack(Items.DIAMOND, 3);
        EventStack.Copy held = EventStack.copyOf(original, "item");

        ItemStack first = held.get();
        first.setCount(64);
        original.setCount(1);

        assertEquals(3, held.get().getCount(), "an edit to a read copy or to the original reaches the next read");
        assertNotSame(held.get(), held.get());
    }

    @Test
    void aLiveStackIsTheStackItself() {
        ItemStack original = new ItemStack(Items.DIAMOND, 3);
        EventStack.Live held = EventStack.live(original, "item");

        held.get().setCount(5);

        assertSame(original, held.get());
        assertEquals(5, original.getCount());
    }

    @Test
    void copiesOfAListAreNewOnEveryReadAndTheListCantChange() {
        List<EventStack.Copy> held = EventStack.copiesOf(List.of(new ItemStack(Items.STICK, 2)), "inputs");

        List<ItemStack> read = EventStack.getAll(held);
        read.get(0).setCount(9);

        assertEquals(2, EventStack.getAll(held).get(0).getCount());
        assertThrows(UnsupportedOperationException.class, () -> read.add(ItemStack.EMPTY));
    }

    @Test
    void aMissingStackIsRefusedNamingItAndTheEmptyStack() {
        NullPointerException copy = assertThrows(NullPointerException.class, () -> EventStack.copyOf(null, "before"));
        assertEquals("before is null; pass ItemStack.EMPTY for no item", copy.getMessage());
        NullPointerException live = assertThrows(NullPointerException.class, () -> EventStack.live(null, "item"));
        assertEquals("item is null; pass ItemStack.EMPTY for no item", live.getMessage());

        List<ItemStack> withNull = new ArrayList<>();
        withNull.add(null);
        NullPointerException element = assertThrows(NullPointerException.class,
                () -> EventStack.copiesOf(withNull, "outputs"));
        assertTrue(element.getMessage().startsWith("outputs element is null; pass ItemStack.EMPTY"),
                element.getMessage());
    }
}
