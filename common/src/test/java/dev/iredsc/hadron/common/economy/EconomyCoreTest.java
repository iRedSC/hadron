package dev.iredsc.hadron.common.economy;

import dev.iredsc.hadron.common.TestHost;
import dev.iredsc.hadron.test.Covers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.economy.Currency;
import dev.iredsc.hadron.api.economy.Economy;
import dev.iredsc.hadron.api.economy.EconomyProvider;
import dev.iredsc.hadron.api.economy.EconomyResult;
import dev.iredsc.hadron.api.event.resource.ResourceChangeEvent;
import dev.iredsc.hadron.api.event.resource.ResourceConsumeEvent;
import dev.iredsc.hadron.api.event.resource.ResourceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.resource.ResourceProduceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceTransferEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.common.TestPlugins;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every decision the economy makes, with the server stood in for: currencies, checks, events, providers. */
class EconomyCoreTest {
    private static final Currency MONEY = Economy.MONEY;
    private static final Currency GEMS = new Currency("gems", "gem", "gems", 0);
    private static final UUID ANN = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID NOBODY = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    /** The server as the economy sees it. */
    static final class FakeHost implements EconomyCore.Host {
        final Set<JavaPlugin> alive = new HashSet<>();
        final Set<UUID> online = new HashSet<>();
        final List<ResourceEvent> events = new ArrayList<>();
        Consumer<ResourceEvent> listener = e -> {};
        EconomyProvider provider;

        @Override
        public boolean isAlive(JavaPlugin plugin) {
            return alive.contains(plugin);
        }

        @Override
        public void post(ResourceEvent event) {
            events.add(event);
            listener.accept(event);
        }

        @Override
        public EconomyProvider registeredProvider() {
            return provider;
        }

        @Override
        public boolean isOnline(UUID account) {
            return online.contains(account);
        }
    }

    /** A plugin's account store, in memory. */
    static class MapProvider implements EconomyProvider {
        final Map<UUID, Map<String, BigDecimal>> accounts = new HashMap<>();

        @Override
        public boolean hasAccount(UUID account) {
            return accounts.containsKey(account);
        }

        @Override
        public boolean createAccount(UUID account) {
            return accounts.putIfAbsent(account, new HashMap<>()) == null;
        }

        @Override
        public BigDecimal getBalance(UUID account, Currency currency) {
            return accounts.get(account).getOrDefault(currency.name(), BigDecimal.ZERO.setScale(currency.decimals()));
        }

        @Override
        public void setBalances(Currency currency, Map<UUID, BigDecimal> balances) {
            balances.forEach((id, b) -> accounts.get(id).put(currency.name(), b));
        }
    }

    @TempDir
    Path dir;

    private final FakeHost host = new FakeHost();
    private final ManualSaveTimer timer = new ManualSaveTimer();
    private EconomyCore economy;
    private JavaPlugin gemsPlugin;

    private EconomyCore start() {
        economy = new EconomyCore(TestHost.KEY, host, dir.toFile(),
                new JsonEconomyProvider(dir.resolve("economy.json"), timer));
        economy.start();
        return economy;
    }

    private JavaPlugin plugin(String name) {
        JavaPlugin plugin = TestPlugins.create(name, null);
        host.alive.add(plugin);
        return plugin;
    }

    @BeforeEach
    void setUp() {
        gemsPlugin = plugin("Gems");
        host.online.add(ANN);
        host.online.add(BO);
    }

    @AfterEach
    void tearDown() {
        if (economy != null) economy.close();
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    private void fund(UUID account, String amount) {
        assertInstanceOf(EconomyResult.Done.class, economy.deposit(account, money(amount)));
        host.events.clear();
    }

    // ---- lifecycle ----

    @Covers(value = Economy.class, method = "get")
    @Test
    void startMakesEconomyGetWorkAndCloseUndoesIt() {
        start();
        assertSame(economy, Economy.Lifecycle.running());
        economy.close();
        assertNull(Economy.Lifecycle.running());
        economy.close(); // twice is harmless
    }

    @Covers(value = Economy.class, method = "deposit")
    @Test
    void afterCloseChangesAreUnavailableAndReadsSayTheServerIsStopping() {
        start();
        economy.close();
        EconomyResult result = economy.deposit(ANN, money("1.00"));
        assertEquals(new EconomyResult.Unavailable("the server is stopping"), result);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> economy.getBalance(ANN, MONEY));
        assertTrue(e.getMessage().contains("stopping"), e.getMessage());
    }

