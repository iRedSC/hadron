package dev.iredsc.hadron.test;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/**
 * What an action did to players and listeners beyond blocks and items: statistics counted, advancement triggers fired,
 * game events emitted (the vibrations sculk sensors hear), experience and hunger. A refused action must leave none of
 * them, and they are the ones a test forgets to look at: a refused lead tie that still counts the lead as used, pays an
 * advancement's reward, or sets off a sculk sensor looks fine to every check of the world.
 *
 * <pre>{@code
 * SideEffects effects = t.watchSideEffects();     // after the setup, right before the action
 * alice.interact(cow);                            // refused by a listener
 * t.tick();
 * effects.assertNone("a refused leash");
 * }</pre>
 *
 * <p><b>What it records</b>, from {@link TestContext#watchSideEffects} until the test ends: for every player of the test,
 * each statistic that changed, each advancement trigger fired (whether or not an advancement listens for it), and
 * each change to experience points or levels or to food exhaustion; and every game event in the test's world, or
 * caused by one of its players anywhere. A cancelled NeoForge event (a stat award, a game event, an experience change)
 * never happened, so it isn't recorded.
 *
 * <p><b>What it leaves out</b>, because nothing an action does causes it: the statistics every player's tick counts
 * ({@code play_time}, {@code total_world_time}, {@code time_since_death}, {@code time_since_rest}); the triggers every
 * player's tick fires ({@code tick}, {@code location}, {@code enter_block}, {@code levitation}) and what they
 * cause (standing in water completes the boat recipes' advancements); and the motion of
 * entities that aren't test players (their {@code step}, {@code swim}, {@code flap}, {@code hit_ground},
 * {@code splash} and {@code elytra_glide}), so a cow wandering by isn't one. A test player's own motion is recorded:
 * move them before watching.
 *
 * <p>Each call to {@link TestContext#watchSideEffects} starts a watch of its own; {@link #mark} starts the next step of
 * one. Blocks, inventories and entities aren't its business: assert those directly.
 */
public interface SideEffects {
    /** Everything recorded since the watch started or was last {@link #mark marked}, oldest first. */
    List<Effect> seen();

    /** {@link #seen()}, only the effects of one kind: {@code seen(SideEffects.CriterionTriggered.class)}. */
    default <T extends Effect> List<T> seen(Class<T> kind) {
        return seen().stream().filter(kind::isInstance).map(kind::cast).toList();
    }

    /** Forgets what was recorded so far: the start of the next step. */
    void mark();

    /**
     * That nothing was recorded since the watch started or was last {@link #mark marked}: for a step that was refused.
     * The failure lists each effect, what fired it and where, and the code that called it, which is where the refused
     * action went on regardless.
     *
     * @param step what was done, for the failure: {@code "a refused lead tie"}.
     */
    default void assertNone(String step) {
        List<Effect> seen = seen();
        if (seen.isEmpty()) return;
        throw new AssertionError(step + " should leave no side effect, but left " + seen.size() + ":\n"
                + seen.stream().map(e -> "  - " + e).collect(Collectors.joining("\n"))
                + "\nA refused action ran on past its refusal. Make the code named after \"from\" check the cancel "
                + "before it counts, triggers or emits, or move the event before it.");
    }

    /** One thing an action did beyond blocks and items. {@code from} is the code that did it, nearest first. */
    sealed interface Effect {
        /** The first frames outside Minecraft's and the harness's own bookkeeping, nearest first. */
        List<StackTraceElement> from();
    }

    /** A statistic of {@code player}'s went from {@code before} to {@code after}. */
    record StatChanged(TestPlayer player, Stat<?> stat, int before, int after, List<StackTraceElement> from)
            implements Effect {
        /** Such as {@code alice's stat minecraft:used minecraft:lead went 0 -> 1, from LeadItem.useOn(LeadItem.java:46)}. */
        @Override
        public String toString() {
            return player.name() + "'s stat " + name(stat) + " went " + before + " -> " + after + frames(from);
        }

        private static <T> String name(Stat<T> stat) {
            ResourceLocation value = stat.getType().getRegistry().getKey(stat.getValue());
            return BuiltInRegistries.STAT_TYPE.getKey(stat.getType()) + " " + value;
        }
    }

    /** An advancement trigger fired for {@code player}, counting toward every advancement listening for it. */
    record CriterionTriggered(TestPlayer player, CriterionTrigger<?> trigger, List<StackTraceElement> from)
            implements Effect {
        /** Such as {@code trigger minecraft:player_interacted_with_entity fired for alice, from ...}. */
        @Override
        public String toString() {
            return "trigger " + BuiltInRegistries.TRIGGER_TYPES.getKey(trigger) + " fired for " + player.name()
                    + frames(from);
        }
    }

    /** A game event was emitted at {@code pos}, which sculk sensors, wardens and allays within range hear. */
    record GameEventEmitted(Holder<GameEvent> event, Vec3 pos, Optional<Entity> source, List<StackTraceElement> from)
            implements Effect {
        /** Such as {@code game event minecraft:block_attach at 2 64 0 by alice, from ...}. */
        @Override
        public String toString() {
            return "game event " + event.unwrapKey().map(k -> k.location().toString()).orElse(event.toString())
                    + " at " + (int) Math.floor(pos.x) + " " + (int) Math.floor(pos.y) + " " + (int) Math.floor(pos.z)
                    + source.map(e -> " by " + e.getName().getString()).orElse(", by nobody") + frames(from);
        }
    }

    /** {@code player}'s experience points changed by {@code change}: gained if positive, spent if negative. */
    record ExperienceChanged(TestPlayer player, int change, List<StackTraceElement> from) implements Effect {
        @Override
        public String toString() {
            return player.name() + "'s experience changed by " + change + " points" + frames(from);
        }
    }

    /** {@code player}'s experience level changed by {@code change}: an enchantment's or an anvil's cost if negative. */
    record LevelsChanged(TestPlayer player, int change, List<StackTraceElement> from) implements Effect {
        @Override
        public String toString() {
            return player.name() + "'s experience level changed by " + change + frames(from);
        }
    }

    /** {@code player} was made hungrier by {@code exhaustion}, as attacking, mining or sprinting does. */
    record FoodExhausted(TestPlayer player, float exhaustion, List<StackTraceElement> from) implements Effect {
        @Override
        public String toString() {
            return player.name() + " was exhausted by " + exhaustion + frames(from);
        }
    }

    private static String frames(List<StackTraceElement> from) {
        if (from.isEmpty()) return "";
        return ", from " + from.stream().map(SideEffects::frame).collect(Collectors.joining(" < "));
    }

    private static String frame(StackTraceElement e) {
        String type = e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1);
        return type + "." + e.getMethodName() + "(" + (e.getFileName() == null ? "?" : e.getFileName())
                + (e.getLineNumber() > 0 ? ":" + e.getLineNumber() : "") + ")";
    }
}
