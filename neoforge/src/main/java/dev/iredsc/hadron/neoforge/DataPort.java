package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.data.PersistentData;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The NeoForge side of persistent data on items, entities, block entities, blocks, chunks and levels. Mixins in
 * {@code mixin.data}. Items need nothing here: their data is vanilla custom data, written by the API itself.
 *
 * <p>Everything stored is a compound of tags (never a plugin's object), so a reloaded plugin reads its data back.
 */
final class DataPort {
    private DataPort() {}

    /** Compounds saved only when they hold something. */
    private static final IAttachmentSerializer<CompoundTag, CompoundTag> COMPOUND = new IAttachmentSerializer<>() {
        @Override
        public CompoundTag read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            return tag;
        }

        @Override
        public CompoundTag write(CompoundTag data, HolderLookup.Provider provider) {
            return data.isEmpty() ? null : data.copy();
        }
    };

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, HadronMod.MOD_ID);

    /** {@code PersistentData.of(entity | blockEntity | chunk | level)}. A player keeps theirs across death. */
    static final Supplier<AttachmentType<CompoundTag>> DATA = ATTACHMENTS.register("data",
            () -> AttachmentType.builder(() -> new CompoundTag()).serialize(COMPOUND).copyOnDeath().build());

    /** {@code PersistentData.of(level, pos)}, per chunk. Mirrored in the chunk's {@link DataChunk} field. */
    static final Supplier<AttachmentType<DataBlocks>> BLOCKS = ATTACHMENTS.register("block_data",
            () -> AttachmentType.builder(DataBlocks::new).serialize(DataBlocks.SERIALIZER).build());

    /** A pushed block's position data, carried by its moving piston block entity until it lands. */
    static final Supplier<AttachmentType<CompoundTag>> MOVING = ATTACHMENTS.register("moving_block_data",
            () -> AttachmentType.builder(() -> new CompoundTag()).serialize(COMPOUND).build());

    static void register() {
        ATTACHMENTS.register(ModLoadingContext.get().getActiveContainer().getEventBus());
        PersistentData.Port.installStore(new DataStore());
    }
}
