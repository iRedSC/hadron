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
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * Who applied each effect on an entity, kept on the entity itself ({@link AttributionMemory#EFFECT_CAUSES}) and saved
 * with it: for each effect, one {@link Link} per instance in its chain, the active one first, then each hidden one
 * that resumes when the one above it runs out.
 *
 * <p>A link names its instance by identity, the very {@code MobEffectInstance} object, and keeps a snapshot of what
 * that instance said when the link last saw it change by a known path. Credit is given only where it can be proven:
 *
 * <ul>
 *   <li>An application ({@link #applying}) works on its own deep copy of the incoming instance, chain included, so it
 *       never shares state with whoever passed it in. Afterwards, an instance that was there before and still says
 *       what it said keeps its cause; one that was there and now says what the application said was replaced in place
 *       and takes the new cause; one from the application's own chain takes the new cause; and a copy vanilla made
 *       during the call takes the cause of the one instance it can only be a copy of. Anything else is unrecorded.
 *   <li>A tick ({@link #beforeTick}, {@link #afterTick}) may change an instance only as a tick does: each duration
 *       down by one, and a hidden effect resuming into the active one.
 *   <li>Every other read ({@link #active}) checks the chain still is what its links say.
 * </ul>
 *
 * Any change outside those paths (a mod calling {@code update} or editing an instance or the chain directly) that
 * alters an instance's amplifier, duration or flags, or which objects make the chain, is found at the effect's next
 * tick, act or application, and makes that instance unrecorded: it then acts for {@link Actor.Unknown}, never for the
 * cause it had. A change that alters none of those leaves nothing to tell, and credits nobody new.
 */
public final class DataEffectCauses {
    /** One instance in an effect's chain: which object it is, what it last said, and who applied it (if proven). */
    static final class Link {
        /** The instance this describes; null after loading until it is matched to the instance loaded with it. */
        @Nullable MobEffectInstance instance;
        int amplifier;
        int duration;
        boolean ambient;
        boolean visible;
        boolean showIcon;
        Optional<LastingCause> cause;

        Link(MobEffectInstance instance, Optional<LastingCause> cause) {
            this.cause = cause;
            see(instance);
        }

        private Link(int amplifier, int duration, Optional<LastingCause> cause) {
            this.amplifier = amplifier;
            this.duration = duration;
            this.cause = cause;
        }

        static final Codec<Link> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("amplifier").forGetter(l -> l.amplifier),
                Codec.INT.fieldOf("duration").forGetter(l -> l.duration),
                LastingCause.CODEC.optionalFieldOf("cause").forGetter(l -> l.cause))
                .apply(i, Link::new));

        /** Takes {@code e} as its instance, and what it says now as known. */
        void see(MobEffectInstance e) {
            instance = e;
            amplifier = e.getAmplifier();
            duration = e.getDuration();
            ambient = e.isAmbient();
            visible = e.isVisible();
            showIcon = e.showIcon();
        }

        /** Something changed {@code e} by no known path: from now on nobody known applied it. */
        void lose(MobEffectInstance e) {
            cause = Optional.empty();
            see(e);
        }

        boolean says(MobEffectInstance e, int expectedDuration) {
            return e.getAmplifier() == amplifier && e.getDuration() == expectedDuration && e.isAmbient() == ambient
                    && e.isVisible() == visible && e.showIcon() == showIcon;
        }

        boolean says(MobEffectInstance e) {
            return says(e, duration);
        }

        /** What a tick leaves of this duration: one less, unless infinite or run out. */
        int ticked() {
            return duration == MobEffectInstance.INFINITE_DURATION || duration == 0 ? duration : duration - 1;
        }
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

    /**
     * The links for the effect active on {@code entity}, made to match its chain: an instance that is the one its link
     * names and says what the link knows keeps its cause; a link loaded with the entity takes the instance loaded with
     * it, if that says what was saved; anything else is lost. Null when nothing was recorded. Allocates nothing unless
     * the chain was changed by no known path.
     */
    private static @Nullable List<Link> checked(LivingEntity entity, Holder<MobEffect> effect) {
        List<Link> links = links(entity, effect);
        if (links == null) return null;
        MobEffectInstance top = entity.getEffect(effect);
        if (top == null) {
            removed(entity, effect);
            return null;
        }
        int i = 0;
        for (MobEffectInstance e = top; e != null; e = hidden(e), i++) {
            if (i == links.size()) {
                links.add(new Link(e, Optional.empty()));
                continue;
            }
            Link link = links.get(i);
            if (link.instance == null ? link.amplifier == e.getAmplifier() && link.duration == e.getDuration()
                    : link.instance == e && link.says(e)) {
                link.see(e);
            } else {
                link.lose(e);
            }
        }
        while (links.size() > i) links.removeLast();
        return links;
    }

    /**
     * Who applied {@code effect}, the instance active on {@code entity} now; null when that can't be proven (nothing
     * recorded it, or it was changed by no known path).
     */
    public static @Nullable LastingCause active(LivingEntity entity, MobEffectInstance effect) {
        if (entity.getEffect(effect.getEffect()) != effect) return null;
        List<Link> links = checked(entity, effect.getEffect());
        return links == null ? null : links.getFirst().cause.orElse(null);
    }

    /** Before the active instance ticks: anything changed since by no known path is lost now, not absorbed. */
    public static void beforeTick(LivingEntity entity, MobEffectInstance effect) {
        if (entity.getEffect(effect.getEffect()) == effect) checked(entity, effect.getEffect());
    }

    /**
     * After the active instance ticked: each instance may have changed only as a tick changes it (each duration down
     * by one, none when the active one had run out), and the hidden one may have resumed into the active one, taking
     * its place with its cause. Anything else is lost.
     */
    public static void afterTick(LivingEntity entity, MobEffectInstance top) {
        List<Link> links = links(entity, top.getEffect());
        if (links == null || entity.getEffect(top.getEffect()) != top || links.getFirst().instance != top) return;
        boolean ticked = links.getFirst().duration != 0;
        int i = 0;
        if (links.size() > 1 && hidden(top) != links.get(1).instance) {
            // Resumed: the hidden instance's details were copied into the active one, and it left the chain.
            links.removeFirst();
            Link resumed = links.getFirst();
            if (resumed.says(top, ticked ? resumed.ticked() : resumed.duration)) {
                resumed.see(top);
            } else {
                resumed.lose(top);
            }
            i = 1;
        }
        MobEffectInstance e = i == 0 ? top : hidden(top);
        for (; e != null; e = hidden(e), i++) {
            if (i == links.size()) {
                links.add(new Link(e, Optional.empty()));
                continue;
            }
            Link link = links.get(i);
            if (link.instance == e && link.says(e, ticked ? link.ticked() : link.duration)) {
                link.see(e);
            } else {
                link.lose(e);
            }
        }
        while (links.size() > i) links.removeLast();
    }

    /**
     * Runs {@code apply}, vanilla applying {@code incoming} to {@code subject}, on a deep copy of it (chain included),
     * and records who applied it: {@code source} when there is one (which may be the subject itself, a player's own
     * splash potion), otherwise whoever is on the stack ({@link Actor.Nature} when nothing is), with where the subject
     * stands. Credit goes only where it can be proven; see the class.
     */
    public static <T> T applying(LivingEntity subject, MobEffectInstance incoming, @Nullable Entity source,
            Function<MobEffectInstance, T> apply) {
        Holder<MobEffect> effect = incoming.getEffect();
        MobEffectInstance copy = deepCopy(incoming);
        List<MobEffectInstance> fromIncoming = new ArrayList<>();
        for (MobEffectInstance e = copy; e != null; e = hidden(e)) fromIncoming.add(e);
        List<Link> known = checked(subject, effect);
        List<Link> was = new ArrayList<>();
        int i = 0;
        for (MobEffectInstance e = subject.getEffect(effect); e != null; e = hidden(e), i++) {
            was.add(known != null ? known.get(i) : new Link(e, Optional.empty()));
        }
        Actor actor = source != null ? EntityActors.of(source) : EntityActors.current();
        Optional<LastingCause> cause = Optional.of(LastingCause.of(actor, subject));
        T result = apply.apply(copy);
        MobEffectInstance after = subject.getEffect(effect);
        if (after == null) {
            removed(subject, effect);
            return result;
        }
        List<Link> links = new ArrayList<>();
        for (MobEffectInstance e = after; e != null; e = hidden(e)) {
            links.add(new Link(e, causeAfter(e, was, fromIncoming, copy, cause)));
        }
        subject.getData(AttributionMemory.EFFECT_CAUSES).chains.put(effect, links);
        return result;
    }

    /** An instance's cause after an application, by the rules in the class Javadoc; empty where nothing is proven. */
    private static Optional<LastingCause> causeAfter(MobEffectInstance e, List<Link> was,
            List<MobEffectInstance> fromIncoming, MobEffectInstance copy, Optional<LastingCause> cause) {
        for (Link before : was) {
            if (before.instance != e) continue;
            if (e.getAmplifier() == before.amplifier && e.getDuration() == before.duration) return before.cause;
            return e.getAmplifier() == copy.getAmplifier() && e.getDuration() == copy.getDuration()
                    ? cause : Optional.empty();
        }
        for (MobEffectInstance mine : fromIncoming) {
            if (mine == e) return cause;
        }
        // A copy vanilla made during the call: of an instance it replaced (as it was) or of the incoming one. Vanilla
        // copies only where the two differ; where they can't be told apart, nobody is credited.
        Link of = null;
        int matches = 0;
        for (Link before : was) {
            if (before.says(e)) {
                of = before;
                matches++;
            }
        }
        boolean incoming = e.getAmplifier() == copy.getAmplifier() && e.getDuration() == copy.getDuration()
                && e.isAmbient() == copy.isAmbient() && e.isVisible() == copy.isVisible()
                && e.showIcon() == copy.showIcon();
        if (matches == 1 && !incoming && of != null) return of.cause;
        if (matches == 0 && incoming) return cause;
        return Optional.empty();
    }

    /** {@code effect} and every instance it hides, copied, so applying it shares nothing with whoever passed it in. */
    private static MobEffectInstance deepCopy(MobEffectInstance effect) {
        MobEffectInstance copy = new MobEffectInstance(effect);
        MobEffectInstance hidden = hidden(effect);
        ((MobEffectInstanceAccessor) copy).hadron$setHiddenEffect(hidden == null ? null : deepCopy(hidden));
        return copy;
    }

    /** The effect ended (ran out, cured by milk or a totem, cleared): its causes go with it. */
    public static void removed(LivingEntity entity, Holder<MobEffect> effect) {
        if (!entity.hasData(AttributionMemory.EFFECT_CAUSES)) return;
        DataEffectCauses causes = entity.getData(AttributionMemory.EFFECT_CAUSES);
        causes.chains.remove(effect);
        if (causes.isEmpty()) entity.removeData(AttributionMemory.EFFECT_CAUSES);
    }
}
