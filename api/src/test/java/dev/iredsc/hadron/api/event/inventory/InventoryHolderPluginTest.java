package dev.iredsc.hadron.api.event.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.iredsc.hadron.api.data.NamespacedKey;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.WithMinecraft;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Where protection finds a plugin's container: nowhere, as the plugin's own, or where the inventory it shows is. */
@WithMinecraft
class InventoryHolderPluginTest {
    private static final NamespacedKey SHOP = new NamespacedKey("shops", "shop");

    @Test
    @Covers(value = Place.Nowhere.class, method = "pluginContainer")
    void aPluginsOwnContainerIsNowhereAsThePluginsOwn() {
        assertEquals(Places.of(new Place.Nowhere(Place.Nowhere.PLUGIN_CONTAINER, "shops:shop", Optional.empty())),
                Place.of(new InventoryHolder.Plugin(SHOP)),
                "a container that shows nothing else is the plugin's, for protection to let through");
    }

    @Test
    @Covers(value = Place.class, method = "of")
    void aViewOfAnotherInventoryIsWhereThatInventoryIs() {
        InventoryHolder.Linked ender = new InventoryHolder.Linked(InventoryHolder.Linked.ENDER_CHEST,
                new UUID(0, 1).toString());
        assertEquals(Place.of(ender), Place.of(new InventoryHolder.Plugin(SHOP, Optional.of(ender))),
                "a remote view of an ender chest is placed as the ender chest");

        InventoryHolder.Plugin inner = new InventoryHolder.Plugin(new NamespacedKey("viewer", "inner"),
                Optional.of(InventoryHolder.Unknown.INSTANCE));
        assertEquals(Place.of(InventoryHolder.Unknown.INSTANCE),
                Place.of(new InventoryHolder.Plugin(SHOP, Optional.of(inner))),
                "a view of a view comes down to the last real inventory, here one nothing identified");
    }
}
