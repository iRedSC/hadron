package dev.iredsc.hadron.neoforge;

import org.jspecify.annotations.Nullable;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Remembered attribution, saved with each entity and block entity as a NeoForge data attachment, so a shell landing
 * after a restart still knows who fired it. Also holds who set a burning entity alight, and the items a dead player
 * keeps until they respawn.
 */
final class AttributionMemory implements Actors.Memory {
    static final Codec<Attribution> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.optionalFieldOf("responsible").forGetter(Attribution::responsible),
            UUIDUtil.CODEC.optionalFieldOf("controller").forGetter(Attribution::controller),
            UUIDUtil.CODEC.optionalFieldOf("authority").forGetter(Attribution::authority))
            .apply(i, (r, c, a) -> Attribution.none().withResponsible(r.orElse(null)).withController(c.orElse(null))
                    .withAuthority(a.orElse(null))));

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, HadronMod.MOD_ID);

    static final Supplier<AttachmentType<Attribution>> ATTRIBUTION = ATTACHMENTS.register("attribution",
            () -> AttachmentType.builder(Attribution::none).serialize(CODEC, a -> !a.isEmpty()).build());

    /**
     * Who set a burning entity alight, and where, while it burns ({@link Lasting#fireSet}). An entity saved before
     * Hadron kept it has none, and burns on with an unrecorded cause.
     */
    static final Supplier<AttachmentType<Optional<LastingCause>>> FIRE_CAUSE = ATTACHMENTS.register("fire_cause",
            () -> AttachmentType.<Optional<LastingCause>>builder(Optional::empty)
                    .serialize(LastingCause.CODEC.xmap(Optional::of, Optional::orElseThrow), Optional::isPresent)
                    .build());

    /**
     * Who applied each effect on an entity and where, one per effect in each effect's chain ({@link DataEffectCauses}).
     * An entity saved before Hadron kept them has none, and its effects act with an unrecorded cause.
     */
    static final Supplier<AttachmentType<DataEffectCauses>> EFFECT_CAUSES = ATTACHMENTS.register("effect_causes",
            () -> AttachmentType.builder(DataEffectCauses::new)
                    .serialize(DataEffectCauses.CODEC, causes -> !causes.isEmpty()).build());

    /**
     * The id Hadron gives a block entity the first time it causes an effect, saved with it, so a cause can tell that
     * very machine from another later put in its place.
     */
    static final Supplier<AttachmentType<UUID>> MACHINE_ID = ATTACHMENTS.register("machine_id",
            () -> AttachmentType.builder(UUID::randomUUID).serialize(UUIDUtil.CODEC).build());

    /** The machine's id, given to it now (and saved with it) if it has none yet. */
    static UUID machineId(BlockEntity blockEntity) {
        if (blockEntity.hasData(MACHINE_ID)) return blockEntity.getData(MACHINE_ID);
        UUID id = blockEntity.getData(MACHINE_ID);
        blockEntity.setChanged();
        return id;
    }

    /** The machine's id, or null if it was never given one. */
    static @Nullable UUID machineIdIfAny(BlockEntity blockEntity) {
        return blockEntity.hasData(MACHINE_ID) ? blockEntity.getData(MACHINE_ID) : null;
    }

    /** Items a dead player keeps; copied to the respawned player and handed back then. */
    static final Supplier<AttachmentType<List<ItemStack>>> KEPT_ITEMS = ATTACHMENTS.register("kept_items",
            () -> AttachmentType.<List<ItemStack>>builder(() -> new ArrayList<>())
                    .serialize(ItemStack.CODEC.listOf(), list -> !list.isEmpty()).copyOnDeath().build());

    static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }

    @Override
    public void remember(Entity entity, Attribution attribution) {
        if (attribution.isEmpty()) {
            if (entity.hasData(ATTRIBUTION)) entity.removeData(ATTRIBUTION);
        } else {
            entity.setData(ATTRIBUTION, attribution);
        }
        ((EntityState) entity).hadron$setActor(null);
    }

    @Override
    public void remember(BlockEntity blockEntity, Attribution attribution) {
        if (attribution.isEmpty()) {
            if (!blockEntity.hasData(ATTRIBUTION)) return;
            blockEntity.removeData(ATTRIBUTION);
        } else {
            blockEntity.setData(ATTRIBUTION, attribution);
        }
        blockEntity.setChanged();
    }

    @Override
    public Attribution remembered(Entity entity) {
        return entity.hasData(ATTRIBUTION) ? entity.getData(ATTRIBUTION) : Attribution.none();
    }

    @Override
    public Attribution remembered(BlockEntity blockEntity) {
        return blockEntity.hasData(ATTRIBUTION) ? blockEntity.getData(ATTRIBUTION) : Attribution.none();
    }
}
