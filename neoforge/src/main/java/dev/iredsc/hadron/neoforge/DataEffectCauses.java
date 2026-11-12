package dev.iredsc.hadron.neoforge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.neoforge.mixin.entity.MobEffectInstanceAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * Who applied each effect on an entity, kept on the entity itself ({@link AttributionMemory#EFFECT_CAUSES}) and saved
 * with it: for each effect, one {@link Link} per instance in its chain, the active one first, then each hidden one
 * that resumes when the one above it runs out. Held by the entity whose effect it is, so an instance a mod passes from
 * one entity to another can't carry one entity's credit to the other, and gone with the effect.
 *
 * <p>The chain changes in exactly three places, each of which knows the entity: an application ({@link #applying},
 * {@code addEffect} and {@code forceAddEffect}), a hidden effect resuming ({@link #resumed}, in the instance's tick)
 * and the effect ending ({@link #removed}). An application is read off the chain as vanilla left it: nothing ticks
 * while it runs, so each instance after it either is one that was there before, with the same amplifier and duration,
 * and keeps its cause, or is the new application's, and takes the new cause. No rule of vanilla's is copied here, so
 * none can drift from it. A chain changed any other way (a mod calling {@code update} or editing it directly) no
 * longer matches its links' amplifiers, and is read as unrecorded rather than credited to anyone.
 */
public final class DataEffectCauses {
    /** One instance in an effect's chain: its amplifier, to check the chain is still the one recorded, and its cause. */
    record Link(int amplifier, Optional<LastingCause> cause) {
        static final Codec<Link> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("amplifier").forGetter(Link::amplifier),
                LastingCause.CODEC.optionalFieldOf("cause").forGetter(Link::cause))
                .apply(i, Link::new));
    }

    /** Saved as a map from each effect's id to its chain's links, the active instance's first. */
    static final Codec<DataEffectCauses> CODEC = Codec.unboundedMap(MobEffect.CODEC, Link.CODEC.listOf())
            .xmap(DataEffectCauses::new, causes -> causes.chains);

    private final Map<Holder<MobEffect>, List<Link>> chains;

    DataEffectCauses() {
        this(Map.of());
    }

    private DataEffectCauses(Map<Holder<MobEffect>, List<Link>> loaded) {
        this.chains = new HashMap<>();
        loaded.forEach((effect, links) -> chains.put(effect, new ArrayList<>(links)));
    }

    boolean isEmpty() {
        return chains.isEmpty();
    }

    private static @Nullable MobEffectInstance hidden(MobEffectInstance effect) {
        return ((MobEffectInstanceAccessor) effect).hadron$hiddenEffect();
    }

    private static @Nullable List<Link> links(LivingEntity entity, Holder<MobEffect> effect) {
        return entity.hasData(AttributionMemory.EFFECT_CAUSES)
                ? entity.getData(AttributionMemory.EFFECT_CAUSES).chains.get(effect) : null;
    }

    /** {@code links} if they still describe {@code active}'s chain, instance for instance; null otherwise. */
    private static @Nullable List<Link> matching(@Nullable List<Link> links, @Nullable MobEffectInstance active) {
        if (links == null || active == null) return null;
        int i = 0;
        for (MobEffectInstance e = active; e != null; e = hidden(e), i++) {
            if (i >= links.size() || links.get(i).amplifier() != e.getAmplifier()) return null;
        }
        return i == links.size() ? links : null;
    }

    /**
     * Who applied {@code effect}, the instance active on {@code entity} now; null when nothing recorded it or its
     * chain is no longer the one recorded. Allocates nothing.
     */
    public static @Nullable LastingCause active(LivingEntity entity, MobEffectInstance effect) {
        if (entity.getEffect(effect.getEffect()) != effect) return null;
        List<Link> links = matching(links(entity, effect.getEffect()), effect);
        return links == null ? null : links.getFirst().cause().orElse(null);
    }

    /**
     * Runs {@code apply}, vanilla applying {@code incoming} to {@code subject}, and records who applied it: {@code
     * source} when there is one (which may be the subject itself, a player's own splash potion), otherwise whoever is
     * on the stack ({@link Actor.Nature} when nothing is), with where the subject stands. The new cause goes only to
     * the instances vanilla made of this application; every other instance keeps its own, so a refused, weaker and
     * shorter, or no-change application moves nothing.
     */
    public static <T> T applying(LivingEntity subject, MobEffectInstance incoming, @Nullable Entity source,
            Supplier<T> apply) {
        Holder<MobEffect> effect = incoming.getEffect();
        MobEffectInstance before = subject.getEffect(effect);
        List<Link> kept = matching(links(subject, effect), before);
        List<int[]> was = new ArrayList<>();
        for (MobEffectInstance e = before; e != null; e = hidden(e)) was.add(new int[] {e.getAmplifier(), e.getDuration()});
        int amplifier = incoming.getAmplifier();
        int duration = incoming.getDuration();
        Actor actor = source != null ? EntityActors.of(source) : EntityActors.current();
        LastingCause cause = LastingCause.of(actor, subject);
        T result = apply.get();
        MobEffectInstance after = subject.getEffect(effect);
        if (after == null) {
            removed(subject, effect);
            return result;
        }
        List<Link> links = new ArrayList<>();
        for (MobEffectInstance e = after; e != null; e = hidden(e)) {
            links.add(new Link(e.getAmplifier(), causeOf(e, was, kept, amplifier, duration, cause)));
        }
        subject.getData(AttributionMemory.EFFECT_CAUSES).chains.put(effect, links);
        return result;
    }

    /** An instance's cause after an application: the one it had if it was there before, else the new one if it is it. */
    private static Optional<LastingCause> causeOf(MobEffectInstance e, List<int[]> was, @Nullable List<Link> kept,
            int amplifier, int duration, LastingCause cause) {
        for (int i = 0; i < was.size(); i++) {
            if (was.get(i)[0] == e.getAmplifier() && was.get(i)[1] == e.getDuration()) {
                return kept != null ? kept.get(i).cause() : Optional.empty();
            }
        }
        return e.getAmplifier() == amplifier && e.getDuration() == duration ? Optional.of(cause) : Optional.empty();
    }

    /** The active instance ran out and the one it hid took its place: that one's cause is now the active one's. */
    public static void resumed(LivingEntity entity, Holder<MobEffect> effect) {
        List<Link> links = links(entity, effect);
        if (links != null && links.size() > 1) links.removeFirst();
    }

    /** The effect ended (ran out, cured by milk or a totem, cleared): its causes go with it. */
    public static void removed(LivingEntity entity, Holder<MobEffect> effect) {
        if (!entity.hasData(AttributionMemory.EFFECT_CAUSES)) return;
        DataEffectCauses causes = entity.getData(AttributionMemory.EFFECT_CAUSES);
        causes.chains.remove(effect);
        if (causes.isEmpty()) entity.removeData(AttributionMemory.EFFECT_CAUSES);
    }
}
