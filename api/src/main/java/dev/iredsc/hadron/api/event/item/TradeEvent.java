package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * An actor is about to make an exchange with a trader: a player taking a villager's or wandering trader's offer, or a
 * mod's trader. Cancel it and nothing changes hands, and the offer's use count and the trader's experience stay. A
 * shift-click trades as many times as it can and fires once.
 *
 * <p>Core fires it for every merchant menu, vanilla's and mods' that use it. Bridges fire it for their mod's own
 * trading.
 */
public class TradeEvent extends ItemEvent implements Cancellable {
    private final InventoryHolder trader;
    private final Merchant merchant;
    private final MerchantOffer offer;
    private boolean cancelled;

    public TradeEvent(ServerLevel level, InventoryHolder trader, Merchant merchant, MerchantOffer offer, Actor actor) {
        super(level, Objects.requireNonNull(offer, "offer").getResult().copy(), actor);
        this.trader = Objects.requireNonNull(trader, "trader");
        this.merchant = Objects.requireNonNull(merchant, "merchant");
        this.offer = offer;
    }

    /** Who trades with the actor: usually {@code InventoryHolder.Entity} of the villager. */
    public InventoryHolder getTrader() {
        return trader;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    /** The offer taken. Read it; don't change it. */
    public MerchantOffer getOffer() {
        return offer;
    }

    /** What the actor receives, once; same as {@link #getItem()}. */
    public ItemStack getResult() {
        return getItem();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
