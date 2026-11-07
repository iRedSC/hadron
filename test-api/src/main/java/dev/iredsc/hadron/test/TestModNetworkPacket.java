package dev.iredsc.hadron.test;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The test mod's second channel, {@code hadron:test_ping_net}, whose handler NeoForge runs on the network thread, as
 * a mod can ask for. Send it with {@code t.moddedPlayer("alice").sendModPacket(new TestModNetworkPacket("hi"))}; what
 * the handler received, and on which thread, is in {@link TestContext#modPacketsHandled}.
 */
public record TestModNetworkPacket(String text) implements CustomPacketPayload {
    public static final Type<TestModNetworkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("hadron", "test_ping_net"));
    public static final StreamCodec<ByteBuf, TestModNetworkPacket> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(TestModNetworkPacket::new, TestModNetworkPacket::text);

    @Override
    public Type<TestModNetworkPacket> type() {
        return TYPE;
    }
}
