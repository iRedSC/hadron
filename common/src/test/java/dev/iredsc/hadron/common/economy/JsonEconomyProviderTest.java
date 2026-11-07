package dev.iredsc.hadron.common.economy;

import dev.iredsc.hadron.test.Covers;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.economy.Currency;
import dev.iredsc.hadron.api.economy.Economy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Hadron's own account file: when it saves, how it saves, and that it never writes over what it couldn't read. */
class JsonEconomyProviderTest {
    private static final Currency MONEY = Economy.MONEY;
    private static final Currency GEMS = new Currency("gems", "gem", "gems", 0);
    private static final UUID ANN = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;

    private Path file;
    private ManualSaveTimer timer;
    private JsonEconomyProvider store;

    @BeforeEach
    void setUp() {
        file = dir.resolve("hadron/economy.json");
        timer = new ManualSaveTimer();
        store = new JsonEconomyProvider(file, timer);
    }

    private JsonEconomyProvider reopen() {
        JsonEconomyProvider again = new JsonEconomyProvider(file, new ManualSaveTimer());
        again.load();
        return again;
    }

    // ---- when it saves ----

    @Test
    void aMissingFileStartsEmptyAndIsntWrittenUntilSomethingChanges() {
        store.load();
        assertFalse(store.hasAccount(ANN));
        assertEquals(0, timer.waiting());
        store.close();
        assertFalse(Files.exists(file), "nothing changed, so nothing to write");
    }

    @Test
    void aChangeSavesFiveSecondsLaterNotAtOnce() {
        store.load();
        store.setBalances(MONEY, Map.of(ANN, new BigDecimal("1.00")));
        assertFalse(Files.exists(file));
        assertEquals(List.of(JsonEconomyProvider.SAVE_DELAY_SECONDS), timer.delays);
        timer.elapse();
        assertTrue(Files.exists(file));
    }

    @Test
    void aBurstOfChangesIsOneSave() {
        store.load();
        for (int i = 0; i < 50; i++) store.setBalances(MONEY, Map.of(ANN, new BigDecimal(i)));
        store.createAccount(BO);
        assertEquals(1, timer.waiting());
        timer.elapse();
        assertEquals(new BigDecimal(49), reopen().getBalance(ANN, MONEY));
        assertTrue(reopen().hasAccount(BO));
    }

    @Test
    void aChangeAfterASaveSchedulesAnother() {
        store.load();
        store.setBalances(MONEY, Map.of(ANN, BigDecimal.ONE));
        timer.elapse();
        assertEquals(0, timer.waiting());
        store.setBalances(MONEY, Map.of(ANN, BigDecimal.TEN));
        assertEquals(1, timer.waiting());
        timer.elapse();
        assertEquals(BigDecimal.TEN, reopen().getBalance(ANN, MONEY));
    }

    @Test
    void closingSavesWhatTheTimerHadntYet() {
        store.load();
        store.setBalances(GEMS, Map.of(ANN, new BigDecimal("7")));
        store.close();
        assertTrue(timer.stopped());
        assertEquals(new BigDecimal("7"), reopen().getBalance(ANN, GEMS));
    }

    @Test
    void afterCloseEveryCallFailsAndCloseAgainIsHarmless() {
        store.load();
        store.close();
        store.close();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> store.hasAccount(ANN));
        assertTrue(e.getMessage().contains("already saved"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> store.setBalances(MONEY, Map.of(ANN, BigDecimal.ONE)));
    }

    // ---- what it saves ----

    @Test
    void balancesRoundTripExactlyIncludingScale() {
        store.load();
        store.setBalances(MONEY, Map.of(ANN, new BigDecimal("12345678901234567.89"), BO, new BigDecimal("0.10")));
        store.setBalances(GEMS, Map.of(ANN, new BigDecimal("3")));
        store.close();
        JsonEconomyProvider again = reopen();
        assertEquals(new BigDecimal("12345678901234567.89"), again.getBalance(ANN, MONEY));
        assertEquals(new BigDecimal("0.10"), again.getBalance(BO, MONEY));
        assertEquals(new BigDecimal("3"), again.getBalance(ANN, GEMS));
    }

    @Test
    void theFileIsSortedPlainTextWithItsFormat() throws Exception {
        store.load();
        store.setBalances(MONEY, Map.of(BO, new BigDecimal("1E+3"), ANN, new BigDecimal("2.00")));
        store.close();
        String json = Files.readString(file);
        assertTrue(json.contains("\"format\": 1"), json);
        assertTrue(json.indexOf(ANN.toString()) < json.indexOf(BO.toString()), "accounts sorted, so diffs stay small");
        assertTrue(json.contains("\"1000\""), "plain numbers, never exponents: " + json);
        assertTrue(json.contains("\"2.00\""), json);
    }

    @Test
    void anAccountWithNoBalanceReadsZeroAtTheCurrencysScale() {
        store.load();
        assertEquals(new BigDecimal("0.00"), store.getBalance(ANN, MONEY));
        store.createAccount(ANN);
        assertEquals(new BigDecimal("0"), store.getBalance(ANN, GEMS));
    }

