package dev.iredsc.hadron.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * gradle/deploy/HadronDeploy.java, run as the {@code deploy} task runs it, against a stand-in RCON server that answers
 * {@code hadron status} with {@link PluginStatus} lines. Checks the whole path: a jar counts as deployed only when the
 * server names the exact file version written, and each way it can go wrong ends with a reason that says the fix.
 */
class DeployClientTest {
    private static final String STALE_SHA = "0".repeat(64);

    @TempDir
    Path dir;

    /** What the stand-in server says to {@code hadron status}, given the jar as the deploy wrote it. */
    private interface Answer {
        List<PluginStatus> to(int asked, File deployed, String sha);
    }

    private static PluginStatus line(PluginStatus.State state, File jar, String sha, String why) {
        return new PluginStatus("Hello", state, jar.getName(), jar.lastModified(), sha, why);
    }

    @Test
    void reportsEnabledOnceTheServerNamesThisExactFile() throws Exception {
        Run run = deploy(5, (asked, jar, sha) -> List.of(line(PluginStatus.State.ENABLED, jar, sha, null)));
        assertEquals(0, run.exit, run.stderr);
        assertTrue(run.stdout.startsWith("Hello enabled on the dev server"), run.stdout);
        assertTrue(Files.isRegularFile(dir.resolve("server/plugins/Hello.jar")), "the jar is installed under its stable name");
        assertFalse(Files.exists(dir.resolve("server/plugins/.Hello.jar.part")), "no half-written copy is left beside it");
    }

    @Test
    void anOlderCopyStillLoadedNeverCountsAsSuccess() throws Exception {
        AtomicInteger sawStale = new AtomicInteger();
        Run run = deploy(10, (asked, jar, sha) -> {
            if (asked <= 3) {
                sawStale.incrementAndGet();
                return List.of(new PluginStatus("Hello", PluginStatus.State.ENABLED, jar.getName(),
                        jar.lastModified() - 5000, STALE_SHA, null));
            }
            return List.of(line(PluginStatus.State.ENABLED, jar, sha, null));
        });
        assertEquals(0, run.exit, run.stderr);
        assertEquals(3, sawStale.get(), "the deploy kept asking while the old version was the one reported");
    }

    @Test
    void theSameBytesWithAnOldTimestampAreNotThisDeploy() throws Exception {
        Run run = deploy(1, (asked, jar, sha) -> List.of(new PluginStatus("Hello", PluginStatus.State.ENABLED,
                jar.getName(), jar.lastModified() - 1000, sha, null)));
        assertEquals(1, run.exit);
        assertTrue(run.stderr.contains("didn't load Hello.jar within 1 s"), run.stderr);
    }

    @Test
    void aPluginThatFailedToLoadFailsTheDeployWithTheHostsReason() throws Exception {
        String why = "onEnable threw java.lang.IllegalStateException: boom at dev.Hello.onEnable(Hello.java:9). Fix "
                + "that there; the stack trace is in the server log";
        Run run = deploy(5, (asked, jar, sha) -> List.of(line(PluginStatus.State.FAILED, jar, sha, why)));
        assertEquals(1, run.exit);
        assertEquals("Hello failed to load: " + why, run.stderr.trim());
        assertEquals("", run.stdout.trim());
    }

    @Test
    void aRefusedJarFailsTheDeployWithTheHostsReason() throws Exception {
        Run run = deploy(5, (asked, jar, sha) -> List.of(
                new PluginStatus("Other", PluginStatus.State.ENABLED, "Other.jar", 1, STALE_SHA, null),
                new PluginStatus(null, PluginStatus.State.REFUSED, jar.getName(), jar.lastModified(), sha,
                        "Hello.jar changed but isn't usable, so the running version stays: no plugin.yml")));
        assertEquals(1, run.exit);
        assertTrue(run.stderr.startsWith("Hello.jar was refused: Hello.jar changed but isn't usable"), run.stderr);
    }

    @Test
    void aPluginSwitchedOffIsNotRunningThisBuild() throws Exception {
        Run run = deploy(5, (asked, jar, sha) -> List.of(line(PluginStatus.State.OFF, jar, sha, null)));
        assertEquals(1, run.exit);
        assertTrue(run.stderr.contains("hadron enable Hello"), run.stderr);
    }

    @Test
    void aServerThatNeverPicksTheJarUpFailsAfterTheTimeout() throws Exception {
        Run run = deploy(1, (asked, jar, sha) -> List.of());
        assertEquals(1, run.exit);
        assertTrue(run.stderr.contains("didn't load Hello.jar within 1 s (it reported nothing about Hello.jar)"),
                run.stderr);
        assertTrue(run.stderr.contains("logs/latest.log"), "says where to look: " + run.stderr);
    }