    @Covers(value = Economy.class, method = "deposit")
    @Test
    void closingSavesBalancesForTheNextStart() {
        start();
        fund(ANN, "4.20");
        economy.close();
        economy = null;
        EconomyCore again = new EconomyCore(TestHost.KEY, host, dir.toFile(),
                new JsonEconomyProvider(dir.resolve("economy.json"), new ManualSaveTimer()));
        again.start();
        try {
            assertEquals(money("4.20"), again.getBalance(ANN, MONEY));
        } finally {
            again.close();
        }
    }

    // ---- the default currency ----

    @Covers(value = Economy.class, method = "getDefaultCurrency")
    @Test
    void theFirstStartWritesAConfigNamingMoney() throws Exception {
        start();
        String config = Files.readString(dir.resolve("economy.yml"));
        assertTrue(config.contains("default-currency: money"), config);
        assertEquals(MONEY, economy.getDefaultCurrency());
    }

    @Covers(value = Economy.class, method = "getDefaultCurrency")
    @Covers(value = Economy.class, method = "getCurrencies")
    @Test
    void aPluginCurrencyCanBeTheDefault() throws Exception {
        Files.writeString(dir.resolve("economy.yml"), "default-currency: '  gems '\n");
        start();
        economy.registerCurrency(gemsPlugin, GEMS);
        assertEquals(GEMS, economy.getDefaultCurrency());
        assertEquals(List.of(GEMS, MONEY), economy.getCurrencies(), "the default comes first");
        fund(ANN, "3");
        assertEquals(money("3"), economy.getBalance(ANN, GEMS));
        assertEquals(money("0.00"), economy.getBalance(ANN, MONEY));
    }

    @Covers(value = Economy.class, method = "getDefaultCurrency")
    @Test
    void aMissingDefaultSaysWhichFileAndWhatIsRegistered() throws Exception {
        Files.writeString(dir.resolve("economy.yml"), "default-currency: gems\n");
        start();
        IllegalStateException e = assertThrows(IllegalStateException.class, economy::getDefaultCurrency);
        assertTrue(e.getMessage().contains("economy.yml sets default-currency: gems"), e.getMessage());
        assertTrue(e.getMessage().contains("(registered: money)"), e.getMessage());
        assertTrue(e.getMessage().contains("Enable the plugin that adds it, or change default-currency"),
                e.getMessage());
    }

    @Covers(value = Economy.class, method = "deposit")
    @Covers(value = Economy.class, method = "getBalance")
    @Covers(value = Economy.class, method = "has")
    @Test
    void withoutItsDefaultTheShortFormsRefuseRatherThanGuess() throws Exception {
        Files.writeString(dir.resolve("economy.yml"), "default-currency: gems\n");
        start();
        assertEquals(new EconomyResult.NoSuchCurrency("gems"), economy.deposit(ANN, money("1")));
        assertEquals(new EconomyResult.NoSuchCurrency("gems"), economy.withdraw(ANN, money("1")));
        assertEquals(new EconomyResult.NoSuchCurrency("gems"), economy.transfer(ANN, BO, money("1")));
        assertEquals(BigDecimal.ZERO, economy.getBalance(ANN));
        assertFalse(economy.has(ANN, BigDecimal.ZERO));
        assertTrue(host.events.isEmpty());
    }

    @Covers(value = Economy.class, method = "getDefaultCurrency")
    @Test
    void aBlankDefaultLineFallsBackToMoney() throws Exception {
        Files.writeString(dir.resolve("economy.yml"), "other: 1\n");
        start();
        assertEquals(MONEY, economy.getDefaultCurrency());
    }

    // ---- currencies ----

