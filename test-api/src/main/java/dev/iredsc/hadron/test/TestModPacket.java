package dev.iredsc.hadron.test;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The network message of Hadron's test mod, a stand-in for any mod's: a client sends it to the server, which hands it
 * to the mod's handler, so a test can drive {@code PlayerModPacketEvent} without a real mod. A test server (and only a
 * test server) registers it as {@code hadron:test_ping}, client to server, play phase. Send one with
 * {@code t.moddedPlayer("alice").sendModPacket(new TestModPacket("hi"))}; what the test mod's handler received is
 * {@link TestContext#modPacketsHandled}.
 */
public record TestModPacket(String text) implements CustomPacketPayload {
    public static final Type<TestModPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("hadron", "test_ping"));
    public static final StreamCodec<ByteBuf, TestModPacket> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(TestModPacket::new, TestModPacket::text);

    @Override
    public Type<TestModPacket> type() {
        return TYPE;
    }

    /** One message the test mod's handler ran for: who sent it, and what. */
    /**
     * One packet the test mod handled: who sent it, what it said, and whether the mod's handler ran on the server
     * thread. {@link TestModNetworkPacket}s are recorded here too, as a {@code TestModPacket} with the same text.
     */
    public record Handled(ServerPlayer player, TestModPacket packet, boolean onServerThread) {}
}
