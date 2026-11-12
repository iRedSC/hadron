package dev.iredsc.hadron.api.inventory;

import dev.iredsc.hadron.api.data.NamespacedKey;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * A container a plugin owns: a shop, a kit menu, a frequency chest. Make one with {@code Hadron.createInventory}, like
 * Bukkit's {@code Bukkit.createInventory}, and show it with {@link #open}.
 *
 * <pre>{@code
 * PluginContainer shop = Hadron.createInventory(this, "shop", 3);
 * shop.setItem(13, new ItemStack(Items.DIAMOND));
 * shop.open(player, Component.literal("Shop"));
 * }</pre>
 *
 * <p>Every event about it names it as {@link InventoryHolder.Plugin}, so protection lets it through and the plugin
 * finds its own menus by {@link #getId()}. When the plugin disables, Hadron closes every menu on it, so nobody clicks
 * into a container nothing will save. Its contents are the plugin's to keep: save them in {@code onDisable} and on
 * {@code InventoryCloseEvent}, for example with {@code PersistentData}.
 */
public final class PluginContainer extends SimpleContainer {
    private final JavaPlugin plugin;
    private final InventoryHolder.Plugin holder;
    private final int rows;

    /**
     * @param id a name for this container, unique within the plugin: a-z, 0-9 and {@code / . _ -}
     * @param rows 1 to 6 rows of 9 slots, as a chest shows them
     */
    public PluginContainer(JavaPlugin plugin, String id, int rows) {
        this(plugin, id, rows, Optional.empty());
    }

    /**
     * A container that shows another inventory, such as a remote view of a chest. Events about it carry {@code at},
     * so protection checks that inventory's place instead of trusting the plugin.
     */
    public PluginContainer(JavaPlugin plugin, String id, int rows, InventoryHolder at) {
        this(plugin, id, rows, Optional.of(Objects.requireNonNull(at, "at; use the constructor without it for a "
                + "container that shows no other inventory")));
    }

    private PluginContainer(JavaPlugin plugin, String id, int rows, Optional<InventoryHolder> at) {
        super(checkRows(rows) * 9);
        this.plugin = Objects.requireNonNull(plugin, "plugin; pass your plugin, usually `this`");
        this.holder = new InventoryHolder.Plugin(new NamespacedKey(plugin, id), at);
        this.rows = rows;
    }

    private static int checkRows(int rows) {
        if (rows < 1 || rows > 6) {
            boolean slots = rows % 9 == 0 && rows / 9 >= 1 && rows / 9 <= 6;
            throw new IllegalArgumentException("A plugin container has 1 to 6 rows of 9 slots, as a chest shows them, "
                    + "but was given " + rows + " rows" + (slots ? "; for " + rows + " slots pass " + rows / 9 : ""));
        }
        return rows;
    }

    /** The plugin that made it. */
    public JavaPlugin getPlugin() {
        return plugin;
    }

    /** Its id, in the plugin's namespace; the same as {@code getHolder().id()}. */
    public NamespacedKey getId() {
        return holder.id();
    }

    /** What every event about this container names as its holder. */
    public InventoryHolder.Plugin getHolder() {
        return holder;
    }

    public int getRows() {
        return rows;
    }

    /**
     * Opens it for {@code player} as a chest of {@link #getRows()} rows, closing whatever they had open. Fires
     * {@code InventoryOpenEvent}, with this container's holder.
     *
     * @return false when the player didn't get it: an {@code InventoryOpenEvent} listener refused it, or the plugin
     *     isn't enabled
     */
    public boolean open(ServerPlayer player, Component title) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(title, "title; use Component.literal(\"Shop\")");
        if (!plugin.isEnabled()) return false;
        MenuType<ChestMenu> type = switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
        return player.openMenu(new SimpleMenuProvider(
                (id, inventory, viewer) -> new ChestMenu(type, id, inventory, this, rows), title)).isPresent();
    }

    /** Only while its plugin is enabled, so a menu left open on a disabled plugin's container closes. */
    @Override
    public boolean stillValid(Player player) {
        return plugin.isEnabled();
    }
}
