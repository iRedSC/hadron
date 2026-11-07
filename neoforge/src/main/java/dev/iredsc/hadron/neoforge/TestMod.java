package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.test.TestModPacket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Hadron's test mod: one network channel, {@link TestModPacket} ({@code hadron:test_ping}, client to server, play),
 * whose handler records what it received, so tests can send a mod packet and see whether the mod handled it. Only a
 * test server registers it ({@link TestRunner#register}); a normal server never has the channel.
 *
 * <p>Optional, as NeoForge calls it, so unmodded test players still join; modded ones negotiate it with the rest.
 */
final class TestMod {
    private static final List<TestModPacket.Handled> HANDLED = new CopyOnWriteArrayList<>();

    private TestMod() {}

    static void register(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").optional()
                .playToServer(TestModPacket.TYPE, TestModPacket.STREAM_CODEC,
                        (payload, context) -> HANDLED.add(new TestModPacket.Handled((ServerPlayer) context.player(), payload))));
    }

    static List<TestModPacket.Handled> handled() {
        return List.copyOf(HANDLED);
    }

    /** At each test's start and end, so a test sees only its own. */
    static void forget() {
        HANDLED.clear();
    }
}
