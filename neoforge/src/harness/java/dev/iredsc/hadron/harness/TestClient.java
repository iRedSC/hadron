package dev.iredsc.hadron.harness;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.test.TestPlayer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.local.LocalChannel;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.arguments.ArgumentSignatures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ClientboundPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import dev.iredsc.hadron.harness.mixin.NetworkRegistryAccessor;
import java.util.Collection;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.payload.KnownRegistryDataMapsPayload;
import net.neoforged.neoforge.network.payload.KnownRegistryDataMapsReplyPayload;
import net.neoforged.neoforge.network.payload.ModdedNetworkPayload;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryPayload;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * A test player's client: a vanilla client's side of an in-memory connection, with no game attached. It answers
 * what the server needs answered to log in and stay connected (the login, the configuration phase, keep-alives,
 * teleport confirmations, chunk batches) the way a vanilla client does, and records everything else.
 *
 * <p>A modded client ({@code t.moddedPlayer}) is a NeoForge client with the server's own mods instead: it answers
 * NeoForge's channel query ({@link ModdedNetworkQueryPayload}) with every channel the server registered, as a real
 * client with the same mod list does, which makes the connection a NeoForge one. The server then negotiates the
 * channels, sends the result ({@link ModdedNetworkPayload}, kept here so {@link #sendModPacket} knows what the server
 * accepts) and runs NeoForge's configuration tasks; of those, a memory connection gets only config syncing (nothing to
 * answer) and the data map handshake, answered as knowing every data map offered. Mods' own configuration tasks still
 * get no answer.
 *
 * <p>Packets arrive on the connection's netty thread and are recorded there; {@link TestWorld#settle} waits for them,
 * so the test, on the server thread, reads a complete picture.
 */
@SuppressWarnings({"unchecked", "rawtypes"}) // Protocol switches with a listener that isn't the client interface.
final class TestClient implements TestPlayer {
    enum Phase { LOGIN, CONFIGURATION, PLAY, GONE }

    /** The furthest a client moves in one packet without the server calling it "moved too quickly". */
    private static final double MOVE_STEP = 8.0;

    private final MinecraftServer server;
    private final TestWorld world;
    private final String name;
    private final UUID uuid;
    private final Connection connection;
    /** A NeoForge client with the server's mods, rather than a vanilla one. */
    private final boolean modded;
    /** The channels the server agreed on with a modded client; null until it says. */
    private volatile @Nullable NetworkPayloadSetup channels;
    private volatile Phase phase = Phase.LOGIN;
    private volatile String disconnectReason = "";
    private volatile @Nullable Throwable failure;
    private volatile @Nullable ServerPlayer player;
    /** Bumped for each packet received, so settling knows when traffic stopped. */
    final AtomicLong activity = new AtomicLong();

    private final Object lock = new Object();
    private final List<Packet<?>> packets = new ArrayList<>();
    private final List<String> chat = new ArrayList<>();
    private final Set<UUID> sent = new HashSet<>();
    private final Map<Integer, UUID> shown = new HashMap<>();
    private final Set<UUID> tabPresent = new HashSet<>();
    private final Set<UUID> tabListed = new HashSet<>();
    private int sequence;
    /** Where the client last put itself, for teleports relative to it. Only the connection's thread uses these. */
    private double clientX, clientY, clientZ;
    private float clientYRot, clientXRot;
    /** Ticks since this client last sent its position; a vanilla client reminds the server every 20. */
    private int sincePosition;
    /**
     * Whether this client has answered a teleport with its position since the server last ticked. The server checks
     * a reported move against where the player was at the start of the tick, so a second answer in the same gap
     * (to the server's own "moved too quickly" correction) would be refused again, forever, since settling never
     * ticks. A real client's answer arrives after a tick has reset that, so the second waits for the next tick.
     */
    private volatile boolean answeredTeleport;
    /** The position report held back for the next tick, or null. */
    private final java.util.concurrent.atomic.AtomicReference<ServerboundMovePlayerPacket.PosRot> heldReport =
            new java.util.concurrent.atomic.AtomicReference<>();

    private final Listener login = new Listener(ConnectionProtocol.LOGIN);
    private final Listener configuration = new Listener(ConnectionProtocol.CONFIGURATION);
    private final Listener game = new Listener(ConnectionProtocol.PLAY);