    @Test
    void creatingAnExistingAccountLeavesItAsItWas() {
        store.load();
        assertTrue(store.createAccount(ANN));
        store.setBalances(MONEY, Map.of(ANN, BigDecimal.TEN));
        assertFalse(store.createAccount(ANN));
        assertEquals(BigDecimal.TEN, store.getBalance(ANN, MONEY));
    }

    @Test
    void anEmptyAccountIsSavedToo() {
        store.load();
        store.createAccount(ANN);
        store.close();
        assertTrue(reopen().hasAccount(ANN));
    }

    // ---- how it saves ----

    @Test
    void aSaveLeavesNoTemporaryFileBehind() throws Exception {
        store.load();
        store.setBalances(MONEY, Map.of(ANN, BigDecimal.ONE));
        timer.elapse();
        try (var files = Files.list(file.getParent())) {
            assertEquals(List.of(file.getFileName().toString()),
                    files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aTemporaryFileLeftByACrashIsWrittenOverNotRead() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file.resolveSibling("economy.json.tmp"), "half a file {{{");
        store.load();
        assertFalse(store.hasAccount(ANN));
        store.setBalances(MONEY, Map.of(ANN, BigDecimal.ONE));
        timer.elapse();
        assertEquals(BigDecimal.ONE, reopen().getBalance(ANN, MONEY));
        assertFalse(Files.exists(file.resolveSibling("economy.json.tmp")));
    }

    @Test
    void aFailedSaveIsRetriedAndKeepsTheChange() throws Exception {
        Files.createDirectories(file.getParent());
        Path blocker = file.resolveSibling("economy.json.tmp");
        Files.createDirectory(blocker); // a directory where the temp file goes: writing it fails
        store.load();
        store.setBalances(MONEY, Map.of(ANN, BigDecimal.ONE));
        timer.elapse();
        assertFalse(Files.exists(file));
        assertEquals(1, timer.waiting(), "a failed save tries again");
        Files.delete(blocker);
        timer.elapse();
        assertEquals(BigDecimal.ONE, reopen().getBalance(ANN, MONEY));
    }

    // ---- refusing to overwrite ----

    @ParameterizedTest
    @ValueSource(strings = {
        "not json at all {",
        "",
        "[]",
        "{\"format\": 1}",
        "{\"accounts\": {\"not-a-uuid\": {}}}",
        "{\"accounts\": {\"00000000-0000-0000-0000-00000000000a\": {\"money\": \"-1.00\"}}}",
        "{\"accounts\": {\"00000000-0000-0000-0000-00000000000a\": {\"money\": \"lots\"}}}",
        "{\"accounts\": {\"00000000-0000-0000-0000-00000000000a\": 5}}",
    })
    void anUnreadableFileTurnsTheEconomyOffAndIsNeverOverwritten(String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        byte[] before = Files.readAllBytes(file);
        store.load();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> store.hasAccount(ANN));
        assertTrue(e.getMessage().contains("couldn't be read"), e.getMessage());
        assertTrue(e.getMessage().contains("Fix the JSON, or move the file away"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> store.setBalances(MONEY, Map.of(ANN, BigDecimal.ONE)));
        assertThrows(IllegalStateException.class, () -> store.createAccount(ANN));
        assertThrows(IllegalStateException.class, () -> store.getBalance(ANN, MONEY));
        timer.elapse();
        store.close();
        assertArrayEquals(before, Files.readAllBytes(file), "the unreadable file must be left exactly as it was");
    }

    @Test
    void aFixedFileLoadsOnTheNextLoad() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "broken");
        store.load();
        assertThrows(IllegalStateException.class, () -> store.hasAccount(ANN));
        Files.writeString(file, "{\"accounts\": {\"" + ANN + "\": {\"money\": \"5.00\"}}}",
                StandardCharsets.UTF_8);
        store.load();
        assertEquals(new BigDecimal("5.00"), store.getBalance(ANN, MONEY));
    }

    @Test
    void anOlderFileWithoutAFormatFieldStillLoads() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"accounts\": {\"" + ANN + "\": {\"gems\": \"4\"}}}");
        store.load();
        assertEquals(new BigDecimal("4"), store.getBalance(ANN, GEMS));
    }

    @Test
    void theDefaultTimerIsTheWallClockOnADaemonThread() throws Exception {
        JsonEconomyProvider.SaveTimer real = JsonEconomyProvider.SaveTimer.background();
        java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
        boolean[] daemon = new boolean[1];
        real.schedule(() -> {
            daemon[0] = Thread.currentThread().isDaemon();
            ran.countDown();
        }, 0);
        assertTrue(ran.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(daemon[0], "a pending save must never keep the JVM alive");
        real.schedule(() -> {
            throw new AssertionError("a save only waiting is dropped at stop");
        }, 3600);
        real.stop();
        assertTrue(real.stopped());
    }
}