    @Covers(value = Economy.class, method = "registerCurrency")
    @Covers(value = Economy.class, method = "getCurrency")
    @Test
    void registeringACurrencyMakesItUsable() {
        start();
        assertSame(GEMS, economy.registerCurrency(gemsPlugin, GEMS));
        assertEquals(GEMS, economy.getCurrency("gems").orElseThrow());
        assertEquals(List.of(MONEY, GEMS), economy.getCurrencies());
    }

    @Covers(value = Economy.class, method = "registerCurrency")
    @Test
    void registeringTheSameCurrencyAgainFromTheSamePluginIsANoOp() {
        start();
        economy.registerCurrency(gemsPlugin, GEMS);
        assertEquals(GEMS, economy.registerCurrency(gemsPlugin, new Currency("gems", "gem", "gems", 0)));
    }

    @Covers(value = Economy.class, method = "registerCurrency")
    @Test
    void redefiningYourOwnCurrencyIsRefusedShowingTheOldDefinition() {
        start();
        economy.registerCurrency(gemsPlugin, GEMS);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> economy.registerCurrency(gemsPlugin, new Currency("gems", "gem", "gems", 2)));
        assertTrue(e.getMessage().contains("already registered by Gems as Currency[name=gems"), e.getMessage());
    }

    @Covers(value = Economy.class, method = "registerCurrency")
    @Test
    void anotherPluginsCurrencyNameIsRefusedNamingTheOwner() {
        start();
        economy.registerCurrency(gemsPlugin, GEMS);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> economy.registerCurrency(plugin("Rival"), GEMS));
        assertTrue(e.getMessage().contains("already registered by Gems; pick another name"), e.getMessage());
        assertTrue(e.getMessage().contains("Economy.get().getCurrency(\"gems\")"), e.getMessage());
    }

    @Covers(value = Economy.class, method = "registerCurrency")
    @Test
    void moneyBelongsToHadron() {
        start();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> economy.registerCurrency(gemsPlugin, new Currency("money", "buck", "bucks", 2)));
        assertTrue(e.getMessage().contains("already registered by Hadron"), e.getMessage());
    }

    @Covers(value = Economy.class, method = "registerCurrency")
    @Test
    void aPluginThatIsUnloadingCantRegisterACurrency() {
        start();
        host.alive.remove(gemsPlugin);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> economy.registerCurrency(gemsPlugin, GEMS));
        assertTrue(e.getMessage().contains("register them in onLoad"), e.getMessage());
        assertTrue(economy.getCurrency("gems").isEmpty());
    }

    @Covers(value = Economy.class, method = "registerCurrency")
    @Test
    void aDisabledPluginsCurrenciesGoButTheirBalancesStay() {
        start();
        economy.registerCurrency(gemsPlugin, GEMS);
        assertInstanceOf(EconomyResult.Done.class, economy.deposit(ANN, GEMS, money("5")));
        economy.forget(gemsPlugin);
        assertTrue(economy.getCurrency("gems").isEmpty());
        assertEquals(new EconomyResult.NoSuchCurrency("gems"), economy.deposit(ANN, GEMS, money("1")));
        assertEquals(money("0"), economy.getBalance(ANN, GEMS));
        JavaPlugin reloaded = plugin("Gems");
        economy.registerCurrency(reloaded, GEMS);
        assertEquals(money("5"), economy.getBalance(ANN, GEMS), "the reloaded plugin finds its balances again");
    }

    @Covers(value = Economy.class, method = "registerCurrency")
    @Test
    void forgettingAPluginLeavesOtherPluginsCurrencies() {
        start();
        economy.registerCurrency(gemsPlugin, GEMS);
        JavaPlugin other = plugin("Coins");
        economy.registerCurrency(other, new Currency("coins", "coin", "coins", 0));
        economy.forget(other);
        assertTrue(economy.getCurrency("gems").isPresent());
        assertTrue(economy.getCurrency("money").isPresent());
    }

    @Covers(value = Economy.class, method = "getBalance")
    @Test
    void aCurrencyHeldFromBeforeAReloadUsesTheCurrentDefinition() {
        start();
        economy.registerCurrency(gemsPlugin, GEMS);
        Currency stale = new Currency("gems", "old gem", "old gems", 3); // same name, older shape
        economy.deposit(ANN, GEMS, money("2"));
        assertEquals(money("2"), economy.getBalance(ANN, stale), "read at the registered 0 decimals");
    }

    // ---- amounts ----

    @Covers(value = Economy.class, method = "deposit")
    @Test
    void moreDecimalPlacesThanTheCurrencyHasIsRefusedPointingAtRound() {
        start();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> economy.deposit(ANN, MONEY, money("0.005")));
        assertTrue(e.getMessage().contains("money has 2 decimal place(s), but the amount is 0.005"), e.getMessage());
        assertTrue(e.getMessage().contains("currency.round(amount)"), e.getMessage());
        assertTrue(host.events.isEmpty());
    }

    @Covers(value = Economy.class, method = "deposit")
    @ParameterizedTest
    @ValueSource(strings = {"1", "1.5", "1.50", "1.500000"})
    void trailingZerosDontCountAsDecimalPlaces(String amount) {
        start();
        EconomyResult result = economy.deposit(ANN, MONEY, money(amount));
        assertEquals(money(amount).setScale(2), ((EconomyResult.Done) result).amount());
    }

    @Covers(value = Economy.class, method = "withdraw")
    @Test
    void negativeAmountsAreRefusedPointingAtTheOtherCall() {
        start();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> economy.withdraw(ANN, MONEY, money("-1")));
        assertTrue(e.getMessage().contains("use withdraw to take money and deposit to give it"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> economy.has(ANN, MONEY, money("-1")));
    }

    @Covers(value = Economy.class, method = "deposit")
    @Test
    void aBadAmountIsRefusedEvenForAnUnregisteredCurrency() {
        start();
        assertThrows(IllegalArgumentException.class, () -> economy.deposit(ANN, GEMS, money("0.5")));
        assertThrows(IllegalArgumentException.class, () -> economy.withdraw(ANN, GEMS, money("0.5")));
        assertThrows(IllegalArgumentException.class, () -> economy.transfer(ANN, BO, GEMS, money("0.5")));
        assertEquals(new EconomyResult.NoSuchCurrency("gems"), economy.deposit(ANN, GEMS, money("1")));
        assertEquals(new EconomyResult.NoSuchCurrency("gems"), economy.withdraw(ANN, GEMS, money("1")));
        assertEquals(new EconomyResult.NoSuchCurrency("gems"), economy.transfer(ANN, BO, GEMS, money("1")));
    }

    @Covers(value = Economy.class, method = "deposit")
    @Test
    void nullsAreRefused() {
        start();
        assertThrows(NullPointerException.class, () -> economy.deposit(null, MONEY, money("1")));
        assertThrows(NullPointerException.class, () -> economy.deposit(ANN, null, money("1")));
        assertThrows(NullPointerException.class, () -> economy.deposit(ANN, MONEY, null));
        assertThrows(NullPointerException.class, () -> economy.transfer(ANN, null, MONEY, money("1")));
    }

    // ---- accounts ----

    @Covers(value = Economy.class, method = "deposit")
    @Covers(value = Economy.class, method = "hasAccount")
    @Test
    void anOfflineStrangerHasNoAccountToPayInto() {
        start();
        assertFalse(economy.hasAccount(NOBODY));
        assertEquals(new EconomyResult.NoSuchAccount(NOBODY), economy.deposit(NOBODY, money("1.00")));
        assertTrue(host.events.isEmpty());
    }

    @Covers(value = Economy.class, method = "hasAccount")
    @Test
    void anOnlinePlayersAccountIsMadeTheFirstTimeItsTouched() {
        start();
        assertTrue(economy.hasAccount(ANN), "online players always have an account");
        assertEquals(money("0.00"), economy.getBalance(ANN, MONEY));
        fund(ANN, "1.00");
        host.online.remove(ANN);
        assertTrue(economy.hasAccount(ANN), "and keep it once they log off");
        assertEquals(money("1.00"), economy.getBalance(ANN));
    }

    @Covers(value = Economy.class, method = "createAccount")
    @Test
    void createAccountMakesAnOfflineOne() {
        start();
        assertTrue(economy.createAccount(NOBODY));
        assertFalse(economy.createAccount(NOBODY));
        assertInstanceOf(EconomyResult.Done.class, economy.deposit(NOBODY, money("1.00")));
    }

    // ---- deposit ----

    @Covers(value = Economy.class, method = "deposit")
    @Test
    void aDepositAddsAndFiresAProduceEventInMinorUnits() {
        start();
        fund(ANN, "1.25");
        EconomyResult result = economy.deposit(ANN, money("2.50"));
        assertEquals(new EconomyResult.Done(MONEY, money("2.50"), money("3.75")), result);
        ResourceProduceEvent event = assertInstanceOf(ResourceProduceEvent.class, host.events.get(0));
        assertEquals(MONEY.resource(), event.getResource());
        assertEquals(ResourceHolder.Account.player(ANN), event.getHolder());
        assertEquals(125, event.getOldAmount());
        assertEquals(375, event.getNewAmount());
        assertEquals(250, event.getAmount());
    }

    @Covers(value = Economy.class, method = "deposit")
    @Test
    void aCancelledDepositChangesNothing() {
        start();
        host.listener = e -> e.setCancelled(true);
        assertEquals(new EconomyResult.Refused(MONEY, money("1.00")), economy.deposit(ANN, money("1")));
        assertEquals(money("0.00"), economy.getBalance(ANN));
    }

    @Covers(value = Economy.class, method = "deposit")
    @Test
    void aZeroDepositSucceedsWithoutAnEvent() {
        start();
        fund(ANN, "1.00");
        assertEquals(new EconomyResult.Done(MONEY, money("0.00"), money("1.00")), economy.deposit(ANN, money("0")));
        assertTrue(host.events.isEmpty());
    }

    @Covers(value = Economy.class, method = "deposit")
    @Test
    void theEventCarriesTheActorOnTop() {
        start();
        Actor console = new Actor.Server("Server");
        try (Actors.Scope scope = Actors.push(console)) {
            economy.deposit(ANN, money("1"));
        }
        assertSame(console, host.events.get(0).getActor());
        economy.deposit(ANN, money("1"));
        assertSame(Actor.Unknown.INSTANCE, host.events.get(1).getActor(), "nobody on the stack is Unknown");
    }

    @Covers(value = Economy.class, method = "deposit")
    @Test
    void aDepositThatWouldOverflowIsRefusedBeforeAnyoneHearsOfIt() {
        start();
        economy.registerCurrency(gemsPlugin, GEMS);
        assertInstanceOf(EconomyResult.Done.class,
                economy.deposit(ANN, GEMS, BigDecimal.valueOf(Long.MAX_VALUE)));
        host.events.clear();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> economy.deposit(ANN, GEMS, BigDecimal.ONE));
        assertTrue(e.getMessage().contains("more than an account can hold"), e.getMessage());
        assertTrue(host.events.isEmpty());
        assertEquals(BigDecimal.valueOf(Long.MAX_VALUE), economy.getBalance(ANN, GEMS));
    }

    // ---- withdraw ----

    @Covers(value = Economy.class, method = "withdraw")
    @Test
    void aWithdrawalTakesAndFiresAConsumeEvent() {
        start();
        fund(ANN, "5.00");
        assertEquals(new EconomyResult.Done(MONEY, money("1.50"), money("3.50")), economy.withdraw(ANN, money("1.5")));
        ResourceConsumeEvent event = assertInstanceOf(ResourceConsumeEvent.class, host.events.get(0));
        assertEquals(500, event.getOldAmount());
        assertEquals(350, event.getNewAmount());
        assertEquals(money("3.50"), economy.getBalance(ANN), "the withdrawal is stored, not only reported");
    }

    @Covers(value = Economy.class, method = "withdraw")
    @Test
    void overdrawingIsRefusedSayingHowMuchIsMissing() {
        start();
        fund(ANN, "1.00");
        EconomyResult result = economy.withdraw(ANN, money("1.01"));
        EconomyResult.NotEnoughMoney refused = assertInstanceOf(EconomyResult.NotEnoughMoney.class, result);
        assertEquals(money("0.01"), refused.missing());
        assertEquals(money("1.00"), economy.getBalance(ANN));
        assertTrue(host.events.isEmpty(), "nothing to cancel: the check comes before the event");
    }

    @Covers(value = Economy.class, method = "withdraw")
    @Test
    void withdrawingEverythingLeavesZero() {
        start();
        fund(ANN, "2.00");
        assertEquals(new EconomyResult.Done(MONEY, money("2.00"), money("0.00")), economy.withdraw(ANN, money("2")));
        assertEquals(money("0.00"), economy.getBalance(ANN));
    }

    @Covers(value = Economy.class, method = "has")
    @Test
    void hasComparesWithTheBalance() {
        start();
        fund(ANN, "2.00");
        assertTrue(economy.has(ANN, money("2")));
        assertFalse(economy.has(ANN, money("2.01")));
        assertFalse(economy.has(ANN, GEMS, BigDecimal.ZERO), "an unregistered currency is never had");
    }

    // ---- transfer ----

    @Covers(value = Economy.class, method = "transfer")
    @Test
    void aTransferMovesMoneyAndFiresOneTransferEvent() {
        start();
        fund(ANN, "10.00");
        fund(BO, "1.00");
        assertEquals(new EconomyResult.Done(MONEY, money("2.50"), money("7.50")),
                economy.transfer(ANN, BO, money("2.5")));
        assertEquals(money("3.50"), economy.getBalance(BO));
        assertEquals(1, host.events.size());
        ResourceTransferEvent event = assertInstanceOf(ResourceTransferEvent.class, host.events.get(0));
        assertEquals(ResourceHolder.Account.player(ANN), event.getSource());
        assertEquals(ResourceHolder.Account.player(BO), event.getDestination());
        assertEquals(250, event.getAmount());
    }

    @Covers(value = Economy.class, method = "transfer")
    @Test
    void aCancelledTransferMovesNothing() {
        start();
        fund(ANN, "10.00");
        host.listener = e -> e.setCancelled(true);
        assertInstanceOf(EconomyResult.Refused.class, economy.transfer(ANN, BO, money("1")));
        assertEquals(money("10.00"), economy.getBalance(ANN));
        assertEquals(money("0.00"), economy.getBalance(BO));
    }

    @Covers(value = Economy.class, method = "transfer")
    @Test
    void aTransferNeedsEnoughMoneyAndBothAccounts() {
        start();
        fund(ANN, "1.00");
        assertInstanceOf(EconomyResult.NotEnoughMoney.class, economy.transfer(ANN, BO, money("2")));
        assertEquals(new EconomyResult.NoSuchAccount(NOBODY), economy.transfer(ANN, NOBODY, money("1")));
        assertEquals(new EconomyResult.NoSuchAccount(NOBODY), economy.transfer(NOBODY, ANN, money("1")));
        assertEquals(money("1.00"), economy.getBalance(ANN));
        assertTrue(host.events.isEmpty());
    }

    @Covers(value = Economy.class, method = "transfer")
    @Test
    void aTransferToYourselfChecksTheBalanceButChangesNothing() {
        start();
        fund(ANN, "1.00");
        assertEquals(new EconomyResult.Done(MONEY, money("1.00"), money("1.00")),
                economy.transfer(ANN, ANN, money("1")));
        assertInstanceOf(EconomyResult.NotEnoughMoney.class, economy.transfer(ANN, ANN, money("2")));
        assertTrue(host.events.isEmpty());
    }

    @Covers(value = Economy.class, method = "transfer")
    @Test
    void aTransferThatWouldOverflowTheRecipientIsRefusedBeforeTheEvent() {
        start();
        economy.registerCurrency(gemsPlugin, GEMS);
        economy.deposit(ANN, GEMS, BigDecimal.ONE);
        economy.deposit(BO, GEMS, BigDecimal.valueOf(Long.MAX_VALUE));
        host.events.clear();
        assertThrows(IllegalArgumentException.class, () -> economy.transfer(ANN, BO, GEMS, BigDecimal.ONE));
        assertTrue(host.events.isEmpty());
        assertEquals(BigDecimal.ONE, economy.getBalance(ANN, GEMS));
    }

    // ---- listeners using the economy ----

    @Covers(value = Economy.class, method = "deposit")
    @Test
    void aListenerCantChangeTheAccountBeingDecided() {
        start();
        List<Throwable> thrown = new ArrayList<>();
        host.listener = e -> {
            try {
                economy.deposit(ANN, money("100"));
            } catch (Throwable t) {
                thrown.add(t);
            }
        };
        assertEquals(new EconomyResult.Done(MONEY, money("1.00"), money("1.00")), economy.deposit(ANN, money("1")));
        IllegalStateException e = assertInstanceOf(IllegalStateException.class, thrown.get(0));
        assertTrue(e.getMessage().contains("getServer().getScheduler().runTask"), e.getMessage());
        assertEquals(money("1.00"), economy.getBalance(ANN), "the outer change wins, nothing is lost or doubled");
    }

    @Covers(value = Economy.class, method = "transfer")
    @Test
    void aListenerCantChangeEitherSideOfATransfer() {
        start();
        fund(ANN, "5.00");
        List<Throwable> thrown = new ArrayList<>();
        host.listener = e -> {
            try {
                economy.withdraw(BO, money("0"));
            } catch (Throwable t) {
                thrown.add(t);
            }
        };
        economy.transfer(ANN, BO, money("1"));
        assertInstanceOf(IllegalStateException.class, thrown.get(0));
    }

    @Covers(value = Economy.class, method = "deposit")
    @Test
    void aListenerMayReadTheAccountAndChangeOthers() {
        start();
        fund(ANN, "1.00");
        List<Object> seen = new ArrayList<>();
        host.listener = e -> {
            if (e instanceof ResourceChangeEvent c && c.getHolder().equals(ResourceHolder.Account.player(ANN))) {
                seen.add(economy.getBalance(ANN));
                seen.add(economy.deposit(BO, money("0.10"))); // a tax to someone else
            }
        };
        economy.deposit(ANN, money("1"));
        assertEquals(money("1.00"), seen.get(0), "the listener sees the balance before the change");
        assertInstanceOf(EconomyResult.Done.class, seen.get(1));
        assertEquals(money("2.00"), economy.getBalance(ANN));
        assertEquals(money("0.10"), economy.getBalance(BO));
    }

    @Covers(value = Economy.class, method = "deposit")
    @Test
    void theRefusalEndsWithTheChangeSoTheAccountIsFreeAfter() {
        start();
        host.listener = e -> {
            throw new IllegalStateException("listener bug escaping a fake host");
        };
        assertThrows(IllegalStateException.class, () -> economy.deposit(ANN, money("1")));
        host.listener = e -> {};
        assertInstanceOf(EconomyResult.Done.class, economy.deposit(ANN, money("1")), "the account isn't stuck");
    }

    @Covers(value = Economy.class, method = "getBalance")
    @Covers(value = Economy.class, method = "deposit")
    @Test
    void everyCallLetsGoOfTheEconomySoAnotherThreadCanUseIt() throws Exception {
        start();
        economy.getBalance(ANN, MONEY);
        economy.deposit(ANN, money("1"));
        java.util.concurrent.ExecutorService other = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            assertEquals(money("1.00"), other.submit(() -> economy.getBalance(ANN, MONEY))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS), "a read must let go of the lock");
            economy.close();
            assertEquals(new EconomyResult.Unavailable("the server is stopping"), other.submit(
                    () -> economy.deposit(ANN, money("1"))).get(10, java.util.concurrent.TimeUnit.SECONDS),
                    "a change and close must let go of the lock");
        } finally {
            other.shutdownNow();
        }
    }

    // ---- providers ----

    @Nested
    class WithAPluginProvider {
        final MapProvider provider = new MapProvider();

        @BeforeEach
        void register() {
            start();
            host.provider = provider;
        }

        @Covers(value = Economy.class, method = "deposit")
        @Test
        void aRegisteredProviderStoresTheAccounts() {
            economy.deposit(ANN, money("3"));
            assertEquals(money("3.00"), provider.accounts.get(ANN).get("money"));
            assertEquals(0, timer.waiting(), "Hadron's own file is untouched");
        }

        @Covers(value = Economy.class, method = "deposit")
        @Test
        void oncePluginAccountsWereUsedTheirAbsenceFailsRatherThanSplittingBalances() {
            economy.deposit(ANN, money("3"));
            host.provider = null; // the plugin disabled, or is mid-reload
            EconomyResult.Unavailable result = assertInstanceOf(EconomyResult.Unavailable.class,
                    economy.deposit(ANN, money("1")));
            assertTrue(result.reason().contains(MapProvider.class.getName() + ") isn't running"), result.reason());
            assertTrue(result.reason().contains("restart the server to use Hadron's own economy.json"),
                    result.reason());
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> economy.getBalance(ANN, MONEY));
            assertTrue(e.getMessage().startsWith("The economy can't read accounts"), e.getMessage());
            host.provider = provider; // back again
            assertEquals(money("3.00"), economy.getBalance(ANN, MONEY));
        }

        @Covers(value = Economy.class, method = "deposit")
        @Test
        void aThrowingProviderFailsTheOperationAndChangesNothing() {
            economy.deposit(ANN, money("3"));
            host.provider = new MapProvider() {
                {
                    accounts.putAll(provider.accounts);
                }

                @Override
                public void setBalances(Currency currency, Map<UUID, BigDecimal> balances) {
                    throw new IllegalStateException("database down");
                }
            };
            EconomyResult.Unavailable result = assertInstanceOf(EconomyResult.Unavailable.class,
                    economy.deposit(ANN, money("1")));
            assertTrue(result.reason().contains("database down"), result.reason());
        }

        @Covers(value = Economy.class, method = "withdraw")
        @Test
        void aNegativeOrNullBalanceFromAProviderIsAFailureNotAValue() {
            provider.createAccount(ANN);
            provider.accounts.get(ANN).put("money", money("-5"));
            EconomyResult.Unavailable result = assertInstanceOf(EconomyResult.Unavailable.class,
                    economy.withdraw(ANN, money("1")));
            assertTrue(result.reason().contains("a provider must return zero or more"), result.reason());
            host.provider = new MapProvider() {
                {
                    createAccount(ANN);
                }

                @Override
                public BigDecimal getBalance(UUID account, Currency currency) {
                    return null;
                }
            };
            assertInstanceOf(EconomyResult.Unavailable.class, economy.deposit(ANN, money("1")));
        }

        @Covers(value = Economy.class, method = "getBalance")
        @Test
        void aStrangerReadsZeroWithoutTheProviderBeingAskedForABalance() {
            // MapProvider, like many a plugin's, can't give a balance for an account it doesn't have.
            assertEquals(money("0.00"), economy.getBalance(NOBODY, MONEY));
        }

        @Covers(value = Economy.class, method = "getBalance")
        @Test
        void aBalanceStoredAtMoreDecimalsRoundsDownNeverUp() {
            provider.createAccount(ANN);
            provider.accounts.get(ANN).put("money", money("1.239"));
            assertEquals(money("1.23"), economy.getBalance(ANN, MONEY));
            assertInstanceOf(EconomyResult.NotEnoughMoney.class, economy.withdraw(ANN, money("1.24")));
        }
    }

    @Covers(value = Economy.class, method = "deposit")
    @Test
    void anUnreadableEconomyFileMakesChangesUnavailableAndIsLeftAlone() throws Exception {
        Files.writeString(dir.resolve("economy.json"), "{ broken");
        start();
        EconomyResult.Unavailable result = assertInstanceOf(EconomyResult.Unavailable.class,
                economy.deposit(ANN, money("1")));
        assertTrue(result.reason().contains("couldn't be read"), result.reason());
        economy.close();
        economy = null;
        assertEquals("{ broken", Files.readString(dir.resolve("economy.json")));
    }
}
