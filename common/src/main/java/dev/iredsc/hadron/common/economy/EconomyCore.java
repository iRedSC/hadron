package dev.iredsc.hadron.common.economy;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.config.YamlConfiguration;
import dev.iredsc.hadron.api.economy.Currency;
import dev.iredsc.hadron.api.economy.Economy;
import dev.iredsc.hadron.api.economy.EconomyProvider;
import dev.iredsc.hadron.api.economy.EconomyResult;
import dev.iredsc.hadron.api.event.resource.ResourceConsumeEvent;
import dev.iredsc.hadron.api.event.resource.ResourceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.resource.ResourceProduceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceTransferEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.common.PluginHost;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hadron's economy: the currency registry, the checks, the resource events, and the calls to whichever
 * {@link EconomyProvider} stores accounts. Every account operation, reads included, runs under one lock, so a
 * balance is never read and then overwritten by another thread in between, and a provider never sees two calls at
 * once. Events fire under that lock on the calling thread.
 */
public final class EconomyCore implements Economy {
    private static final Logger LOG = LoggerFactory.getLogger("Hadron");
    private static final String CONFIG = "economy.yml";
    private static final String DEFAULT_CONFIG = """
            # The currency that plugins use when they don't name one.
            # "money" (dollars, 2 decimal places) is Hadron's own and always exists. To make a plugin's currency the
            # default instead, put its name here, such as "gems", and restart.
            default-currency: money
            """;

    private static volatile EconomyCore current;

    static {
        // Once per JVM; forwards to whichever server is running.
        PluginHost.onPluginDisable(plugin -> {
            EconomyCore running = current;
            if (running != null) running.forget(plugin);
        });
    }

    private final PluginHost host;
    private final MinecraftServer minecraft;
    private final File folder;
    private final JsonEconomyProvider builtIn;
    /** By name, in registration order. Guarded by itself. */
    private final Map<String, Registered> currencies = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    /** The accounts whose change this thread is firing events for, so a listener can't overwrite it. */
    private final ThreadLocal<Set<UUID>> changing = ThreadLocal.withInitial(HashSet::new);
    /**
     * The class name of the last plugin provider seen this run, or null while only the built-in one has been used.
     * A name, not the provider, so a disabled plugin's classes aren't kept.
     */
    private volatile String pluginProvider;
    private volatile String defaultName = Economy.MONEY.name();
    private boolean warnedDefault;
    private boolean closed;

    /** {@code owner} is null for Hadron's own. Holds the plugin only until it disables. */
    private record Registered(Currency currency, JavaPlugin owner) {
        String ownerName() {
            return owner == null ? "Hadron" : owner.getName();
        }
    }

    /** @param folder the server's {@code hadron/} folder, for {@code economy.yml} and {@code economy.json} */
    public EconomyCore(PluginHost host, MinecraftServer minecraft, File folder) {
        this.host = host;
        this.minecraft = minecraft;
        this.folder = folder;
        this.builtIn = new JsonEconomyProvider(new File(folder, "economy.json").toPath());
        currencies.put(Economy.MONEY.name(), new Registered(Economy.MONEY, null));
    }

    /** Before any plugin loads: reads the config and balances, and makes {@link Economy#get()} work. */
    public void start() {
        readConfig();
        builtIn.load();
        current = this;
        Economy.Lifecycle.install(this);
    }