    @Test
    void noServerOnTheRconPortSaysHowToStartOne() throws Exception {
        int closed;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = probe.getLocalPort();
        }
        Path jar = jar("Hello", true);
        Run run = launch(jar, properties(closed, "hadron"), 1);
        assertEquals(1, run.exit);
        assertTrue(run.stderr.startsWith("No dev server answered on rcon port " + closed), run.stderr);
        assertTrue(run.stderr.contains("./gradlew :neoforge:runServer"), run.stderr);
        assertFalse(Files.exists(dir.resolve("server/plugins/Hello.jar")), "nothing is installed that nobody could verify");
    }

    @Test
    void aWrongRconPasswordSaysWhereItComesFrom() throws Exception {
        try (Fake server = new Fake("right", (asked, jar, sha) -> List.of())) {
            Run run = launch(jar("Hello", true), properties(server.port, "wrong"), 1);
            assertEquals(1, run.exit);
            assertTrue(run.stderr.contains("refused the RCON password") && run.stderr.contains("rcon.password"),
                    run.stderr);
        }
    }

    @Test
    void aServerThatHangsUpMidDeployFailsInsteadOfWaiting() throws Exception {
        Run run = deploy(30, (asked, jar, sha) -> null);
        assertEquals(1, run.exit);
        assertTrue(run.stderr.contains("stopped answering (it closed the connection)"), run.stderr);
    }

    @Test
    void aStatusReplySplitAcrossPacketsIsReadWhole() throws Exception {
        Run run = deploy(5, (asked, jar, sha) -> List.of(line(PluginStatus.State.ENABLED, jar, sha, null)), 7);
        assertEquals(0, run.exit, run.stderr);
    }

    @Test
    void aJarWithoutPluginYmlIsRefusedBeforeAnythingIsCopied() throws Exception {
        try (Fake server = new Fake("hadron", (asked, jar, sha) -> List.of())) {
            Run run = launch(jar("Hello", false), properties(server.port, "hadron"), 1);
            assertEquals(1, run.exit);
            assertTrue(run.stderr.contains("has no plugin.yml"), run.stderr);
            assertFalse(Files.exists(dir.resolve("server/plugins/Hello.jar")));
        }
    }

    @Test
    void aServerWithoutRconSaysToEnableIt() throws Exception {
        Run run = launch(jar("Hello", true), "online-mode=false\nenable-rcon=false\n", 1);
        assertEquals(1, run.exit);
        assertTrue(run.stderr.contains("RCON is off") && run.stderr.contains("enable-rcon=true"), run.stderr);
    }

    @Test
    void aSecondDeployWaitsForTheFirstInsteadOfReplacingItsJar() throws Exception {
        CountDownLatch firstAsked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Fake server = new Fake("hadron", (asked, jar, sha) -> {
            if (asked == 1) {
                firstAsked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
            return List.of(line(PluginStatus.State.ENABLED, jar, sha, null));
        })) {
            Path jarA = jar("Hello", true, "A");
            Path jarB = jar("Hello", true, "B");
            Started first = start(jarA, properties(server.port, "hadron"), 30);
            assertTrue(firstAsked.await(60, TimeUnit.SECONDS), "the first deploy installed and asked the server");
            Started second = start(jarB, properties(server.port, "hadron"), 30);
            assertTrue(second.nextLine().startsWith("Another deploy to "), "the second deploy says it waits its turn");
            assertTrue(Arrays.equals(Files.readAllBytes(jarA), Files.readAllBytes(dir.resolve("server/plugins/Hello.jar"))),
                    "the first deploy's jar is still the one in plugins/ while it waits for its answer");
            release.countDown();
            Run a = first.finish();
            Run b = second.finish();
            assertEquals(0, a.exit, a.stderr);
            assertEquals(0, b.exit, b.stderr);
            assertTrue(a.stdout.contains("sha256 " + sha12(jarA)), "the first reports its own jar: " + a.stdout);
            assertTrue(b.stdout.contains("sha256 " + sha12(jarB)), "the second reports its own jar: " + b.stdout);
            assertTrue(Arrays.equals(Files.readAllBytes(jarB), Files.readAllBytes(dir.resolve("server/plugins/Hello.jar"))),
                    "the second deploy's jar ends up installed");
        }
    }

    @Test
    void anInstallTakesItsIdentityFromItsOwnCopyNotFromWhatIsInPluginsAfterwards() throws Exception {
        Path jarA = jar("Hello", true, "A");
        Path jarB = jar("Hello", true, "B");
        File target = dir.resolve("server/plugins/Hello.jar").toFile();
        long[] justInstalled = new long[1];
        // Another deploy's jar replaces the target the moment this one's is renamed into place.
        Object version = install(jarA, target, () -> {
            justInstalled[0] = target.lastModified();
            try {
                Files.copy(jarB, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                assertTrue(target.setLastModified(justInstalled[0] + 60_000));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        assertEquals(sha(jarA), field(version, "sha256"), "the hash is of this deploy's jar, not the one that replaced it");
        assertEquals(justInstalled[0], field(version, "modified"), "and so is the modification time");
    }

    @Test
    void anInstallNeverTouchesAnotherDeploysUnfinishedCopy() throws Exception {
        Path plugins = Files.createDirectories(dir.resolve("server/plugins"));
        Path others = plugins.resolve(".Hello.jar.part");
        Files.writeString(others, "another deploy is still writing this");
        install(jar("Hello", true, "A"), plugins.resolve("Hello.jar").toFile(), () -> {});
        assertEquals("another deploy is still writing this", Files.readString(others));
        try (var files = Files.list(plugins)) {
            assertEquals(List.of(".Hello.jar.part", "Hello.jar"), files.map(f -> f.getFileName().toString()).sorted().toList(),
                    "this deploy leaves nothing of its own behind");
        }
    }

    // ---- Harness ----

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String sha12(Path file) throws Exception {
        return sha(file).substring(0, 12);
    }

    /** HadronDeploy compiled from its source file, as the deploy task's source launcher runs it, for calling in-process. */
    private Class<?> client() throws Exception {
        Path classes = Files.createDirectories(dir.resolve("client-classes"));
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(null, null, null, "-d", classes.toString(), "../gradle/deploy/HadronDeploy.java");
        assertEquals(0, status, "HadronDeploy.java compiles");
        return new java.net.URLClassLoader(new java.net.URL[] {classes.toUri().toURL()}).loadClass("HadronDeploy");
    }

    private Object install(Path jar, File target, Runnable afterRename) throws Exception {
        var method = client().getDeclaredMethod("install", File.class, File.class, Runnable.class);
        method.setAccessible(true);
        try {
            return method.invoke(null, jar.toFile(), target, afterRename);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    private static Object field(Object record, String name) throws Exception {
        var accessor = record.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(record);
    }

    private record Run(int exit, String stdout, String stderr) {}

    private Run deploy(int timeoutSeconds, Answer answer) throws Exception {
        return deploy(timeoutSeconds, answer, Integer.MAX_VALUE);
    }

    /** Runs a deploy against a stand-in server whose replies are cut into packets of {@code packetSize} bytes. */
    private Run deploy(int timeoutSeconds, Answer answer, int packetSize) throws Exception {
        try (Fake server = new Fake("hadron", answer, packetSize)) {
            return launch(jar("Hello", true), properties(server.port, "hadron"), timeoutSeconds);
        }
    }

    private String properties(int port, String password) {
        return "enable-rcon=true\nrcon.port=" + port + "\nrcon.password=" + password + "\n";
    }

    private Path jar(String name, boolean withPluginYml) throws IOException {
        return jar(name, withPluginYml, "");
    }

    /** A jar whose bytes differ with {@code marker}, built at its own path, so two builds can be told apart. */
    private Path jar(String name, boolean withPluginYml, String marker) throws IOException {
        Path jar = dir.resolve("build" + marker + "/" + name + ".jar");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            if (withPluginYml) {
                zip.putNextEntry(new ZipEntry("plugin.yml"));
                zip.write(("name: " + name + "\nversion: 1\nmain: x.Main\n").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("x/Main.class"));
            zip.write(("class " + marker).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }

    private Run launch(Path jar, String serverProperties, int timeoutSeconds) throws Exception {
        return start(jar, serverProperties, timeoutSeconds).finish();
    }

    /** A deploy client in progress: its output so far, and every stdout line as it arrives. */
    private static final class Started {
        final Process process;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final Thread outReader;
        private final Thread errReader;

        Started(Process process) {
            this.process = process;
            outReader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line = in.readLine(); line != null; line = in.readLine()) {
                        out.writeBytes((line + "\n").getBytes(StandardCharsets.UTF_8));
                        lines.add(line);
                    }
                } catch (IOException ignored) {
                    // the process ended
                }
            });
            errReader = new Thread(() -> copy(process.getErrorStream(), err));
            outReader.start();
            errReader.start();
        }

        /** The next stdout line, or a failure if none comes in a minute (so a missing line can't hang the build). */
        String nextLine() throws InterruptedException {
            String line = lines.poll(60, TimeUnit.SECONDS);
            if (line == null) throw new AssertionError("The deploy client printed nothing for 60 s");
            return line;
        }

        Run finish() throws Exception {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("The deploy client didn't exit within 120 s");
            }
            outReader.join();
            errReader.join();
            return new Run(process.exitValue(), out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        }
    }

    private Started start(Path jar, String serverProperties, int timeoutSeconds) throws Exception {
        Path server = dir.resolve("server");
        Files.createDirectories(server);
        Files.writeString(server.resolve("server.properties"), serverProperties);
        File source = new File("../gradle/deploy/HadronDeploy.java");
        return new Started(new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), source.getPath(),
                "--jar", jar.toString(), "--server-dir", server.toString(), "--install-as", "Hello.jar",
                "--timeout-seconds", Integer.toString(timeoutSeconds), "--poll-millis", "20").start());
    }

    private static void copy(java.io.InputStream in, OutputStream out) {
        try {
            in.transferTo(out);
        } catch (IOException ignored) {
            // the process ended
        }
    }

    /** A minimal RCON server: one connection at a time, answering {@code hadron status} from the installed jar. */
    private final class Fake implements AutoCloseable {
        final ServerSocket socket;
        final int port;
        private final String password;
        private final Answer answer;
        private final int packetSize;
        private final Thread thread;
        private final AtomicInteger asked = new AtomicInteger();

        Fake(String password, Answer answer) throws IOException {
            this(password, answer, Integer.MAX_VALUE);
        }

        Fake(String password, Answer answer, int packetSize) throws IOException {
            this.password = password;
            this.answer = answer;
            this.packetSize = packetSize;
            socket = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            port = socket.getLocalPort();
            thread = new Thread(this::serve);
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            while (!socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    Thread connection = new Thread(() -> {
                        try (client) {
                            converse(client);
                        } catch (IOException ignored) {
                            // a client went away, or the server closed
                        }
                    });
                    connection.setDaemon(true);
                    connection.start();
                } catch (IOException ignored) {
                    // the server closed
                }
            }
        }

        private void converse(Socket client) throws IOException {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            byte[] read = new byte[1460];
            while (true) {
                // As Minecraft's RconClient: one request per socket read, and a read holding anything but exactly one
                // frame disconnects. A busy server's read finds whatever the client has sent by then, so the stand-in
                // takes a short moment to read: a client that sends a second frame before the reply to the first
                // has it read together with the first, and is hung up on.
                client.setSoTimeout(0);
                int n = in.read(read);
                if (n < 0) return;
                client.setSoTimeout(150);
                try {
                    if (in.read(new byte[1460]) != 0) return; // more bytes (or the end) arrived with this request
                } catch (SocketTimeoutException alone) {
                    // nothing else came: the read held one request
                }
                if (n < 10 || ByteBuffer.wrap(read, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() != n - 4) return;
                int length = n - 4;
                byte[] data = Arrays.copyOfRange(read, 4, n);
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                int id = buf.getInt();
                int type = buf.getInt();
                String body = new String(data, 8, length - 10, StandardCharsets.UTF_8);
                if (type == 3) {
                    send(out, body.equals(password) ? id : -1, 2, "");
                } else if (type == 2) {
                    List<PluginStatus> lines = answer.to(asked.incrementAndGet(), installed(), sha());
                    if (lines == null) return; // hang up mid-command
                    StringBuilder reply = new StringBuilder();
                    for (PluginStatus s : lines) reply.append(s.format()).append('\n');
                    String text = reply.toString();
                    int at = 0;
                    do { // as Minecraft's: an empty reply is still one packet
                        send(out, id, 0, text.substring(at, Math.min(text.length(), at + packetSize)));
                        at += packetSize;
                    } while (at > 0 && at < text.length());
                } else {
                    send(out, id, 0, "Unknown request " + Integer.toHexString(type));
                }
            }
        }

        private File installed() {
            return dir.resolve("server/plugins/Hello.jar").toFile();
        }

        private String sha() throws IOException {
            try {
                return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(installed().toPath())));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        private void send(OutputStream out, int id, int type, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ByteBuffer packet = ByteBuffer.allocate(14 + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
            packet.putInt(10 + bytes.length).putInt(id).putInt(type).put(bytes).put((byte) 0).put((byte) 0);
            out.write(packet.array());
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
