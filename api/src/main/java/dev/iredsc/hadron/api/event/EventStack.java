package dev.iredsc.hadron.api.event;

import java.util.List;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/**
 * How an event holds a stack it hands to listeners. Events and bridges use it; plugins never see it, since every getter
 * returns a plain {@link ItemStack}.
 *
 * <ul>
 *   <li>{@link #copyOf}: the event keeps its own copy and every {@link #get()} returns a new one, so a listener that
 *       edits what it read changes nothing: not the next listener's view, not the code that fired the event.
 *   <li>{@link #live}: the real stack, for the events whose Javadoc says {@code getItem()} is live (a player's drop,
 *       an item entity's stack).
 * </ul>
 *
 * A field of type {@link Copy} can only ever hand out copies, so an event can't forget to copy on read.
 */
public sealed interface EventStack permits EventStack.Copy, EventStack.Live {
    /** The stack: a fresh copy for {@link Copy}, the stack itself for {@link Live}. */
    ItemStack get();

    /**
     * A copy of {@code stack}, copied again on every read.
     *
     * @param name what the stack is, for the error when it's null (such as {@code "before"})
     */
    static Copy copyOf(ItemStack stack, String name) {
        return new Copy(Objects.requireNonNull(stack, () -> name + " is null; pass ItemStack.EMPTY for no item")
                .copy());
    }

    /**
     * The stack itself, handed to every listener. Use only where the event's Javadoc says listeners get the live stack.
     *
     * @param name what the stack is, for the error when it's null (such as {@code "item"})
     */
    static Live live(ItemStack stack, String name) {
        return new Live(Objects.requireNonNull(stack, () -> name + " is null; pass ItemStack.EMPTY for no item"));
    }

    /** {@link #copyOf} for each stack, in order. */
    static List<Copy> copiesOf(List<ItemStack> stacks, String name) {
        Objects.requireNonNull(stacks, () -> name + " is null; pass List.of() for none");
        return stacks.stream().map(s -> copyOf(s, name + " element")).toList();
    }

    /** New copies of each stack, in order, in a list the caller can't change. */
    static List<ItemStack> getAll(List<Copy> stacks) {
        return stacks.stream().map(Copy::get).toList();
    }

    /** A stack the event owns; every read is a new copy. */
    final class Copy implements EventStack {
        private final ItemStack stack;

        private Copy(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public ItemStack get() {
            return stack.copy();
        }

        @Override
        public String toString() {
            return stack.toString();
        }
    }

    /** The live stack, shared with whatever holds it. */
    final class Live implements EventStack {
        private final ItemStack stack;

        private Live(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public ItemStack get() {
            return stack;
        }

        @Override
        public String toString() {
            return stack.toString();
        }
    }
}
