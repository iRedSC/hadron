package dev.iredsc.hadron.api.economy;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * What a deposit, withdrawal or transfer did. Sealed, so a {@code switch} must handle every outcome, and a shop can
 * tell the buyer why:
 *
 * <pre>{@code
 * switch (economy.withdraw(player.getUUID(), price)) {
 *     case EconomyResult.Done done -> giveItem(player);
 *     case EconomyResult.NotEnoughMoney poor -> player.sendSystemMessage(Component.literal(poor.message()));
 *     case EconomyResult failed -> player.sendSystemMessage(Component.literal(failed.message()));
 * }
 * }</pre>
 *
 * Or just {@code if (result.isDone())}, with {@link #message()} for the reason when it isn't.
 */
public sealed interface EconomyResult {
    /** Whether the money moved. */
    default boolean isDone() {
        return this instanceof Done;
    }

    /** A sentence to show the player: what happened, or why it didn't. */
    String message();

    /**
     * The money moved. {@code balance} is the new balance of the account the call took from or gave to: the payer's
     * for a transfer.
     */
    record Done(Currency currency, BigDecimal amount, BigDecimal balance) implements EconomyResult {
        public Done {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(balance, "balance");
        }

        @Override
        public String message() {
            return "Done; the balance is now " + currency.format(balance) + ".";
        }
    }

    /** The account has {@code balance}, less than the {@code needed} amount. Nothing changed. */
    record NotEnoughMoney(Currency currency, BigDecimal balance, BigDecimal needed) implements EconomyResult {
        public NotEnoughMoney {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(balance, "balance");
            Objects.requireNonNull(needed, "needed");
        }

        /** How much more the account needs. */
        public BigDecimal missing() {
            return needed.subtract(balance);
        }

        @Override
        public String message() {
            return "Not enough " + currency.plural() + ": that costs " + currency.format(needed) + ", and there's only "
                    + currency.format(balance) + ".";
        }
    }

    /**
     * Nobody has an account by that UUID. A player online gets one the first time anything touches it; anything else
     * (a town, a shop) needs {@link Economy#createAccount} first. Nothing changed.
     */
    record NoSuchAccount(UUID account) implements EconomyResult {
        public NoSuchAccount {
            Objects.requireNonNull(account, "account");
        }

        @Override
        public String message() {
            return "There's no account " + account + ".";
        }
    }

    /**
     * No running plugin provides that currency, such as one whose plugin is disabled, or a default currency named in
     * {@code hadron/economy.yml} that nothing registers. Balances in it are kept. Nothing changed.
     */
    record NoSuchCurrency(String name) implements EconomyResult {
        public NoSuchCurrency {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String message() {
            return "The currency " + name + " isn't available right now.";
        }
    }

    /** A listener cancelled the resource event for it, such as a protection or anti-dupe plugin. Nothing changed. */
    record Refused(Currency currency, BigDecimal amount) implements EconomyResult {
        public Refused {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(amount, "amount");
        }

        @Override
        public String message() {
            return "Moving " + currency.format(amount) + " was refused.";
        }
    }

    /**
     * Accounts can't be read or written right now: the economy provider failed or its plugin isn't running, Hadron's
     * {@code economy.json} couldn't be read at start, or the server is stopping. {@code reason} is for the server log. Nothing changed.
     */
    record Unavailable(String reason) implements EconomyResult {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String message() {
            return "The economy isn't available right now.";
        }
    }
}
