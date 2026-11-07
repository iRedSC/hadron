package dev.iredsc.hadron.common.economy;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.iredsc.hadron.api.economy.Currency;
import dev.iredsc.hadron.api.economy.EconomyProvider;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hadron's own account store, used while no plugin provides {@link EconomyProvider}: every balance in one JSON file,
 * loaded at start, saved a few seconds after a change (so a burst of payments is one write) and at stop. Each save
 * writes a temporary file, flushes it to disk and moves it over the old one in one step, so a crash mid-save leaves
 * the previous file whole. A file that can't be read is never saved over: the economy stays unavailable until it is
 * fixed, rather than starting everyone at zero.
 */
final class JsonEconomyProvider implements EconomyProvider {
    private static final Logger LOG = LoggerFactory.getLogger("Hadron");
    private static final long SAVE_DELAY_SECONDS = 5;
    private static final int FORMAT = 1;

    private final Path file;
    private final Path temp;
    /** Account to currency name to balance. */
    private final Map<UUID, Map<String, BigDecimal>> accounts = new HashMap<>();
    private final ScheduledThreadPoolExecutor saver;
    private ScheduledFuture<?> pending;
    private boolean dirty;
    private boolean closed;
    /** Why the file couldn't be loaded, or null when it loaded (or didn't exist yet). */
    private String broken;

    JsonEconomyProvider(Path file) {
        this.file = file;
        this.temp = file.resolveSibling(file.getFileName() + ".tmp");
        this.saver = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "Hadron economy saver");
            t.setDaemon(true);
            return t;
        });
        saver.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    synchronized void load() {
        accounts.clear();
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject all = root.getAsJsonObject("accounts");
            if (all == null) throw new IllegalArgumentException("it has no \"accounts\" object");
            for (Map.Entry<String, JsonElement> account : all.entrySet()) {
                Map<String, BigDecimal> balances = new HashMap<>();
                for (Map.Entry<String, JsonElement> balance : account.getValue().getAsJsonObject().entrySet()) {
                    BigDecimal amount = new BigDecimal(balance.getValue().getAsString());
                    if (amount.signum() < 0) {
                        throw new IllegalArgumentException(account.getKey() + " has a negative " + balance.getKey()
                                + " balance, " + amount);
                    }
                    balances.put(balance.getKey(), amount);
                }
                accounts.put(UUID.fromString(account.getKey()), balances);
            }
            LOG.info("Economy: loaded {} account(s) from {}", accounts.size(), file);
        } catch (IOException | RuntimeException e) {
            accounts.clear();
            broken = file + " couldn't be read (" + e + "), so the economy is off and the file is left as it is. "
                    + "Fix the JSON, or move the file away to start every balance at zero, then restart the server";
            LOG.error("Economy: " + broken);
        }
    }

    private void check() {
        if (broken != null) throw new IllegalStateException(broken);
        if (closed) throw new IllegalStateException("the server is stopping, and " + file + " is already saved");
    }

    @Override
    public synchronized boolean hasAccount(UUID account) {
        check();
        return accounts.containsKey(account);
    }

    @Override
    public synchronized boolean createAccount(UUID account) {
        check();
        if (accounts.containsKey(account)) return false;
        accounts.put(account, new HashMap<>());
        changed();
        return true;
    }

    @Override
    public synchronized BigDecimal getBalance(UUID account, Currency currency) {
        check();
        Map<String, BigDecimal> balances = accounts.get(account);
        BigDecimal balance = balances == null ? null : balances.get(currency.name());
        return balance == null ? BigDecimal.ZERO.setScale(currency.decimals()) : balance;
    }

    @Override
    public synchronized void setBalances(Currency currency, Map<UUID, BigDecimal> balances) {
        check();
        for (Map.Entry<UUID, BigDecimal> e : balances.entrySet()) {
            accounts.computeIfAbsent(e.getKey(), k -> new HashMap<>()).put(currency.name(), e.getValue());
        }
        changed();
    }

    private void changed() {
        dirty = true;
        if (pending == null && !saver.isShutdown()) {
            pending = saver.schedule(this::save, SAVE_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Saves what's unsaved and stops; later calls fail. Safe to call twice. */
    void close() {
        synchronized (this) {
            if (closed) return;
            saver.shutdown();
        }
        try {
            // A save already running finishes; one only waiting is dropped, and done just below instead.
            saver.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        save();
        synchronized (this) {
            closed = true;
        }
    }

    /**
     * One writer at a time, whatever thread: a timed-out or interrupted wait in {@link #close} must never let a second
     * save write the temp file while the first still is. The snapshot is taken inside, so the last write is the newest.
     */
    private final Object writing = new Object();

    private void save() {
        synchronized (writing) {
            saveLocked();
        }
    }

    private void saveLocked() {
        String json;
        synchronized (this) {
            pending = null;
            if (!dirty || broken != null) return;
            json = serialize();
            dirty = false;
        }
        try {
            write(json);
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                dirty = true;
                if (saver.isShutdown()) {
                    LOG.error("Economy: saving " + file + " at stop failed, so changes since the last save are lost", e);
                } else {
                    LOG.error("Economy: saving " + file + " failed; trying again in " + SAVE_DELAY_SECONDS + "s", e);
                    changed();
                }
            }
        }
    }

    private String serialize() {
        JsonObject all = new JsonObject();
        for (Map.Entry<UUID, Map<String, BigDecimal>> account : new TreeMap<>(accounts).entrySet()) {
            JsonObject balances = new JsonObject();
            for (Map.Entry<String, BigDecimal> b : new TreeMap<>(account.getValue()).entrySet()) {
                balances.addProperty(b.getKey(), b.getValue().toPlainString());
            }
            all.add(account.getKey().toString(), balances);
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.add("accounts", all);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    private void write(String json) throws IOException {
        Files.createDirectories(file.getParent());
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer bytes = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