    private TestClient(MinecraftServer server, TestWorld world, String name, boolean modded) {
        this.server = server;
        this.modded = modded;
        this.world = world;
        this.name = name;
        this.uuid = UUIDUtil.createOfflinePlayerUUID(name);
        this.connection = new Connection(PacketFlow.CLIENTBOUND);
    }

    /** Opens the connection and sends the login start; the rest of the login happens as the server ticks. */
    static TestClient connect(MinecraftServer server, TestWorld world, @Nullable SocketAddress address, String name,
            boolean modded) {
        if (address == null) {
            throw new IllegalStateException("The test server's in-memory channel isn't open yet, so " + name
                    + " can't join; players join from inside a test");
        }
        TestClient client = new TestClient(server, world, name, modded);
        Connection connection = client.connection;
        new Bootstrap().group(Connection.LOCAL_WORKER_GROUP.get()).handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                Connection.configureInMemoryPipeline(channel.pipeline(), PacketFlow.CLIENTBOUND);
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addBefore("packet_handler", "hadron_test_client", client.new Intercept());
            }
        }).channel(LocalChannel.class).connect(address).syncUninterruptibly();
        connection.initiateServerboundPlayConnection(address.toString(), 0, (ProtocolInfo) LoginProtocols.SERVERBOUND,
                (ProtocolInfo) LoginProtocols.CLIENTBOUND, client.login, false);
        connection.send(new ServerboundHelloPacket(name, client.uuid));
        return client;
    }

    // ---- The harness's view ----

    Phase phase() {
        return phase;
    }

    Connection connection() {
        return connection;
    }

    boolean modded() {
        return modded;
    }

    /** Set once the server has placed them in the world. */
    void joined(ServerPlayer player) {
        this.player = player;
    }

    /** A packet this client couldn't handle: a gap in the harness, reported rather than hidden. */
    @Nullable Throwable failure() {
        return failure;
    }

    /** Forgets the failure once reported, so one bad packet fails one step, not every step after it. */
    void clearFailure() {
        failure = null;
    }

    /**
     * After each server tick: what a vanilla client does on its own each tick that the server relies on. Here that's
     * the position reminder every 20 ticks; a test player has no physics, so it reports where the server has it.
     */
    void clientTick() {
        answeredTeleport = false;
        ServerboundMovePlayerPacket.PosRot held = heldReport.getAndSet(null);
        if (held != null) {
            sincePosition = 0;
            connection.send(held);
        }
        ServerPlayer p = player;
        if (p == null || !isConnected() || ++sincePosition < 20) return;
        sincePosition = 0;
        connection.send(new ServerboundMovePlayerPacket.Pos(p.getX(), p.getY(), p.getZ(), onGround(p, p.position())));
    }

    /** Sends their position now, as the client's next reminder would. */
    void reportPosition() {
        ServerPlayer p = player();
        sincePosition = 0;
        connection.send(new ServerboundMovePlayerPacket.Pos(p.getX(), p.getY(), p.getZ(), onGround(p, p.position())));
    }

    private static boolean onGround(ServerPlayer p, Vec3 at) {
        BlockPos below = BlockPos.containing(at.x, at.y - 0.01, at.z);
        return !p.serverLevel().getBlockState(below).getCollisionShape(p.serverLevel(), below).isEmpty();
    }

    // ---- TestPlayer: identity and setup ----

    @Override
    public ServerPlayer player() {
        if (player == null) throw new IllegalStateException(name + " hasn't finished joining");
        return player;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public void give(ItemStack stack) {
        world.requireIdle("give items");
        player().getInventory().add(stack.copy());
        world.settle();
    }

    @Override
    public void hold(ItemStack stack) {
        world.requireIdle("hand a player an item");
        ServerPlayer p = player();
        p.getInventory().setItem(p.getInventory().selected, stack.copy());
        world.settle();
    }

    @Override
    public void setOp(boolean op) {
        world.requireIdle("change op status");
        if (op) server.getPlayerList().op(player().getGameProfile());
        else server.getPlayerList().deop(player().getGameProfile());
        world.settle();
    }

    // ---- TestPlayer: actions ----

    @Override
    public void breakBlock(BlockPos pos) {
        ServerPlayer p = player();
        BlockState state = p.serverLevel().getBlockState(pos);
        if (state.isAir()) {
            throw new AssertionError(name + " can't break " + world.describe(pos) + ": there's no block there, only "
                    + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + ". Place one first with t.setBlock.");
        }
        requireReach(pos);
        float perTick = state.getDestroyProgress(p, p.serverLevel(), pos);
        if (!p.isCreative() && perTick <= 0) {
            throw new AssertionError(name + " can never break " + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                    + " at " + world.describe(pos) + " in " + p.gameMode.getGameModeForPlayer().getName()
                    + " mode. Use another block, or player().setGameMode(GameType.CREATIVE) to break anything.");
        }
        startDigging(pos);
        if (p.isCreative() || perTick >= 1.0f || p.serverLevel().getBlockState(pos) != state) return;
        // A client sends "stopped digging" on the tick its own progress reaches 1.
        world.tick((int) Math.ceil(1.0f / perTick));
        stopDigging(pos);
    }

    @Override
    public void startDigging(BlockPos pos) {
        send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos,
                facing(pos), nextSequence()), "dig");
        send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND), "dig");
    }

    @Override
    public void stopDigging(BlockPos pos) {
        send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos,
                facing(pos), nextSequence()), "dig");
    }

    @Override
    public void abortDigging(BlockPos pos) {
        send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos,
                facing(pos), nextSequence()), "dig");
    }

    @Override
    public void useBlock(BlockPos pos) {
        useBlock(pos, Direction.UP);
    }

    @Override
    public void useBlock(BlockPos pos, Direction face) {
        requireReach(pos);
        Vec3 hit = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
        send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false),
                nextSequence()), "use a block");
        send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND), "use a block");
    }

    @Override
    public void placeBlock(BlockPos pos) {
        ServerPlayer p = player();
        ItemStack held = p.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem)) {
            throw new AssertionError(name + " can't place a block holding " + (held.isEmpty() ? "nothing"
                    : BuiltInRegistries.ITEM.getKey(held.getItem())) + "; hold one first: "
                    + name + ".hold(new ItemStack(Items.STONE))");
        }
        ServerLevel level = p.serverLevel();
        for (Direction toward : Direction.values()) {
            BlockPos against = pos.relative(toward);
            BlockState there = level.getBlockState(against);
            if (!there.isAir() && !there.canBeReplaced()) {
                useBlock(against, toward.getOpposite());
                return;
            }
        }
        throw new AssertionError(name + " can't place a block at " + world.describe(pos) + ": none of its six "
                + "neighbours is a block to click against. Set one first with t.setBlock.");
    }

    @Override
    public void useItem() {
        ServerPlayer p = player();
        send(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, nextSequence(), p.getYRot(), p.getXRot()),
                "use an item");
        send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND), "use an item");
    }

    @Override
    public void attack(Entity target) {
        requireReach(target);
        send(ServerboundInteractPacket.createAttackPacket(target, player().isShiftKeyDown()), "attack");
        send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND), "attack");
    }

    @Override
    public void interact(Entity target) {
        requireReach(target);
        boolean sneaking = player().isShiftKeyDown();
        // As a vanilla client: the precise spot first, then the plain interaction.
        send(ServerboundInteractPacket.createInteractionPacket(target, sneaking, InteractionHand.MAIN_HAND,
                new Vec3(0, target.getBbHeight() / 2, 0)), "interact");
        send(ServerboundInteractPacket.createInteractionPacket(target, sneaking, InteractionHand.MAIN_HAND),
                "interact");
    }

    @Override
    public void clickSlot(int slot, int button, ClickType type) {
        AbstractContainerMenu menu = player().containerMenu;
        if (slot != AbstractContainerMenu.SLOT_CLICKED_OUTSIDE && !menu.isValidSlotIndex(slot)) {
            throw new AssertionError(name + "'s open menu (" + menu.getClass().getSimpleName() + ") has slots 0 to "
                    + (menu.slots.size() - 1) + ", not " + slot + ". Their own inventory counts the crafting grid "
                    + "first: 9 to 35 is the main inventory, 36 to 44 the hotbar.");
        }
        send(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), slot, button, type,
                menu.getCarried().copy(), new Int2ObjectOpenHashMap<>()), "click a slot");
    }

    @Override
    public void clickSlot(int slot) {
        clickSlot(slot, 0, ClickType.PICKUP);
    }

    @Override
    public void drop() {
        send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.DROP_ITEM, BlockPos.ZERO,
                Direction.DOWN), "drop an item");
    }

    @Override
    public void dropStack() {
        send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS, BlockPos.ZERO,
                Direction.DOWN), "drop a stack");
    }

    @Override
    public void chat(String message) {
        send(new ServerboundChatPacket(message, Instant.now(), 0L, null, new LastSeenMessages.Update(0, new BitSet())),
                "chat");
    }

    @Override
    public void command(String command) {
        String line = command.startsWith("/") ? command.substring(1) : command;
        // As a vanilla client: commands with message arguments (/msg, /say) go signed, here with no signatures.
        boolean signable = SignableCommand.hasSignableArguments(
                server.getCommands().getDispatcher().parse(line, player().createCommandSourceStack()));
        send(signable
                ? new ServerboundChatCommandSignedPacket(line, Instant.now(), 0L, ArgumentSignatures.EMPTY,
                        new LastSeenMessages.Update(0, new BitSet()))
                : new ServerboundChatCommandPacket(line), "run a command");
    }

    @Override
    public void moveTo(double x, double y, double z) {
        ServerPlayer p = player();
        Vec3 from = p.position();
        Vec3 to = new Vec3(x, y, z);
        int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) / MOVE_STEP));
        for (int i = 1; i <= steps; i++) {
            Vec3 at = from.lerp(to, (double) i / steps);
            send(new ServerboundMovePlayerPacket.Pos(at.x, at.y, at.z, onGround(p, at)), "move");
            sincePosition = 0;
            // The server allows a few packets' worth of distance per tick, as it would from a real client.
            if (i < steps) world.tick(1);
        }
        if (p.position().distanceTo(to) > 0.1) {
            throw new AssertionError("The server didn't let " + name + " move to " + fmt(to) + "; they're at "
                    + fmt(p.position()) + ". Moves into blocks are refused, as are cancelled PlayerMoveEvents; "
                    + "use teleport for a jump the server must allow.");
        }
    }

    @Override
    public void moveTo(BlockPos pos) {
        moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    @Override
    public void teleport(double x, double y, double z) {
        world.requireIdle("teleport");
        player().teleportTo(x, y, z);
        world.settle();
    }

    @Override
    public void selectSlot(int slot) {
        if (slot < 0 || slot > 8) {
            throw new AssertionError("Hotbar slots are 0 to 8, not " + slot + "; selectSlot(0) is the leftmost.");
        }
        send(new ServerboundSetCarriedItemPacket(slot), "select a slot");
    }

    @Override
    public void sendModPacket(CustomPacketPayload payload) {
        ResourceLocation id = payload.type().id();
        if (!modded) {
            throw new AssertionError(name + " joined as an unmodded client, which has no mod channels to send " + id
                    + " on. Join them as a modded client instead: t.moddedPlayer(\"" + name + "\").");
        }
        NetworkPayloadSetup agreed = channels;
        if (agreed == null || agreed.getChannel(ConnectionProtocol.PLAY, id) == null) {
            throw new AssertionError("No mod on this server registered a play channel " + id + ", so the server "
                    + "would disconnect " + name + " for sending it. Send a payload a mod registers in "
                    + "RegisterPayloadHandlersEvent, or Hadron's test mod's: new TestModPacket(\"hi\").");
        }
        if (NetworkRegistry.getCodec(id, ConnectionProtocol.PLAY, PacketFlow.SERVERBOUND) == null) {
            throw new AssertionError(id + " is a channel the server sends on, not one a client sends; the server "
                    + "would refuse it from " + name + ". Send a payload its mod registers with playToServer or "
                    + "playBidirectional.");
        }
        send(new ServerboundCustomPayloadPacket(payload), "send a mod packet");
    }

    @Override
    public void disconnect() {
        world.requireIdle("disconnect");
        if (phase == Phase.GONE) return;
        connection.disconnect(Component.literal("Disconnected"));
        // The server notices a closed connection on its next tick, as with a real client.
        world.tick(1);
    }

    /** The harness ending the test: kicked by the server, so they're gone before the next line runs. */
    void kick(String reason) {
        ServerPlayer p = player;
        if (p != null && p.connection != null && server.getPlayerList().getPlayer(uuid) == p) {
            p.connection.disconnect(Component.literal(reason));
        } else {
            connection.disconnect(Component.literal(reason));
        }
    }

    // ---- TestPlayer: observations ----

    @Override
    public List<Packet<?>> received() {
        synchronized (lock) {
            return List.copyOf(packets);
        }
    }

    @Override
    public <P> List<P> received(Class<P> type) {
        List<P> out = new ArrayList<>();
        synchronized (lock) {
            for (Packet<?> p : packets) if (type.isInstance(p)) out.add(type.cast(p));
        }
        return out;
    }

    @Override
    public List<String> chat() {
        synchronized (lock) {
            return List.copyOf(chat);
        }
    }

    @Override
    public boolean wasSent(Entity entity) {
        synchronized (lock) {
            return sent.contains(entity.getUUID());
        }
    }

    @Override
    public boolean isShown(Entity entity) {
        synchronized (lock) {
            return shown.containsValue(entity.getUUID());
        }
    }

    @Override
    public boolean inTabList(UUID id) {
        synchronized (lock) {
            return tabPresent.contains(id) && tabListed.contains(id);
        }
    }

    @Override
    public void clearReceived() {
        synchronized (lock) {
            packets.clear();
            chat.clear();
            sent.clear();
        }
    }

    @Override
    public boolean isConnected() {
        return phase != Phase.GONE && connection.isConnected();
    }

    @Override
    public String disconnectReason() {
        return disconnectReason;
    }

    @Override
    public String toString() {
        return name;
    }

    // ---- Sending ----

    private void send(Packet<?> packet, String what) {
        world.requireIdle(what);
        if (!isConnected()) {
            throw new AssertionError(name + " can't " + what + ": they're no longer connected ("
                    + disconnectReason + ").");
        }
        connection.send(packet);
        world.settle();
    }

    private int nextSequence() {
        synchronized (lock) {
            return ++sequence;
        }
    }

    private Direction facing(BlockPos pos) {
        Vec3 eye = player().getEyePosition();
        Vec3 d = Vec3.atCenterOf(pos).subtract(eye);
        return Direction.getNearest(d.x, d.y, d.z).getOpposite();
    }

    private void requireReach(BlockPos pos) {
        ServerPlayer p = player();
        if (!p.canInteractWithBlock(pos, 1.0)) {
            throw new AssertionError(name + " can't reach " + world.describe(pos) + ": it's "
                    + String.format("%.1f", Math.sqrt(p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))))
                    + " blocks from their eyes, past their reach of " + p.blockInteractionRange() + ". Move them "
                    + "closer first: " + name + ".teleport(x, y, z).");
        }
    }

    private void requireReach(Entity target) {
        ServerPlayer p = player();
        if (!p.canInteractWithEntity(target, 3.0)) {
            throw new AssertionError(name + " can't reach " + target.getName().getString() + ": it's "
                    + String.format("%.1f", p.distanceTo(target)) + " blocks away, past their reach of "
                    + p.entityInteractionRange() + ". Move them closer first: " + name + ".teleport(x, y, z).");
        }
    }

    private static String fmt(Vec3 v) {
        return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    // ---- Receiving: on the connection's thread ----

    private void receive(Packet<?> packet) {
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> sub : bundle.subPackets()) receive(sub);
            return;
        }
        try {
            record(packet);
            answer(packet);
        } catch (RuntimeException | Error e) {
            failure = new IllegalStateException("Hadron's test client for " + name + " failed on "
                    + packet.getClass().getSimpleName() + ", a bug in the test harness rather than the test", e);
            throw e;
        } finally {
            activity.incrementAndGet();
        }
    }

    private void record(Packet<?> packet) {
        synchronized (lock) {
            packets.add(packet);
            switch (packet) {
                case ClientboundSystemChatPacket p -> {
                    if (!p.overlay()) chat.add(p.content().getString());
                }
                case ClientboundPlayerChatPacket p -> {
                    Component content = p.unsignedContent() != null ? p.unsignedContent()
                            : Component.literal(p.body().content());
                    chat.add(p.chatType().decorate(content).getString());
                }
                case ClientboundDisguisedChatPacket p -> chat.add(p.chatType().decorate(p.message()).getString());
                case ClientboundAddEntityPacket p -> {
                    sent.add(p.getUUID());
                    shown.put(p.getId(), p.getUUID());
                }
                case ClientboundRemoveEntitiesPacket p -> p.getEntityIds().forEach(id -> shown.remove(id));
                // A new level on the client: it starts with no entities.
                case ClientboundRespawnPacket p -> shown.clear();
                case ClientboundLoginPacket p -> shown.clear();
                case ClientboundPlayerInfoUpdatePacket p -> {
                    for (ClientboundPlayerInfoUpdatePacket.Entry e : p.entries()) {
                        if (p.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                            tabPresent.add(e.profileId());
                        }
                        if (p.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED)) {
                            if (e.listed()) tabListed.add(e.profileId());
                            else tabListed.remove(e.profileId());
                        }
                    }
                }
                case ClientboundPlayerInfoRemovePacket p -> {
                    p.profileIds().forEach(tabPresent::remove);
                    p.profileIds().forEach(tabListed::remove);
                }
                default -> {}
            }
        }
    }

    /** What a vanilla client answers, so the server goes on as it would with one. */
    private void answer(Packet<?> packet) {
        switch (packet) {
            case ClientboundKeepAlivePacket p -> connection.send(new ServerboundKeepAlivePacket(p.getId()));
            case ClientboundPingPacket p -> connection.send(new ServerboundPongPacket(p.getId()));
            case ClientboundCookieRequestPacket p -> connection.send(new ServerboundCookieResponsePacket(p.key(), null));
            case ClientboundResourcePackPushPacket p ->
                    connection.send(new ServerboundResourcePackPacket(p.id(), ServerboundResourcePackPacket.Action.DECLINED));
            case ClientboundHelloPacket p -> connection.disconnect(Component.literal(
                    "The server asked a test player to authenticate; memory connections never should"));
            case ClientboundCustomQueryPacket p ->
                    connection.send(new ServerboundCustomQueryAnswerPacket(p.transactionId(), null));
            case ClientboundLoginDisconnectPacket p -> disconnectReason = p.getReason().getString();
            case ClientboundDisconnectPacket p -> disconnectReason = p.reason().getString();
            case ClientboundGameProfilePacket p -> {
                connection.setupInboundProtocol((ProtocolInfo) ConfigurationProtocols.CLIENTBOUND, configuration);
                connection.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
                connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
                connection.send(new ServerboundCustomPayloadPacket(new BrandPayload(modded ? "neoforge" : "vanilla")));
                // What a real client sends with its options untouched: unlike vanilla's server-side default, a client allows
                // server listings unless the player turns it off, so the server list's sample shows test players.
                connection.send(new ServerboundClientInformationPacket(new ClientInformation("en_us", 12,
                        net.minecraft.world.entity.player.ChatVisiblity.FULL, true, 0x7F,
                        net.minecraft.world.entity.player.Player.DEFAULT_MAIN_HAND, false, true)));
                phase = Phase.CONFIGURATION;
            }
            // Claims every pack the server offers, so it sends no registry contents it doesn't have to.
            case ClientboundSelectKnownPacks p -> connection.send(new ServerboundSelectKnownPacks(p.knownPacks()));
            case ClientboundCustomPayloadPacket p -> answerPayload(p.payload());
            case ClientboundFinishConfigurationPacket p -> {
                RegistryAccess registries = server.registryAccess();
                // As the server encodes for this connection: NeoForge's play encoding for a modded client.
                ConnectionType type = modded ? ConnectionType.NEOFORGE : ConnectionType.OTHER;
                connection.setupInboundProtocol((ProtocolInfo) GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        RegistryFriendlyByteBuf.decorator(registries, type)), game);
                connection.send(ServerboundFinishConfigurationPacket.INSTANCE);
                connection.setupOutboundProtocol(
                        GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries, type)));
                phase = Phase.PLAY;
            }
            case ClientboundStartConfigurationPacket p -> {
                connection.setupInboundProtocol((ProtocolInfo) ConfigurationProtocols.CLIENTBOUND, configuration);
                connection.send(ServerboundConfigurationAcknowledgedPacket.INSTANCE);
                connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
                phase = Phase.CONFIGURATION;
            }
            case ClientboundPlayerPositionPacket p -> {
                // As a vanilla client: confirm, then report standing there. The server pairs nearby entities with
                // a player on their move packets, so without this a teleported player would see nobody.
                Set<RelativeMovement> rel = p.getRelativeArguments();
                double x = rel.contains(RelativeMovement.X) ? clientX + p.getX() : p.getX();
                double y = rel.contains(RelativeMovement.Y) ? clientY + p.getY() : p.getY();
                double z = rel.contains(RelativeMovement.Z) ? clientZ + p.getZ() : p.getZ();
                float yRot = rel.contains(RelativeMovement.Y_ROT) ? clientYRot + p.getYRot() : p.getYRot();
                float xRot = rel.contains(RelativeMovement.X_ROT) ? clientXRot + p.getXRot() : p.getXRot();
                clientX = x;
                clientY = y;
                clientZ = z;
                clientYRot = yRot;
                clientXRot = xRot;
                connection.send(new ServerboundAcceptTeleportationPacket(p.getId()));
                ServerboundMovePlayerPacket.PosRot report = new ServerboundMovePlayerPacket.PosRot(x, y, z, yRot, xRot,
                        false);
                if (answeredTeleport) {
                    heldReport.set(report);
                } else {
                    answeredTeleport = true;
                    connection.send(report);
                }
            }
            case ClientboundChunkBatchFinishedPacket p -> connection.send(new ServerboundChunkBatchReceivedPacket(64f));
            default -> {}
        }
    }

    /**
     * What a NeoForge client answers of NeoForge's own messages; a vanilla client answers none, and neither answers a
     * mod's. The query comes before the server's ping 0, so the answer reaches it before the pong that makes the server
     * decide what kind of client this is.
     */
    private void answerPayload(CustomPacketPayload payload) {
        if (!modded) return;
        switch (payload) {
            case ModdedNetworkQueryPayload q -> connection.send(new ServerboundCustomPayloadPacket(
                    ModdedNetworkQueryPayload.fromRegistry(NetworkRegistryAccessor.hadron$payloadRegistrations())));
            case ModdedNetworkPayload p -> channels = p.setup();
            case KnownRegistryDataMapsPayload p -> {
                Map<ResourceKey<? extends Registry<?>>, Collection<ResourceLocation>> known = new HashMap<>();
                p.dataMaps().forEach((registry, maps) ->
                        known.put(registry, maps.stream().map(KnownRegistryDataMapsPayload.KnownDataMap::id).toList()));
                connection.send(new ServerboundCustomPayloadPacket(new KnownRegistryDataMapsReplyPayload(known)));
            }
            default -> {}
        }
    }

    private void disconnected(DisconnectionDetails details) {
        if (disconnectReason.isEmpty()) disconnectReason = details.reason().getString();
        phase = Phase.GONE;
        activity.incrementAndGet();
    }

    /**
     * What the connection holds as its listener: only what it needs to switch protocols and report a disconnect.
     * Packets never reach it; {@link Intercept} takes them first. Minecraft's client listener interfaces can't be
     * used here at all: NeoForge's extension of them names the client's {@code Minecraft} class, which a dedicated
     * server refuses to load.
     */
    private final class Listener implements ClientboundPacketListener {
        private final ConnectionProtocol protocol;

        Listener(ConnectionProtocol protocol) {
            this.protocol = protocol;
        }

        @Override
        public ConnectionProtocol protocol() {
            return protocol;
        }

        @Override
        public void onDisconnect(DisconnectionDetails details) {
            disconnected(details);
        }

        @Override
        public boolean isAcceptingMessages() {
            return connection.isConnected();
        }

        @Override
        public String toString() {
            return "test client " + name + " (" + protocol.id() + ")";
        }
    }

    /** Sits just before the connection's own handler and takes every decoded packet, so it can be recorded. */
    private final class Intercept extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            if (message instanceof Packet<?> packet) receive(packet);
            else context.fireChannelRead(message);
        }
    }
}
