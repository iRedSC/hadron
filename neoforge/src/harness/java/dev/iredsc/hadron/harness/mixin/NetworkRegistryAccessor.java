package dev.iredsc.hadron.harness.mixin;

import java.util.Map;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Every channel the server's mods registered, so a modded test client can claim them all in NeoForge's network
 * negotiation, as a real client with the same mods does from the same table ({@code NetworkRegistry.onNetworkQuery}).
 */
@Mixin(NetworkRegistry.class)
public interface NetworkRegistryAccessor {
    @Accessor("PAYLOAD_REGISTRATIONS")
    static Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>> hadron$payloadRegistrations() {
        throw new AssertionError("replaced by Mixin");
    }
}
