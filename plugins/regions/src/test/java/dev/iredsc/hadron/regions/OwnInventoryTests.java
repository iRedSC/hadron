package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.data.NamespacedKey;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.item.ItemChangeEvent;
import dev.iredsc.hadron.api.event.resource.Resource;
import dev.iredsc.hadron.api.event.resource.ResourceChangeEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.inventory.PluginContainer;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A player's own inventory stands outside every region however a holder reaches it: through a plugin's view of it as
 * well as directly. Bob stands in a fresh region, which keeps visitors from using what is there.
 */
public final class OwnInventoryTests {
    private static TestPlayer visitorInside(TestContext t) {
        TestPlayer alice = RegionsTests.admin(t);
        RegionsTests.define(t, alice, "personal-view", t.pos(-2, 0, -2), t.pos(2, 3, 2));
        return t.player("bob");
    }

    /** A plugin's view of bob's own inventory, as a plugin makes one. */
    private static InventoryHolder.Plugin viewOf(TestContext t, TestPlayer bob) {
        return new InventoryHolder.Plugin(new NamespacedKey(t.plugin(), "personal-view"),
                Optional.of(new InventoryHolder.Entity(bob.player())));
    }

    @HadronTest
    void aPluginsViewOfAPlayersOwnInventoryOpensWhereverTheyStand(TestContext t) {
        TestPlayer bob = visitorInside(t);
        PluginContainer view = new PluginContainer(t.plugin(), "personal-view", 1,
                new InventoryHolder.Entity(bob.player()));
        boolean opened;
        try (var scope = Actors.push(new Actor.Player(bob.player()))) {
            opened = view.open(bob.player(), Component.literal("My inventory"));
        }
        t.assertTrue(opened, "bob opens a view of his own inventory, standing in a region he isn't a member of");
    }

    @HadronTest
    void anItemInAPluginsViewOfAPlayersOwnInventoryIsTheirs(TestContext t) {
        TestPlayer bob = visitorInside(t);
        ItemChangeEvent change = new ItemChangeEvent(t.level(), viewOf(t, bob), new ItemStack(Items.DIAMOND, 8),
                ItemStack.EMPTY, new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(change);
        t.assertFalse(change.isCancelled(), "bob changes an item in his own inventory, reached through a plugin's view");
    }

    @HadronTest
    void aResourceInAPluginsViewOfAPlayersOwnInventoryIsTheirs(TestContext t) {
        TestPlayer bob = visitorInside(t);
        ResourceHolder.Item battery = new ResourceHolder.Item(new ItemStack(Items.DIAMOND), viewOf(t, bob));
        ResourceChangeEvent charge = new ResourceChangeEvent(new Actor.Player(bob.player()), Resource.ENERGY, battery,
                100, 160);
        t.plugin().getServer().getPluginManager().callEvent(charge);
        t.assertFalse(charge.isCancelled(), "bob charges a battery he carries, reached through a plugin's view");
    }
}
