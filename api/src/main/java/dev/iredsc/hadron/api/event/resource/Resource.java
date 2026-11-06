package dev.iredsc.hadron.api.event.resource;

import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;

/**
 * Something measured in amounts that isn't an item stack: energy, a fluid in a tank, a chemical, heat, mana, money.
 * Two resources are the same when their ids are. Amounts are whole numbers of {@link #unit()}, so pick the smallest
 * unit the mod counts in (millibuckets, not buckets; cents, not dollars).
 *
 * <pre>{@code
 * Resource.ENERGY                                         // hadron:energy, in FE
 * Resource.fluid(Fluids.WATER)                            // hadron:fluid/minecraft/water, in mB
 * Resource.currency("coins")                              // hadron:currency/coins, held by accounts
 * new Resource(ResourceLocation.parse("botania:mana"), "mana")   // a mod's own
 * }</pre>
 *
 * @param id what it is; a mod's own resources use the mod's namespace
 * @param unit what one counts, for showing amounts to people, such as {@code FE} or {@code mB}
 */
public record Resource(ResourceLocation id, String unit) {
    /** Forge Energy and its equivalents (RF, FE), in FE. */
    public static final Resource ENERGY = new Resource(hadron("energy"), "FE");

    private static final String FLUID = "fluid/";
    private static final String CURRENCY = "currency/";

    public Resource {
        Objects.requireNonNull(id, "id");
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("Resource " + id + " needs a unit people can read, such as \"FE\", "
                    + "\"mB\" or \"mana\"");
        }
    }

    /** A fluid in a tank, in millibuckets: {@code hadron:fluid/<namespace>/<path>}. */
    public static Resource fluid(Fluid fluid) {
        return fluid(BuiltInRegistries.FLUID.getKey(Objects.requireNonNull(fluid, "fluid")));
    }

    /** A fluid by its registry id, in millibuckets: {@code hadron:fluid/<namespace>/<path>}. */
    public static Resource fluid(ResourceLocation fluidId) {
        return new Resource(hadron(FLUID + fluidId.getNamespace() + "/" + fluidId.getPath()), "mB");
    }

    /**
     * Money, held by {@link ResourceHolder.Account accounts}: {@code hadron:currency/<name>}. Economy plugins provide
     * the accounts, so a payment by any mod or plugin is one {@link ResourceTransferEvent}. Count it in its smallest
     * unit, such as cents.
     *
     * @param name lowercase letters, digits, {@code _}, {@code -} or {@code .}, such as {@code coins}
     */
    public static Resource currency(String name) {
        if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || !ResourceLocation.isValidPath(name)) {
            throw new IllegalArgumentException("Currency name \"" + name + "\" must be lowercase letters, digits, "
                    + "'_', '-' or '.', such as \"coins\"");
        }
        return new Resource(hadron(CURRENCY + name), name);
    }

    /** Whether this is a fluid made by {@link #fluid}. */
    public boolean isFluid() {
        return id.getNamespace().equals("hadron") && id.getPath().startsWith(FLUID);
    }

    /** Whether this is money made by {@link #currency}; such amounts are held only by accounts. */
    public boolean isCurrency() {
        return id.getNamespace().equals("hadron") && id.getPath().startsWith(CURRENCY);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Resource r && r.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    private static ResourceLocation hadron(String path) {
        return ResourceLocation.fromNamespaceAndPath("hadron", path);
    }
}
