package dev.iredsc.hadron.selftest.platform;

import dev.iredsc.hadron.api.economy.Currency;
import dev.iredsc.hadron.api.economy.Economy;
import dev.iredsc.hadron.api.economy.EconomyResult;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.resource.ResourceTransferEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.Server;
import dev.iredsc.hadron.api.plugin.ServicesManager;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.math.BigDecimal;
import java.util.UUID;
/** The economy as plugins use it: currencies, the default, refusals, accounts and the service lookup. */
public final class EconomyTests {
    private static final Currency GEMS = new Currency("gems", "gem", "gems", 0);

    @HadronTest
    @Covers(value = Economy.class, method = "get")
    @Covers(value = Economy.class, method = "registerCurrency")
    @Covers(value = Economy.class, method = "getCurrency")
    @Covers(value = Economy.class, method = "deposit")
    @Covers(value = Economy.class, method = "withdraw")
    @Covers(value = Economy.class, method = "getBalance")
    void eachCurrencyKeepsItsOwnBalance(TestContext t) {
        TestPlayer alice = t.player("alice");
        Economy economy = Economy.get();
        Currency gems = economy.registerCurrency(t.plugin(), GEMS);
        t.assertTrue(economy.getCurrency("gems").isPresent(), "gems should be registered");
        economy.deposit(alice.uuid(), Economy.MONEY, new BigDecimal("2.50"));
        economy.deposit(alice.uuid(), gems, new BigDecimal("40"));
        economy.withdraw(alice.uuid(), gems, new BigDecimal("15"));
        t.assertBalance(alice, "money", "2.50");
        t.assertBalance(alice, "gems", "25");
    }

    @HadronTest
    @Covers(value = Economy.class, method = "getDefaultCurrency")
    @Covers(value = Economy.class, method = "deposit")
    @Covers(value = Economy.class, method = "getBalance")
    void callsWithoutACurrencyUseTheDefaultMoney(TestContext t) {
        TestPlayer alice = t.player("alice");
        Economy economy = Economy.get();
        t.assertEquals("money", economy.getDefaultCurrency().name(), "the default currency (economy.yml's default)");
        economy.deposit(alice.uuid(), new BigDecimal("3"));
        t.assertBalance(alice, "money", "3.00");
        t.assertEquals(0, economy.getBalance(alice.uuid()).compareTo(new BigDecimal("3")),
                "getBalance without a currency should read money");
    }

    @HadronTest
    @Covers(value = Economy.class, method = "withdraw")
    @Covers(value = EconomyResult.NotEnoughMoney.class, method = "missing")
    void overdrawingIsRefusedAndChangesNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        Economy economy = Economy.get();
        economy.deposit(alice.uuid(), Economy.MONEY, new BigDecimal("5"));
        EconomyResult result = economy.withdraw(alice.uuid(), Economy.MONEY, new BigDecimal("5.01"));
        t.assertTrue(result instanceof EconomyResult.NotEnoughMoney n
                && n.missing().compareTo(new BigDecimal("0.01")) == 0,
                "withdrawing 5.01 of 5 should be NotEnoughMoney, 0.01 short, but was " + result);
        t.assertBalance(alice, "money", "5");
    }

    @HadronTest
    @Covers(ResourceTransferEvent.class)
    @Covers(value = Economy.class, method = "transfer")
    void aListenerRefusingATransferLeavesBothBalances(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Economy economy = Economy.get();
        economy.deposit(alice.uuid(), Economy.MONEY, new BigDecimal("10"));
        int[] heard = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ResourceTransferEvent e) {
                heard[0]++;
                e.setCancelled(true);
            }
        });
        EconomyResult result = economy.transfer(alice.uuid(), bob.uuid(), Economy.MONEY, new BigDecimal("4"));
        t.assertEquals(1, heard[0], "ResourceTransferEvents fired");
        t.assertTrue(result instanceof EconomyResult.Refused, "a refused transfer should be Refused, but was " + result);
        t.assertBalance(alice, "money", "10");
        t.assertBalance(bob, "money", "0");
    }

    @HadronTest
    @Covers(value = Economy.class, method = "hasAccount")
    @Covers(value = Economy.class, method = "deposit")
    void joiningCreatesAnAccountThatOutlivesTheSession(TestContext t) {
        TestPlayer alice = t.player("alice");
        UUID id = alice.uuid();
        alice.disconnect();
        t.tick(2);
        Economy economy = Economy.get();
        t.assertTrue(economy.hasAccount(id), "alice should have an account once she has joined, even offline");
        t.assertFalse(economy.hasAccount(UUID.randomUUID()), "a stranger should have no account");
        EconomyResult paid = economy.deposit(id, Economy.MONEY, new BigDecimal("1"));
        t.assertTrue(paid instanceof EconomyResult.Done, "paying offline alice should work, but was " + paid);
    }

    @HadronTest
    @Covers(value = JavaPlugin.class, method = "getServer")
    @Covers(value = Server.class, method = "getServicesManager")
    @Covers(value = ServicesManager.class, method = "load")
    void theServicesManagerHandsOutTheSameEconomy(TestContext t) {
        Economy loaded = t.plugin().getServer().getServicesManager().load(Economy.class);
        t.assertTrue(loaded != null, "getServicesManager().load(Economy.class) should return the economy, not null");
        t.assertTrue(loaded == Economy.get(), "the service should be Economy.get(), but was " + loaded);
    }
}
