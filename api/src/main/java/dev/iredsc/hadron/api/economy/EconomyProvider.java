package dev.iredsc.hadron.api.economy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Where accounts are stored, for a plugin that keeps them somewhere other than Hadron's own {@code economy.json} (a
 * database shared by several servers, say). Plugins that pay and charge use {@link Economy}, never this.
 *
 * <pre>{@code
 * public void onLoad() {
 *     getServer().getServicesManager().register(EconomyProvider.class, new MyStore(), this, ServicePriority.Normal);
 * }
 * }</pre>
 *
 * The highest priority provider stores every account; with none registered, Hadron's built-in one does. Balances
 * aren't copied between providers, so pick one before players earn money.
 *
 * <p>The provider only stores. Hadron checks amounts and balances, fires the resource events, and only then calls
 * {@link #setBalances}, so every payment is seen by listeners whatever the provider. A provider must not change
 * balances on its own.
 *
 * <p><b>Threads.</b> Calls come from whatever thread the economy was called on, the server thread or an async task.
 * Hadron never runs two calls for the same account at once, but calls for different accounts can overlap, so the
 * provider must be safe for that. Calls should return quickly; a provider backed by a remote database should cache.
 *
 * <p>Throwing from any method fails that operation with {@link EconomyResult.Unavailable}, and nothing changes.
 */
public interface EconomyProvider {
    boolean hasAccount(UUID account);

    /** Makes an empty account. Returns false if it already existed, leaving it as it was. */
    boolean createAccount(UUID account);

    /**
     * An existing account's balance, already at {@code currency}'s {@link Currency#decimals() scale}; zero if it has
     * never held that currency. Store it by {@link Currency#name()}, which stays the same across reloads.
     */
    BigDecimal getBalance(UUID account, Currency currency);

    /**
     * Stores new balances for existing accounts, all of them or none: a transfer writes payer and payee together, so
     * money is never lost or doubled halfway. Each balance is at the currency's scale and never negative.
     */
    void setBalances(Currency currency, Map<UUID, BigDecimal> balances);
}
