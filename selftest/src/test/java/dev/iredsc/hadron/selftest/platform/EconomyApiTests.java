package dev.iredsc.hadron.selftest.platform;

import dev.iredsc.hadron.api.economy.Currency;
import dev.iredsc.hadron.api.economy.Economy;
import dev.iredsc.hadron.api.economy.EconomyProvider;
import dev.iredsc.hadron.api.economy.EconomyResult;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.resource.Resource;
import dev.iredsc.hadron.api.event.resource.ResourceConsumeEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.resource.ResourceProduceEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Currencies' arithmetic and wording, accounts, results, and the resource events every balance change fires. */
public final class EconomyApiTests {
    @HadronTest
    @Covers(value = Currency.class, method = "id")
    @Covers(value = Currency.class, method = "resource")
    @Covers(value = Currency.class, method = "round")
    @Covers(value = Currency.class, method = "fromMinorUnits")
    @Covers(value = Currency.class, method = "format")
    void aCurrencyRoundsLikeATillAndSaysAmountsForPeople(TestContext t) {
        Currency dollars = Economy.MONEY;
        Currency gems = new Currency("gems", "gem", "gems", 0);
        t.assertEquals(ResourceLocation.fromNamespaceAndPath("hadron", "currency/gems"), gems.id(), "the id");
        t.assertEquals(gems.id(), gems.resource().id(), "the resource events carry the currency's id");
        t.assertTrue(gems.resource().isCurrency(), "a currency's resource says it is one");
        t.assertEquals(new BigDecimal("0.01"), dollars.round(new BigDecimal("0.005")), "half a cent rounds up");
        t.assertEquals(new BigDecimal("3"), gems.round(new BigDecimal("2.5")), "a whole-number currency rounds");
        t.assertEquals(new BigDecimal("1.50"), dollars.fromMinorUnits(150), "150 cents");
        t.assertEquals("1 gem", gems.format(BigDecimal.ONE), "one, singular");
        t.assertEquals("1,500 gems", gems.format(new BigDecimal("1500")), "many, plural with grouping");
        t.assertEquals("12.50 dollars", dollars.format(new BigDecimal("12.5")), "money with its decimals");
    }

    @HadronTest
    @Covers(value = Economy.class, method = "createAccount")
    @Covers(value = Economy.class, method = "hasAccount")
    @Covers(value = Economy.class, method = "has")
    @Covers(value = Economy.class, method = "getCurrencies")
    @Covers(value = Economy.class, method = "deposit")
    @Covers(value = EconomyResult.class, method = "isDone")
    @Covers(value = EconomyResult.class, method = "message")
    @Covers(value = EconomyProvider.class, method = "hasAccount")
    @Covers(value = EconomyProvider.class, method = "createAccount")
    @Covers(value = EconomyProvider.class, method = "getBalance")
    @Covers(value = EconomyProvider.class, method = "setBalances")
    void aTownAccountIsMadeOnceAndHoldsWhatIsPaidIn(TestContext t) {
        // These calls reach the accounts through EconomyProvider, here Hadron's own JSON provider. A test registering
        // a provider of its own would switch the store for the rest of the run: once a plugin has provided accounts,
        // Hadron refuses to fall back to its file, so balances never split between two stores.
        Economy economy = Economy.get();
        UUID town = UUID.randomUUID();
        t.assertFalse(economy.hasAccount(town), "a new town has no account");
        EconomyResult missing = economy.deposit(town, Economy.MONEY, BigDecimal.ONE);
        t.assertTrue(missing instanceof EconomyResult.NoSuchAccount && !missing.isDone(), "paying an account that "
                + "doesn't exist should be NoSuchAccount, but was " + missing);
        t.assertFalse(missing.message().isEmpty(), "every result has a message to show");
        t.assertTrue(economy.createAccount(town), "creating it the first time");
        t.assertFalse(economy.createAccount(town), "creating it again reports it existed");
        t.assertTrue(economy.hasAccount(town), "the town has an account now");
        EconomyResult paid = economy.deposit(town, Economy.MONEY, new BigDecimal("7.25"));
        t.assertTrue(paid.isDone(), "the deposit should be done, but was " + paid);
        t.assertFalse(paid.message().isEmpty(), "a done result says what happened");
        t.assertTrue(economy.has(town, new BigDecimal("7")) && !economy.has(town, new BigDecimal("8")),
                "has in the default currency");
        t.assertTrue(economy.has(town, Economy.MONEY, new BigDecimal("7.25")), "has in a currency, exactly");
        t.assertEquals(0, new BigDecimal("7.25").compareTo(economy.getBalance(town, Economy.MONEY)), "the balance");
        t.assertEquals(Economy.MONEY, economy.getCurrencies().get(0), "getCurrencies lists the default first");
        economy.withdraw(town, Economy.MONEY, new BigDecimal("7.25"));
    }

    @HadronTest
    @Covers(ResourceProduceEvent.class)
    @Covers(ResourceConsumeEvent.class)
    @Covers(value = Economy.class, method = "deposit")
    @Covers(value = Economy.class, method = "withdraw")
    void depositsAndWithdrawalsFireResourceEventsThatCanRefuseThem(TestContext t) {
        TestPlayer alice = t.player("alice");
        Economy economy = Economy.get();
        List<String> heard = new ArrayList<>();
        boolean[] refuse = {false};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ResourceProduceEvent e) {
                heard.add("produce " + e.getAmount() + " " + e.getResource().id() + " " + e.getHolder());
                if (refuse[0]) e.setCancelled(true);
            }

            @EventHandler
            void on(ResourceConsumeEvent e) {
                heard.add("consume " + e.getAmount());
                if (refuse[0]) e.setCancelled(true);
            }
        });
        economy.deposit(alice.uuid(), Economy.MONEY, new BigDecimal("2.50"));
        economy.withdraw(alice.uuid(), Economy.MONEY, new BigDecimal("1"));
        Resource money = Economy.MONEY.resource();
        t.assertEquals(List.of("produce 250 " + money.id() + " " + ResourceHolder.Account.player(alice.uuid()),
                "consume 100"), heard, "the resource events, counted in cents");
        refuse[0] = true;
        EconomyResult refusedDeposit = economy.deposit(alice.uuid(), Economy.MONEY, BigDecimal.TEN);
        EconomyResult refusedWithdraw = economy.withdraw(alice.uuid(), Economy.MONEY, BigDecimal.ONE);
        t.assertTrue(refusedDeposit instanceof EconomyResult.Refused && refusedWithdraw instanceof EconomyResult.Refused,
                "refused changes should be Refused, but were " + refusedDeposit + " and " + refusedWithdraw);
        t.assertBalance(alice, "money", "1.50");
    }
}