    /** After every plugin has disabled: saves and stops. Safe to call twice. */
    public void close() {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
        } finally {
            lock.unlock();
        }
        builtIn.close();
        if (current == this) current = null;
        if (Economy.Lifecycle.running() == this) Economy.Lifecycle.install(null);
    }

    private void readConfig() {
        File file = new File(folder, CONFIG);
        try {
            if (!file.exists()) {
                Files.createDirectories(folder.toPath());
                Files.writeString(file.toPath(), DEFAULT_CONFIG, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOG.error("Economy: couldn't write " + file + "; the default currency is money", e);
            return;
        }
        String name = YamlConfiguration.loadConfiguration(file).getString("default-currency", Economy.MONEY.name());
        defaultName = name == null ? Economy.MONEY.name() : name.trim();
        if (!defaultName.equals(Economy.MONEY.name())) LOG.info("Economy: the default currency is {}", defaultName);
    }

    // ---- Currencies ----

    @Override
    public Currency registerCurrency(JavaPlugin plugin, Currency currency) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(currency, "currency");
        synchronized (currencies) {
            // Checked under the lock forget() takes, which runs only after the plugin stops being alive.
            if (!host.isAlive(plugin)) {
                throw new IllegalStateException(plugin.getName() + " can't register currencies while unloading; "
                        + "register them in onLoad");
            }
            Registered existing = currencies.get(currency.name());
            if (existing != null) {
                if (existing.owner() == plugin && existing.currency().equals(currency)) return existing.currency();
                throw new IllegalStateException("The currency " + currency.name() + " is already registered by "
                        + existing.ownerName() + (existing.owner() == plugin ? " as " + existing.currency() : "")
                        + "; pick another name, or use Economy.get().getCurrency(\"" + currency.name()
                        + "\") to use that one");
            }
            currencies.put(currency.name(), new Registered(currency, plugin));
        }
        return currency;
    }

    private void forget(JavaPlugin plugin) {
        synchronized (currencies) {
            for (Iterator<Registered> it = currencies.values().iterator(); it.hasNext(); ) {
                if (it.next().owner() == plugin) it.remove();
            }
        }
    }

    @Override
    public Optional<Currency> getCurrency(String name) {
        synchronized (currencies) {
            Registered r = currencies.get(name);
            return r == null ? Optional.empty() : Optional.of(r.currency());
        }
    }

    @Override
    public List<Currency> getCurrencies() {
        synchronized (currencies) {
            List<Currency> out = new ArrayList<>();
            Registered fallback = currencies.get(defaultName);
            if (fallback != null) out.add(fallback.currency());
            for (Registered r : currencies.values()) if (r != fallback) out.add(r.currency());
            return List.copyOf(out);
        }
    }

    @Override
    public Currency getDefaultCurrency() {
        Currency found = defaultOrNull();
        if (found != null) return found;
        throw new IllegalStateException(missingDefault());
    }

    private Currency defaultOrNull() {
        synchronized (currencies) {
            Registered r = currencies.get(defaultName);
            return r == null ? null : r.currency();
        }
    }

    private String missingDefault() {
        List<String> names;
        synchronized (currencies) {
            names = new ArrayList<>(currencies.keySet());
        }
        return new File(folder, CONFIG) + " sets default-currency: " + defaultName + ", but no running plugin "
                + "registers " + defaultName + " (registered: " + String.join(", ", names) + "). Enable the plugin "
                + "that adds it, or change default-currency and restart";
    }

    /** The registered definition for that name, or null; a plugin may hold a Currency from before a reload. */
    private Currency registered(Currency currency) {
        Objects.requireNonNull(currency, "currency");
        synchronized (currencies) {
            Registered r = currencies.get(currency.name());
            return r == null ? null : r.currency();
        }
    }

    // ---- Reads ----

    @Override
    public boolean hasAccount(UUID account) {
        Objects.requireNonNull(account, "account");
        return read(() -> {
            EconomyProvider provider = provider();
            return call(provider, () -> provider.hasAccount(account)) || isOnline(account);
        });
    }

    @Override
    public boolean createAccount(UUID account) {
        Objects.requireNonNull(account, "account");
        return read(() -> {
            EconomyProvider provider = provider();
            return call(provider, () -> provider.createAccount(account));
        });
    }

    @Override
    public BigDecimal getBalance(UUID account) {
        Currency currency = defaultOrNull();
        return currency == null ? BigDecimal.ZERO : getBalance(account, currency);
    }

    @Override
    public BigDecimal getBalance(UUID account, Currency currency) {
        Objects.requireNonNull(account, "account");
        Currency c = registered(currency);
        if (c == null) return BigDecimal.ZERO.setScale(currency.decimals());
        return read(() -> {
            EconomyProvider provider = provider();
            if (!call(provider, () -> provider.hasAccount(account))) return BigDecimal.ZERO.setScale(c.decimals());
            return balance(provider, account, c);
        });
    }

    @Override
    public boolean has(UUID account, BigDecimal amount) {
        Currency currency = defaultOrNull();
        return currency != null && has(account, currency, amount);
    }

    @Override
    public boolean has(UUID account, Currency currency, BigDecimal amount) {
        Currency c = registered(currency);
        BigDecimal needed = checkAmount(c == null ? currency : c, amount);
        return c != null && getBalance(account, c).compareTo(needed) >= 0;
    }

    /** A read under the lock; a provider failure becomes an exception that says why. */
    private <T> T read(Supplier<T> work) {
        lock.lock();
        try {
            if (closed) throw new IllegalStateException("The server is stopping, so the economy is closed");
            return work.get();
        } catch (ProviderFailed e) {
            throw new IllegalStateException("The economy can't read accounts: " + e.getMessage(), e.getCause());
        } finally {
            lock.unlock();
        }
    }

    // ---- Changes ----

    @Override
    public EconomyResult deposit(UUID account, BigDecimal amount) {
        Currency currency = defaultOrNull();
        return currency == null ? noDefault(amount) : deposit(account, currency, amount);
    }

    @Override
    public EconomyResult deposit(UUID account, Currency currency, BigDecimal amount) {
        Objects.requireNonNull(account, "account");
        Currency c = registered(currency);
        BigDecimal amt = checkAmount(c == null ? currency : c, amount);
        if (c == null) return new EconomyResult.NoSuchCurrency(currency.name());
        return change(Set.of(account), provider -> {
            if (!ensureAccount(provider, account)) return new EconomyResult.NoSuchAccount(account);
            BigDecimal old = balance(provider, account, c);
            if (amt.signum() == 0) return new EconomyResult.Done(c, amt, old);
            BigDecimal now = old.add(amt);
            ResourceEvent event = new ResourceProduceEvent(actor(), c.resource(), holder(account),
                    minor(c, old), minor(c, now));
            if (refused(event)) return new EconomyResult.Refused(c, amt);
            store(provider, () -> provider.setBalances(c, Map.of(account, now)));
            return new EconomyResult.Done(c, amt, now);
        });
    }

    @Override
    public EconomyResult withdraw(UUID account, BigDecimal amount) {
        Currency currency = defaultOrNull();
        return currency == null ? noDefault(amount) : withdraw(account, currency, amount);
    }

    @Override
    public EconomyResult withdraw(UUID account, Currency currency, BigDecimal amount) {
        Objects.requireNonNull(account, "account");
        Currency c = registered(currency);
        BigDecimal amt = checkAmount(c == null ? currency : c, amount);
        if (c == null) return new EconomyResult.NoSuchCurrency(currency.name());
        return change(Set.of(account), provider -> {
            if (!ensureAccount(provider, account)) return new EconomyResult.NoSuchAccount(account);
            BigDecimal old = balance(provider, account, c);
            if (old.compareTo(amt) < 0) return new EconomyResult.NotEnoughMoney(c, old, amt);
            if (amt.signum() == 0) return new EconomyResult.Done(c, amt, old);
            BigDecimal now = old.subtract(amt);
            ResourceEvent event = new ResourceConsumeEvent(actor(), c.resource(), holder(account),
                    minor(c, old), minor(c, now));
            if (refused(event)) return new EconomyResult.Refused(c, amt);
            store(provider, () -> provider.setBalances(c, Map.of(account, now)));
            return new EconomyResult.Done(c, amt, now);
        });
    }

    @Override
    public EconomyResult transfer(UUID from, UUID to, BigDecimal amount) {
        Currency currency = defaultOrNull();
        return currency == null ? noDefault(amount) : transfer(from, to, currency, amount);
    }

    @Override
    public EconomyResult transfer(UUID from, UUID to, Currency currency, BigDecimal amount) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Currency c = registered(currency);
        BigDecimal amt = checkAmount(c == null ? currency : c, amount);
        if (c == null) return new EconomyResult.NoSuchCurrency(currency.name());
        return change(from.equals(to) ? Set.of(from) : Set.of(from, to), provider -> {
            if (!ensureAccount(provider, from)) return new EconomyResult.NoSuchAccount(from);
            if (!ensureAccount(provider, to)) return new EconomyResult.NoSuchAccount(to);
            BigDecimal oldFrom = balance(provider, from, c);
            if (oldFrom.compareTo(amt) < 0) return new EconomyResult.NotEnoughMoney(c, oldFrom, amt);
            if (amt.signum() == 0 || from.equals(to)) return new EconomyResult.Done(c, amt, oldFrom);
            BigDecimal newFrom = oldFrom.subtract(amt);
            BigDecimal newTo = balance(provider, to, c).add(amt);
            minor(c, newTo); // refuse an overflowing balance before anyone hears of the transfer
            ResourceEvent event = new ResourceTransferEvent(actor(), c.resource(), holder(from), holder(to),
                    minor(c, amt));
            if (refused(event)) return new EconomyResult.Refused(c, amt);
            Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
            balances.put(from, newFrom);
            balances.put(to, newTo);
            store(provider, () -> provider.setBalances(c, balances));
            return new EconomyResult.Done(c, amt, newFrom);
        });
    }

    private interface Change {
        EconomyResult run(EconomyProvider provider);
    }

    /**
     * Runs a change under the lock. A listener hearing it may use the economy, but not change these same accounts:
     * the outer change would then write over the inner one when the listener returns.
     */
    private EconomyResult change(Set<UUID> accounts, Change work) {
        Set<UUID> mine = changing.get();
        for (UUID account : accounts) {
            if (mine.contains(account)) {
                throw new IllegalStateException("A listener tried to change account " + account + " while a change "
                        + "to it is being decided; schedule it with getServer().getScheduler().runTask instead");
            }
        }
        lock.lock();
        mine.addAll(accounts);
        try {
            if (closed) return new EconomyResult.Unavailable("the server is stopping");
            return work.run(provider());
        } catch (ProviderFailed e) {
            return new EconomyResult.Unavailable(e.getMessage());
        } finally {
            mine.removeAll(accounts);
            lock.unlock();
        }
    }

    private EconomyResult noDefault(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        logMissingDefault();
        return new EconomyResult.NoSuchCurrency(defaultName);
    }

    private void logMissingDefault() {
        if (warnedDefault) return;
        warnedDefault = true;
        LOG.error("Economy: " + missingDefault());
    }

    // ---- Helpers ----

    /**
     * A plugin's provider when one is registered, otherwise Hadron's JSON file. Looked up on every call. Once a plugin
     * has provided accounts this run, its absence (disabled, or mid-reload) fails the call instead of writing to the
     * JSON file, so balances never split between two stores.
     */
    private EconomyProvider provider() {
        EconomyProvider registered = host.server().getServicesManager().load(EconomyProvider.class);
        if (registered != null) {
            pluginProvider = registered.getClass().getName();
            return registered;
        }
        String gone = pluginProvider;
        if (gone != null) {
            throw new ProviderFailed("the plugin providing accounts (" + gone + ") isn't running; enable it again, "
                    + "or restart the server to use Hadron's own economy.json", null);
        }
        return builtIn;
    }

    /** Whether the account exists, making a player's the first time it is touched while they're online. */
    private boolean ensureAccount(EconomyProvider provider, UUID account) {
        if (call(provider, () -> provider.hasAccount(account))) return true;
        if (!isOnline(account)) return false;
        call(provider, () -> provider.createAccount(account));
        return true;
    }

    private boolean isOnline(UUID account) {
        return minecraft.getPlayerList().getPlayer(account) != null;
    }

    private BigDecimal balance(EconomyProvider provider, UUID account, Currency currency) {
        BigDecimal balance = call(provider, () -> provider.getBalance(account, currency));
        if (balance == null || balance.signum() < 0) {
            throw new ProviderFailed(provider.getClass().getName() + " returned " + balance + " as " + account + "'s "
                    + currency.name() + " balance; a provider must return zero or more", null);
        }
        // A balance stored at more decimals than the currency now has (its plugin changed it) rounds down: the
        // economy never pays out money that isn't there.
        return balance.setScale(currency.decimals(), java.math.RoundingMode.DOWN);
    }

    private static <T> T call(EconomyProvider provider, Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            if (e instanceof ProviderFailed) throw e;
            LOG.error("Economy: " + provider.getClass().getName() + " failed", e);
            throw new ProviderFailed(provider.getClass().getName() + " failed: " + e, e);
        }
    }

    private static void store(EconomyProvider provider, Runnable work) {
        call(provider, () -> {
            work.run();
            return null;
        });
    }

    private boolean refused(ResourceEvent event) {
        host.post(event);
        return event.isCancelled();
    }

    private static Actor actor() {
        return Actors.currentOr(Actor.Unknown.INSTANCE);
    }

    private static ResourceHolder holder(UUID account) {
        return ResourceHolder.Account.player(account);
    }

    /** The amount at the currency's scale, or an exception that says the fix. */
    private static BigDecimal checkAmount(Currency currency, BigDecimal amount) {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Economy amounts are never negative, got " + amount.toPlainString()
                    + "; use withdraw to take money and deposit to give it");
        }
        if (amount.stripTrailingZeros().scale() > currency.decimals()) {
            throw new IllegalArgumentException(currency.name() + " has " + currency.decimals() + " decimal place(s), "
                    + "but the amount is " + amount.toPlainString() + "; round it first with currency.round(amount)");
        }
        return amount.setScale(currency.decimals());
    }

    /** In the smallest unit, as resource events count. */
    private static long minor(Currency currency, BigDecimal amount) {
        try {
            return amount.movePointRight(currency.decimals()).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(currency.format(amount) + " is more than an account can hold (at most "
                    + currency.format(currency.fromMinorUnits(Long.MAX_VALUE)) + ")");
        }
    }

    /** A provider call failed; the operation ends with {@link EconomyResult.Unavailable}. */
    private static final class ProviderFailed extends RuntimeException {
        ProviderFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
