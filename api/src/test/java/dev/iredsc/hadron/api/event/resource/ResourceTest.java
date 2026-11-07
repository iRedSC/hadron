package dev.iredsc.hadron.api.event.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.test.WithMinecraft;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResourceTest {
    @Test
    void energyIsHadronsInFe() {
        // COVERS: Resource#ENERGY
        assertEquals("hadron:energy", Resource.ENERGY.id().toString());
        assertEquals("FE", Resource.ENERGY.unit());
        assertFalse(Resource.ENERGY.isFluid());
        assertFalse(Resource.ENERGY.isCurrency());
    }

    @Test
    void aFluidIdNestsItsNamespaceAndPath() {
        // COVERS: Resource#fluid
        // COVERS: Resource#isFluid
        Resource lava = Resource.fluid(ResourceLocation.parse("create:chocolate"));
        assertEquals("hadron:fluid/create/chocolate", lava.id().toString());
        assertEquals("mB", lava.unit());
        assertTrue(lava.isFluid());
        assertFalse(lava.isCurrency());
    }

    @Test
    void aCurrencyIsHeldByAccounts() {
        // COVERS: Resource#currency
        // COVERS: Resource#isCurrency
        Resource coins = Resource.currency("coins");
        assertEquals("hadron:currency/coins", coins.id().toString());
        assertTrue(coins.isCurrency());
        assertFalse(coins.isFluid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Coins", "a/b", "a b", "a:b"})
    void anInvalidCurrencyNameIsRefusedWithAnExample(String name) {
        // COVERS: Resource#currency
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Resource.currency(name));
        assertTrue(e.getMessage().contains("\"coins\""), e.getMessage());
    }

    @Test
    void aModsOwnPathUnderHadronsPrefixIsNotMistakenForOne() {
        // COVERS: Resource#isFluid
        // COVERS: Resource#isCurrency
        Resource mods = new Resource(ResourceLocation.parse("mymod:fluid/water"), "mB");
        assertFalse(mods.isFluid());
        Resource modMoney = new Resource(ResourceLocation.parse("mymod:currency/coins"), "coins");
        assertFalse(modMoney.isCurrency());
    }

    @Test
    void resourcesAreTheSameWhenTheirIdsAre() {
        // COVERS: Resource#equals
        // COVERS: Resource#hashCode
        Resource a = new Resource(ResourceLocation.parse("botania:mana"), "mana");
        Resource b = new Resource(ResourceLocation.parse("botania:mana"), "points");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, Resource.ENERGY);
    }

    @Test
    void aResourceNeedsAnIdAndAReadableUnit() {
        // COVERS: Resource#Resource
        assertThrows(NullPointerException.class, () -> new Resource(null, "x"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Resource(ResourceLocation.parse("a:b"), " "));
        assertTrue(e.getMessage().contains("\"FE\""), e.getMessage());
    }

    @Test
    void aPlayersAccountIsKeyedByUuid() {
        // COVERS: ResourceHolder.Account#player
        UUID id = UUID.randomUUID();
        assertEquals(id.toString(), ResourceHolder.Account.player(id).id());
        assertThrows(IllegalArgumentException.class, () -> new ResourceHolder.Account(" "));
        assertThrows(NullPointerException.class, () -> ResourceHolder.Account.player(null));
    }

    /** Only these need the fluid registry, so only they boot Minecraft. */
    @Nested
    @WithMinecraft
    class FromTheRegistry {
        @Test
        void aVanillaFluidIsNamedByItsRegistryId() {
            // COVERS: Resource#fluid
            assertEquals("hadron:fluid/minecraft/water", Resource.fluid(Fluids.WATER).id().toString());
            assertEquals(Resource.fluid(ResourceLocation.parse("minecraft:lava")), Resource.fluid(Fluids.LAVA));
        }
    }
}
