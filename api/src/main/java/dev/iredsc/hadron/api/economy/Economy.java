package dev.iredsc.hadron.api.economy;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The server's money, like Vault's {@code Economy}, with several currencies. Get it with {@link #get()}:
 *
 * <pre>{@code
 * Economy economy = Economy.get();
 * EconomyResult paid = economy.transfer(buyer.getUUID(), shopOwner, new BigDecimal("12.50"));
 * if (!paid.isDone()) buyer.sendSystemMessage(Component.literal(paid.message()));
 * }</pre>
 *
 * <p><b>Accounts</b> are keyed by UUID: a player's is their UUID, and is made the first time anything touches it
 * while they're online. Anything else that holds money (a town, a shop, a bank) picks a UUID, keeps it in its own
 * data, and calls {@link #createAccount} once. Every account can hold every currency.
 *
 * <p><b>Currencies.</b> Hadron's own {@link #MONEY} always exists. Plugins add more with {@link #registerCurrency};
 * a currency goes away while its plugin is disabled, but balances in it are kept and come back with it. Methods
 * without a currency use {@link #getDefaultCurrency()}, which the server owner picks in {@code hadron/economy.yml}
 * ({@code default-currency: money} unless changed), never a plugin, so two plugins can't fight over it.
 *
 * <p><b>Amounts</b> are exact {@link BigDecimal}s. A negative amount, or one with more decimal places than the
 * currency has (0.001 dollars), throws {@link IllegalArgumentException}; round computed amounts with
 * {@link Currency#round} first. Zero is allowed and changes nothing.
 *
 * <p><b>Events.</b> Every change fires a cancellable resource event first, carrying the actor on the stack (the
 * player whose command is running, a machine's owner); cancelled, nothing changes and the result is
 * {@link EconomyResult.Refused}. A deposit fires {@code ResourceProduceEvent}, a withdrawal {@code
 * ResourceConsumeEvent}, a transfer {@code ResourceTransferEvent}, each with {@code ResourceHolder.Account} holders
 * and amounts in the currency's smallest unit. Whichever plugin stores the accounts ({@link EconomyProvider}), the
 * events always fire.
 *
 * <p><b>Threads.</b> Safe from any thread, including async tasks. Events fire on the calling thread, so a listener
 * may run off the server thread; it must not wait on another thread that uses the economy, and it can't change the
 * balance of an account whose change it is hearing (schedule that with {@code getScheduler().runTask} instead).
 *
 * <p><b>When storage fails</b> (the provider plugin is down or reloading, or {@code economy.json} is unreadable),
 * changes return {@link EconomyResult.Unavailable} and reads ({@code getBalance}, {@code has}, {@code hasAccount},
 * {@code createAccount}) throw {@link IllegalStateException} saying why, rather than report a balance of zero.
 */
public interface Economy {
    /** Hadron's own currency, always registered: dollars, with 2 decimal places. */
    Currency MONEY = new Currency("money", "dollar", "dollars", 2);

    /**
     * The running server's economy.
     *
     * @throws IllegalStateException before the server has started or after it stopped
     */
    static Economy get() {
        Economy running = Lifecycle.running;
        if (running == null) {
            throw new IllegalStateException("No server is running, so there's no economy yet; call Economy.get() "
                    + "from onLoad, onEnable or later");
        }
        return running;
    }

    // ---- Currencies ----

    /**
     * Adds a currency until the plugin disables. Call it in onLoad, so other plugins find it in their onEnable.
     * Registering the same one again returns it.
     *
     * @return the currency, to pass to the methods below
     * @throws IllegalStateException if another plugin (or Hadron) already registered that name, or the plugin is
     *                               unloading
     */
    Currency registerCurrency(JavaPlugin plugin, Currency currency);

    /** A registered currency by {@link Currency#name()}, such as {@code "gems"}; empty if no running plugin has it. */
    Optional<Currency> getCurrency(String name);

    /** Every registered currency, the default first. */
    List<Currency> getCurrencies();

    /**
     * The currency methods without one use.
     *
     * @throws IllegalStateException if {@code hadron/economy.yml} names one no running plugin registers; the message
     *                               says which
     */
    Currency getDefaultCurrency();

    // ---- Accounts ----

    boolean hasAccount(UUID account);

    /**
     * Makes an empty account for a town, a shop or anything else that holds money; players get theirs on their own.
     *
     * @return false if it already existed, which is left as it was
     */
    boolean createAccount(UUID account);

    /** The balance in the default currency; zero for an account that doesn't exist. */
    BigDecimal getBalance(UUID account);

    /** The balance in that currency; zero for an account that doesn't exist, or a currency that isn't registered. */
    BigDecimal getBalance(UUID account, Currency currency);

    /** Whether the account holds at least that much of the default currency. */
    boolean has(UUID account, BigDecimal amount);

    boolean has(UUID account, Currency currency, BigDecimal amount);

    // ---- Changing balances ----

    /** Adds money made from nothing (a reward, a sale to the server). Fires {@code ResourceProduceEvent}. */
    EconomyResult deposit(UUID account, BigDecimal amount);

    EconomyResult deposit(UUID account, Currency currency, BigDecimal amount);

    /**
     * Takes money out of existence (a purchase from the server, a fee). Never leaves a balance below zero: with too
     * little, the result is {@link EconomyResult.NotEnoughMoney}. Fires {@code ResourceConsumeEvent}.
     */
    EconomyResult withdraw(UUID account, BigDecimal amount);

    EconomyResult withdraw(UUID account, Currency currency, BigDecimal amount);

    /**
     * Moves money from one account to another in one step, so it is never lost or doubled halfway. With too little,
     * the result is {@link EconomyResult.NotEnoughMoney}. Fires {@code ResourceTransferEvent}. Paying yourself
     * changes nothing and is done.
     */
    EconomyResult transfer(UUID from, UUID to, BigDecimal amount);

    EconomyResult transfer(UUID from, UUID to, Currency currency, BigDecimal amount);

    /** For Hadron's plugin host only. */
    final class Lifecycle {
        @StaticState(reason = "Hadron's economy installs itself when the server starts and clears it when it stops")
        private static volatile @Nullable Economy running;

        private Lifecycle() {}

        public static void install(@Nullable Economy economy) {
            running = economy;
        }

        /** Null while no server is running. */
        public static @Nullable Economy running() {
            return running;
        }
    }
}
