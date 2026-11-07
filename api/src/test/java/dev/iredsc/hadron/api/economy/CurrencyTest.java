package dev.iredsc.hadron.api.economy;

import dev.iredsc.hadron.test.Covers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CurrencyTest {
    private static final Currency GEMS = new Currency("gems", "gem", "gems", 0);
    private static final Currency DOLLARS = Economy.MONEY;

    @ParameterizedTest
    @CsvSource({
        "1, 1 gem",
        "0, 0 gems",
        "2, 2 gems",
        "1500, '1,500 gems'",
        "1234567, '1,234,567 gems'",
        "0.6, 1 gem",
        "1.4, 1 gem",
        "-3, -3 gems",
    })
    @Covers(value = Currency.class, method = "format")
    void wholeCurrenciesFormatWithGroupingAndTheRightWord(String amount, String expected) {
        assertEquals(expected, GEMS.format(new BigDecimal(amount)));
    }

    @ParameterizedTest
    @CsvSource({
        "12.5, 12.50 dollars",
        "1, 1.00 dollar",
        "0.005, 0.01 dollars",
        "0.004, 0.00 dollars",
        "1000000.1, '1,000,000.10 dollars'",
    })
    @Covers(value = Currency.class, method = "format")
    void decimalCurrenciesAlwaysShowTheirPlaces(String amount, String expected) {
        assertEquals(expected, DOLLARS.format(new BigDecimal(amount)));
    }

    @Covers(value = Currency.class, method = "round")
    @Test
    void roundingIsHalfUpAsATillDoes() {
        assertEquals(new BigDecimal("0.01"), DOLLARS.round(new BigDecimal("0.005")));
        assertEquals(new BigDecimal("0.00"), DOLLARS.round(new BigDecimal("0.0049")));
        assertEquals(new BigDecimal("3"), GEMS.round(new BigDecimal("2.5")));
        assertEquals(new BigDecimal("-0.01"), DOLLARS.round(new BigDecimal("-0.005")));
        assertThrows(NullPointerException.class, () -> DOLLARS.round(null));
    }

    @Covers(value = Currency.class, method = "fromMinorUnits")
    @Test
    void minorUnitsCountTheSmallestUnit() {
        assertEquals(new BigDecimal("1.50"), DOLLARS.fromMinorUnits(150));
        assertEquals(new BigDecimal("150"), GEMS.fromMinorUnits(150));
        assertEquals(new BigDecimal("92233720368547758.07"), DOLLARS.fromMinorUnits(Long.MAX_VALUE));
    }

    @Covers(value = Currency.class, method = "id")
    @Covers(value = Currency.class, method = "resource")
    @Test
    void aCurrencysIdAndResourceAreUnderHadronsNamespace() {
        assertEquals("hadron:currency/gems", GEMS.id().toString());
        assertTrue(GEMS.resource().isCurrency());
        assertEquals("gems", GEMS.resource().unit());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Gems", "my gems", "a/b", "a:b"})
    void anInvalidNameIsRefusedWithAnExample(String name) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Currency(name, "gem", "gems", 0));
        assertTrue(e.getMessage().contains("\"coins\""), e.getMessage());
    }

    @Test
    void aNullNameIsRefusedTheSameWay() {
        assertThrows(IllegalArgumentException.class, () -> new Currency(null, "gem", "gems", 0));
    }

    @Test
    void blankDisplayNamesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Currency("gems", " ", "gems", 0));
        assertThrows(IllegalArgumentException.class, () -> new Currency("gems", "gem", null, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Currency.MAX_DECIMALS + 1})
    void decimalsOutsideZeroToSixAreRefused(int decimals) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Currency("gems", "gem", "gems", decimals));
        assertTrue(e.getMessage().contains("at most " + Currency.MAX_DECIMALS), e.getMessage());
    }
    @Covers(value = Currency.class, method = "decimals")
    @ParameterizedTest
    @ValueSource(ints = {0, Currency.MAX_DECIMALS})
    void zeroToSixDecimalsAreAccepted(int decimals) {
        assertEquals(decimals, new Currency("gems", "gem", "gems", decimals).decimals());
    }

    @Test
    void moneyIsDollarsWithCents() {
        assertEquals(new Currency("money", "dollar", "dollars", 2), Economy.MONEY);
    }
}
