package dev.iredsc.hadron.api.economy;

import dev.iredsc.hadron.api.event.resource.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * A kind of money, such as coins or gems. Register one with {@link Economy#registerCurrency}; Hadron's own
 * {@link Economy#MONEY} is always there.
 *
 * <pre>{@code
 * Currency gems = Economy.get().registerCurrency(this, new Currency("gems", "gem", "gems", 0));
 * gems.format(new BigDecimal("1500"))   // "1,500 gems"
 * }</pre>
 *
 * <p>Amounts never have more decimal places than {@link #decimals()}: the economy refuses {@code 0.005} dollars
 * rather than guess, so round a computed amount (a 7% tax, say) with {@link #round} first.
 *
 * @param name     the id's last part: lowercase letters, digits, {@code _}, {@code -} or {@code .}, such as
 *                 {@code gems}; balances are stored under it, so keep it the same across versions
 * @param singular what one is called, such as {@code gem}
 * @param plural   what several are called, such as {@code gems}
 * @param decimals how many decimal places an amount has: 0 for gems, 2 for dollars and cents; at most
 *                 {@value #MAX_DECIMALS}
 */
public record Currency(String name, String singular, String plural, int decimals) {
    /** The most decimal places a currency can have. */
    public static final int MAX_DECIMALS = 6;

    public Currency {
        Resource.currency(name); // checks the name, with a message that says how to fix it
        if (singular == null || singular.isBlank() || plural == null || plural.isBlank()) {
            throw new IllegalArgumentException("Currency " + name + " needs a singular and a plural name people "
                    + "read, such as new Currency(\"gems\", \"gem\", \"gems\", 0)");
        }
        if (decimals < 0 || decimals > MAX_DECIMALS) {
            throw new IllegalArgumentException("Currency " + name + " has " + decimals + " decimal places; use 0 "
                    + "for whole amounts (gems), 2 for cents, at most " + MAX_DECIMALS);
        }
    }

    /** {@code hadron:currency/<name>}, the id resource events carry. */
    public ResourceLocation id() {
        return resource().id();
    }

    /**
     * The resource that {@code ResourceTransferEvent}, {@code ResourceProduceEvent} and {@code ResourceConsumeEvent}
     * carry for this currency. Their amounts are in the smallest unit (cents for 2 decimals); turn one back with
     * {@link #fromMinorUnits}.
     */
    public Resource resource() {
        return Resource.currency(name);
    }

    /** Rounds to this currency's decimal places, halves up (0.005 dollars is 0.01), as a till does. */
    public BigDecimal round(BigDecimal amount) {
        return Objects.requireNonNull(amount, "amount").setScale(decimals, RoundingMode.HALF_UP);
    }

    /** An amount from a resource event, which counts in the smallest unit, back in this currency: 150 cents is 1.50. */
    public BigDecimal fromMinorUnits(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, decimals);
    }

    /** For people: {@code "1 gem"}, {@code "1,500 gems"}, {@code "12.50 dollars"}. Rounds as {@link #round} does. */
    public String format(BigDecimal amount) {
        BigDecimal rounded = round(amount);
        DecimalFormat format = new DecimalFormat(decimals == 0 ? "#,##0" : "#,##0." + "0".repeat(decimals),
                DecimalFormatSymbols.getInstance(Locale.ROOT));
        String number = format.format(rounded);
        return number + " " + (rounded.compareTo(BigDecimal.ONE) == 0 ? singular : plural);
    }
}
