package dev.iredsc.hadron.harness;

import dev.iredsc.hadron.test.TestModPacket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Hadron's test mod: two network channels, {@link TestModPacket} ({@code hadron:test_ping}) and
 * {@code TestModNetworkPacket} ({@code hadron:test_ping_net}, whose handler runs on the network thread), client to
 * server, play, whose handlers record what they received and on which thread, so tests can send a mod packet and see whether the mod handled it. Only a
 * test server registers it ({@link TestRunner#register}); a normal server never has the channel.
 *
 * <p>Optional, as NeoForge calls it, so unmodded test players still join; modded ones negotiate it with the rest.
 */
final class TestMod {
    private static final List<TestModPacket.Handled> HANDLED = new CopyOnWriteArrayList<>();

    private TestMod() {}

    static void register(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent e) -> {
            e.registrar("1").optional().playToServer(TestModPacket.TYPE, TestModPacket.STREAM_CODEC,
                    (payload, context) -> handled((ServerPlayer) context.player(), payload.text()));
            // A mod may ask for its handler on the network thread; Hadron moves that one to the server thread only
            // while a plugin listens, so this channel tests that path.
            e.registrar("1").optional().executesOn(net.neoforged.neoforge.network.registration.HandlerThread.NETWORK)
                    .playToServer(dev.iredsc.hadron.test.TestModNetworkPacket.TYPE,
                            dev.iredsc.hadron.test.TestModNetworkPacket.STREAM_CODEC,
                            (payload, context) -> handled((ServerPlayer) context.player(), payload.text()));
        });
    }

    private static void handled(ServerPlayer player, String text) {
        HANDLED.add(new TestModPacket.Handled(player, new TestModPacket(text), player.server.isSameThread()));
    }

    static List<TestModPacket.Handled> handled() {
        return List.copyOf(HANDLED);
    }

    /** At each test's start and end, so a test sees only its own. */
    static void forget() {
        HANDLED.clear();
    }
}
