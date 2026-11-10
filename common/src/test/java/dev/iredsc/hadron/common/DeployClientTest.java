package dev.iredsc.hadron.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
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

    // ---- Harness ----

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
        Path jar = dir.resolve("build/" + name + ".jar");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            if (withPluginYml) {
                zip.putNextEntry(new ZipEntry("plugin.yml"));
                zip.write(("name: " + name + "\nversion: 1\nmain: x.Main\n").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("x/Main.class"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }
        return jar;
    }

    private Run launch(Path jar, String serverProperties, int timeoutSeconds) throws Exception {
        Path server = dir.resolve("server");
        Files.createDirectories(server);
        Files.writeString(server.resolve("server.properties"), serverProperties);
        File source = new File("../gradle/deploy/HadronDeploy.java");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), source.getPath(),
                "--jar", jar.toString(), "--server-dir", server.toString(), "--install-as", "Hello.jar",
                "--timeout-seconds", Integer.toString(timeoutSeconds), "--poll-millis", "20").start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outReader = new Thread(() -> copy(process.getInputStream(), out));
        Thread errReader = new Thread(() -> copy(process.getErrorStream(), err));
        outReader.start();
        errReader.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("The deploy client didn't exit within 120 s");
        }
        outReader.join();
        errReader.join();
        return new Run(process.exitValue(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
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
                try (Socket client = socket.accept()) {
                    converse(client);
                } catch (IOException ignored) {
                    // a client went away, or the server closed
                }
            }
        }

        private void converse(Socket client) throws IOException {
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            while (true) {
                int length;
                try {
                    length = Integer.reverseBytes(in.readInt());
                } catch (EOFException e) {
                    return;
                }
                byte[] data = new byte[length];
                in.readFully(data);
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
                    for (int at = 0; at < text.length(); at += packetSize) {
                        send(out, id, 0, text.substring(at, Math.min(text.length(), at + packetSize)));
                    }
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
