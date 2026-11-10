// Installs a plugin jar into a running dev server and waits until the server says it loaded THIS jar.
//
//     java HadronDeploy.java --jar build/libs/hello.jar --server-dir neoforge/run --install-as Hello.jar
//
// Run by the `deploy` task (gradle/hadron-deploy.gradle.kts); the Java source launcher runs this one file, so it uses
// nothing but the JDK. It reads RCON's port and password from <server-dir>/server.properties, installs the jar as
// plugins/<install-as> (written beside it, then renamed, so the server never reads half a jar), then asks
// `hadron status` (the host's plain-text answer, PluginStatus in common) until the line for that jar names the file
// version it just wrote: the same content hash and the same modification time. Nothing else counts as success,
// so an older copy still loaded can't pass for the new one.
//
// Exit 0 and one line on stdout: the plugin is enabled. Exit 1 and the reason on stderr (naming the fix): the plugin
// failed to load, the jar was refused, or the server never picked it up. Optional --timeout-seconds (default 30) and
// --poll-millis (default 250).
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class HadronDeploy {
    private HadronDeploy() {}

    /** A failure the reader can act on: the message says what to do. */
    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        try {
            System.out.println(deploy(Args.parse(args)));
        } catch (Failure e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    record Args(File jar, File serverDir, String installAs, long timeoutMillis, long pollMillis) {
        static Args parse(String[] args) throws Failure {
            Map<String, String> values = new HashMap<>();
            for (int i = 0; i < args.length; i += 2) {
                if (!args[i].startsWith("--") || i + 1 >= args.length) {
                    throw new Failure("HadronDeploy: expected --name value pairs, got " + String.join(" ", args));
                }
                values.put(args[i].substring(2), args[i + 1]);
            }
            for (String key : List.of("jar", "server-dir", "install-as")) {
                if (!values.containsKey(key)) throw new Failure("HadronDeploy: --" + key + " is missing");
            }
            return new Args(new File(values.get("jar")), new File(values.get("server-dir")), values.get("install-as"),
                    Long.parseLong(values.getOrDefault("timeout-seconds", "30")) * 1000,
                    Long.parseLong(values.getOrDefault("poll-millis", "250")));
        }
    }

    // ---- The flow ----

    static String deploy(Args args) throws Failure {
        if (!args.jar().isFile()) {
            throw new Failure("There is no jar at " + args.jar() + " to deploy: build it first (./gradlew jar).");
        }
        String plugin = pluginName(args.jar());
        Rcon.Settings rcon = Rcon.Settings.read(args.serverDir());
        File plugins = new File(args.serverDir(), "plugins");
        try (Rcon connection = Rcon.open(rcon)) {
            // Refused before anything is copied: with no server to tell us, the copy would be unverified.
            Files.createDirectories(plugins.toPath());
            // One deploy at a time per server: a second one's jar would replace this one's while it waits, and the
            // server would then report a build nobody is waiting for.
            try (FileChannel channel = FileChannel.open(plugins.toPath().resolve(".deploy.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = lock(channel, plugins)) {
                Version version = install(args.jar(), new File(plugins, args.installAs()));
                return awaitLoaded(args, plugin, connection, rcon, version);
            }
        } catch (IOException e) {
            throw new Failure("Deploying " + args.installAs() + " failed: " + e + ". Check that " + plugins
                    + " exists and is writable.");
        }
    }

    /** Takes the folder's deploy lock, saying so first when another deploy holds it: this one waits its turn. */
    private static FileLock lock(FileChannel channel, File plugins) throws IOException {
        FileLock lock = channel.tryLock();
        if (lock != null) return lock;
        System.out.println("Another deploy to " + plugins + " is running: waiting for it to finish.");
        System.out.flush();
        return channel.lock();
    }

    /** Asks the server until it reports exactly {@code version}: enabled, or why not; fails after the timeout. */
    private static String awaitLoaded(Args args, String plugin, Rcon connection, Rcon.Settings rcon, Version version)
            throws Failure, IOException {
        long start = System.nanoTime();
        String last = "nothing about " + args.installAs();
        while (true) {
            List<Status> lines;
            try {
                lines = Status.parseAll(connection.run("hadron status"));
            } catch (Rcon.Unanswered e) {
                throw new Failure(rcon.unanswered(e.getMessage()));
            }
            Status mine = Status.of(lines, args.installAs(), version);
            if (mine != null) {
                switch (Status.verdict(mine, plugin, args.installAs())) {
                    case Verdict.Done done -> {
                        return plugin + " enabled on the dev server (" + args.installAs() + ", sha256 "
                                + version.sha256().substring(0, 12) + ", "
                                + (System.nanoTime() - start) / 1_000_000 + " ms)";
                    }
                    case Verdict.Rejected rejected -> throw new Failure(rejected.message());
                    case Verdict.Waiting waiting -> last = waiting.what();
                }
            }
            if (System.nanoTime() - start > args.timeoutMillis() * 1_000_000L) {
                throw new Failure("The dev server didn't load " + args.installAs() + " within "
                        + args.timeoutMillis() / 1000 + " s (it reported " + last + "). It reloads a changed jar in "
                        + "plugins/ by itself, so check that it runs from " + args.serverDir() + " and read its log: "
                        + new File(args.serverDir(), "logs/latest.log"));
            }
            pause(args.pollMillis());
        }
    }

    private static void pause(long millis) throws Failure {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("Interrupted while waiting for the dev server.");
        }
    }

    /** The plugin.yml name inside the jar, for messages. */
    static String pluginName(File jar) throws Failure {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry("plugin.yml");
            if (entry == null) {
                throw new Failure(jar.getName() + " has no plugin.yml, so Hadron can't load it. Put one in "
                        + "src/main/resources with name:, version:, minecraft: and main:.");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    if (line.startsWith("name:")) return line.substring("name:".length()).strip().replaceAll("^['\"]|['\"]$", "");
                }
            }
        } catch (IOException e) {
            throw new Failure(jar + " can't be read as a jar (" + e.getMessage() + "): rebuild it with ./gradlew jar.");
        }
        throw new Failure(jar.getName() + "'s plugin.yml has no name:. Add name: MyPlugin.");
    }

    /** The exact file version written: what the host must report back before it counts. */
    record Version(long modified, String sha256) {}

    /**
     * Writes the jar to a file of its own beside the target, reads its identity there, and renames it into place. The
     * identity comes from that private copy, before the rename, so nothing that replaces the target afterwards can
     * make this deploy report a different jar's status. The copy's name is unique, so deploys never share one. The
     * modification time is set past the old file's, so redeploying identical bytes is still a change the host acts
     * on, and a status line left over from the previous deploy can never match this one.
     */
    static Version install(File jar, File target) throws IOException {
        return install(jar, target, () -> {});
    }

    /** As {@link #install(File, File)}, running {@code afterRename} once the jar is in place (for tests). */
    static Version install(File jar, File target, Runnable afterRename) throws IOException {
        Path dir = target.toPath().toAbsolutePath().getParent();
        Files.createDirectories(dir);
        long previous = target.isFile() ? target.lastModified() : 0;
        Path part = Files.createTempFile(dir, "." + target.getName() + ".", ".part");
        try {
            Files.copy(jar.toPath(), part, StandardCopyOption.REPLACE_EXISTING);
            long wanted = Math.max(System.currentTimeMillis(), previous + 1000);
            if (!part.toFile().setLastModified(wanted)) {
                throw new IOException("can't set the modification time of " + part);
            }
            Version version = new Version(part.toFile().lastModified(), sha256(Files.readAllBytes(part)));
            Files.move(part, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            afterRename.run();
            return version;
        } finally {
            Files.deleteIfExists(part); // only still there if something above failed
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- What `hadron status` says ----

    /** One line of `hadron status`; see PluginStatus in common for the format. */
    record Status(String plugin, String state, String jar, long modified, String sha256, String why) {
        /** Every status line in a reply; text that isn't one (an error, a banner) is left out. */
        static List<Status> parseAll(String reply) {
            List<Status> out = new ArrayList<>();
            for (String line : reply.split("\\R")) {
                Status s = parse(line);
                if (s != null) out.add(s);
            }
            return out;
        }

        /** The line, or null if it isn't one. {@code plugin} is empty for a refused jar. */
        static Status parse(String line) {
            String[] fields = line.strip().split("\t");
            if (fields.length < 5) return null;
            String plugin;
            if (fields[0].equals("refused")) plugin = "";
            else if (fields[0].startsWith("plugin=")) plugin = fields[0].substring("plugin=".length());
            else return null;
            Map<String, String> values = new HashMap<>();
            for (int i = 1; i < fields.length; i++) {
                int eq = fields[i].indexOf('=');
                if (eq < 0) return null;
                values.put(fields[i].substring(0, eq), fields[i].substring(eq + 1));
            }
            for (String key : List.of("state", "jar", "modified", "sha256")) {
                if (!values.containsKey(key)) return null;
            }
            try {
                return new Status(plugin, values.get("state"), values.get("jar"), Long.parseLong(values.get("modified")),
                        values.get("sha256"), values.get("why"));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** The line about exactly this file version, or null while the host hasn't acted on it yet. */
        static Status of(List<Status> lines, String jar, Version version) {
            for (Status s : lines) {
                if (s.jar.equals(jar) && s.modified == version.modified() && s.sha256.equals(version.sha256())) return s;
            }
            return null;
        }

        static Verdict verdict(Status s, String plugin, String jar) {
            return switch (s.state) {
                case "enabled" -> new Verdict.Done();
                case "failed" -> new Verdict.Rejected(plugin + " failed to load: " + s.why);
                case "refused" -> new Verdict.Rejected(jar + " was refused: " + s.why);
                case "off" -> new Verdict.Rejected(plugin + " is switched off on the dev server, so it isn't running "
                        + "this build. Turn it on with: python3 scripts/rcon.py \"hadron enable " + plugin + "\"");
                default -> new Verdict.Waiting(plugin + " is " + s.state);
            };
        }
    }

    sealed interface Verdict {
        record Done() implements Verdict {}

        record Rejected(String message) implements Verdict {}

        record Waiting(String what) implements Verdict {}
    }

    // ---- RCON ----

    /** A Source RCON client: authenticates, runs commands, and reads replies of any length. */
    static final class Rcon implements AutoCloseable {
        private static final int LOGIN = 3;
        private static final int COMMAND = 2;
        private static final int AUTH_RESPONSE = 2;
        private static final int RESPONSE = 0;

        /** Where the server listens and how to log in, from its server.properties. */
        record Settings(String host, int port, String password) {
            static Settings read(File serverDir) throws Failure {
                File file = new File(serverDir, "server.properties");
                if (!file.isFile()) {
                    throw new Failure("No dev server has been set up in " + serverDir + " (no server.properties): start "
                            + "it once with ./gradlew :neoforge:runServer, which enables RCON, then deploy again.");
                }
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(file.toPath())) {
                    props.load(in);
                } catch (IOException e) {
                    throw new Failure("Can't read " + file + ": " + e.getMessage());
                }
                if (!Boolean.parseBoolean(props.getProperty("enable-rcon", "false"))) {
                    throw new Failure("RCON is off in " + file + ", so a deploy can't ask the server whether the plugin "
                            + "loaded. Set enable-rcon=true, rcon.port=25598 and rcon.password=hadron there, then restart "
                            + "the server.");
                }
                String password = props.getProperty("rcon.password", "");
                if (password.isEmpty()) {
                    throw new Failure(file + " has no rcon.password, which RCON needs. Set rcon.password=hadron there "
                            + "and restart the server.");
                }
                try {
                    return new Settings("127.0.0.1", Integer.parseInt(props.getProperty("rcon.port", "25575").strip()),
                            password);
                } catch (NumberFormatException e) {
                    throw new Failure(file + " has rcon.port=" + props.getProperty("rcon.port") + ", which isn't a "
                            + "port number. Set rcon.port=25598 there.");
                }
            }

            String unanswered(String why) {
                return "The dev server on rcon port " + port + " stopped answering (" + why + "). Check its log, "
                        + "and start it again with ./gradlew :neoforge:runServer if it exited.";
            }
        }

        /** The server went quiet or closed the connection mid-command. */
        static final class Unanswered extends IOException {
            Unanswered(String message) {
                super(message);
            }
        }

        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;
        private int nextId = 10;

        private Rcon(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        static Rcon open(Settings settings) throws Failure {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(settings.host(), settings.port()), 3000);
                socket.setSoTimeout(10_000);
            } catch (IOException e) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // already failing
                }
                throw new Failure("No dev server answered on rcon port " + settings.port() + " (" + e.getMessage()
                        + "): start it with ./gradlew :neoforge:runServer, and wait for it to finish starting.");
            }
            try {
                Rcon rcon = new Rcon(socket);
                rcon.login(settings);
                return rcon;
            } catch (Failure e) {
                closeQuietly(socket);
                throw e;
            } catch (IOException e) {
                closeQuietly(socket);
                throw new Failure("The server on rcon port " + settings.port() + " accepted the connection but "
                        + "didn't finish logging in (" + e.getMessage() + "). It may still be starting: try again in a "
                        + "few seconds.");
            }
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing to add
            }
        }

        private void login(Settings settings) throws IOException, Failure {
            int id = nextId++;
            send(id, LOGIN, settings.password());
            while (true) {
                Packet p = read();
                if (p.type != AUTH_RESPONSE) continue; // some servers send an empty response first
                if (p.id == -1) {
                    throw new Failure("The dev server refused the RCON password from server.properties. Set "
                            + "rcon.password there to the password the running server was started with (./gradlew "
                            + ":neoforge:runServer writes hadron), or restart the server after changing it.");
                }
                return;
            }
        }

        /**
         * Runs one command and returns everything it printed. A long reply arrives as several packets with no end
         * marker, so a second, invalid request follows the command: the server answers it only after the whole reply.
         * It is sent only once the command's first reply packet has arrived (the server always sends one): Minecraft's
         * RCON reads one request per socket read and hangs up on two frames read together, so the command would never
         * run.
         */
        String run(String command) throws IOException {
            int id = nextId++;
            int sentinel = nextId++;
            send(id, COMMAND, command);
            StringBuilder reply = new StringBuilder();
            Packet first = read();
            while (first.id != id) first = read();
            reply.append(first.body);
            send(sentinel, RESPONSE, "");
            while (true) {
                Packet p = read();
                if (p.id == sentinel) return reply.toString();
                if (p.id == id) reply.append(p.body);
            }
        }

        private void send(int id, int type, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ByteBuffer packet = ByteBuffer.allocate(14 + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
            packet.putInt(10 + bytes.length).putInt(id).putInt(type).put(bytes).put((byte) 0).put((byte) 0);
            out.write(packet.array());
            out.flush();
        }

        private record Packet(int id, int type, String body) {}

        private Packet read() throws IOException {
            try {
                byte[] header = new byte[4];
                in.readFully(header);
                int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (length < 10 || length > 8192) throw new Unanswered("the server sent a packet of " + length + " bytes");
                byte[] data = new byte[length];
                in.readFully(data);
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                int id = buf.getInt();
                int type = buf.getInt();
                return new Packet(id, type, new String(data, 8, length - 10, StandardCharsets.UTF_8));
            } catch (EOFException e) {
                throw new Unanswered("it closed the connection");
            } catch (SocketTimeoutException e) {
                throw new Unanswered("no reply within 10 s");
            }
        }

        @Override
        public void close() {
            closeQuietly(socket);
        }
    }
}
