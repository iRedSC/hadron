package dev.iredsc.hadron.neoforge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffectInstance;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saves each effect's cause beside vanilla's fields: {@code hadron:causes}, one entry per effect in its hidden chain,
 * outermost first, an empty compound where nothing recorded the cause. Vanilla's reader ignores the key, so a world
 * opened without Hadron keeps its effects, and an effect saved before Hadron kept causes loads with none.
 */
public final class DataEffectCauses {
    static final String KEY = "hadron:causes";
    private static final Logger LOG = LoggerFactory.getLogger("Hadron");

    private DataEffectCauses() {}

    /** Adds the causes of {@code effect} and every effect it hides to {@code tag}, its own saved form. */
    public static void save(MobEffectInstance effect, CompoundTag tag) {
        ListTag causes = new ListTag();
        boolean any = false;
        for (MobEffectInstance e = effect; e != null; e = hidden(e)) {
            LastingCause cause = Lasting.cause(e);
            Tag saved = cause == null ? null : LastingCause.CODEC.encodeStart(NbtOps.INSTANCE, cause)
                    .resultOrPartial(error -> LOG.error("Couldn't save who applied {}: {}", effect, error)).orElse(null);
            causes.add(saved != null ? saved : new CompoundTag());
            any |= saved != null;
        }
        if (any) tag.put(KEY, causes);
    }

    /** Gives {@code effect}, just loaded from {@code tag}, and every effect it hides back their causes. */
    public static void load(MobEffectInstance effect, CompoundTag tag) {
        if (!tag.contains(KEY, Tag.TAG_LIST)) return;
        ListTag causes = tag.getList(KEY, Tag.TAG_COMPOUND);
        int i = 0;
        for (MobEffectInstance e = effect; e != null && i < causes.size(); e = hidden(e), i++) {
            CompoundTag saved = causes.getCompound(i);
            if (saved.isEmpty()) continue;
            LastingCause cause = LastingCause.CODEC.parse(NbtOps.INSTANCE, saved)
                    .resultOrPartial(error -> LOG.error("Couldn't read who applied {}, so it acts for nobody: {}",
                            effect, error)).orElse(null);
            ((Lasting.EffectState) e).hadron$setCause(cause);
        }
    }

    private static @Nullable MobEffectInstance hidden(MobEffectInstance effect) {
        return ((Lasting.EffectState) effect).hadron$hidden();
    }
}
