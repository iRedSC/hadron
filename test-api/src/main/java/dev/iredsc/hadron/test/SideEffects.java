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
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/**
 * What an action did to players and listeners beyond blocks and items: statistics counted, advancement triggers fired,
 * game events emitted (the vibrations sculk sensors hear), sounds played, experience and hunger. A refused action must
 * leave none of them, and they are the ones a test forgets to look at: a refused lead tie that still counts the lead
 * as used, pays an advancement's reward, or sets off a sculk sensor, or a refused lever that still clicks, looks fine
 * to every check of the world.
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
 * each change to experience points or levels or to food exhaustion; every game event in the test's world, or
 * caused by one of its players anywhere; and every sound the server sends, from the test's world or for one of its
 * players anywhere: a sound ({@link SoundPlayed}), one of vanilla's numbered events that plays one
 * ({@link LevelEventPlayed}), or a sound sent to one player alone. A sound is recorded once, when the server sends it,
 * whether or not anyone is near enough to hear it, and also when the player who caused it is left out of the
 * broadcast because their own client plays it. A cancelled NeoForge event (a stat award, a game event, a sound, an
 * experience change) never happened, so it isn't recorded.
 *
 * <p><b>What it leaves out</b>, because nothing an action does causes it: the statistics every player's tick counts
 * ({@code play_time}, {@code total_world_time}, {@code time_since_death}, {@code time_since_rest}); the triggers every
 * player's tick fires ({@code tick}, {@code location}, {@code enter_block}, {@code levitation}) and what they
 * cause (standing in water completes the boat recipes' advancements); and the motion of
 * entities that aren't test players (their {@code step}, {@code swim}, {@code flap}, {@code hit_ground},
 * {@code splash} and {@code elytra_glide}) and the sounds of their idle life (a mob's ambient sound, steps, swimming,
 * splashing, landing, a parrot on a shoulder), so a cow wandering by isn't one; and the numbered events dripstone makes
 * dripping into a cauldron. A test player's own motion and its sounds are recorded: move them before watching. Weather
 * and music aren't sent by the server at all: clients play them themselves. Nor is the sound of a hit that dealt no
 * damage ({@code entity.player.attack.nodamage}): vanilla plays it for every such hit, a refused one included, as
 * Bukkit does, and it tells the players near that nothing happened.
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

    /**
     * A game event was emitted at {@code pos} in {@code level}, which sculk sensors, wardens and allays within range
     * there hear. A test player's events are recorded in any world, so compare {@code level} before {@code pos}.
     */
    record GameEventEmitted(Holder<GameEvent> event, Level level, Vec3 pos, Optional<Entity> source,
            List<StackTraceElement> from) implements Effect {
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

    /** Who a sound or a numbered event was sent to. */
    enum Heard {
        /** Every player online, wherever they are: a global event such as the wither's spawn. */
        EVERYONE,
        /** Every player within its range in that world. */
        ALL_NEAR,
        /** Every player within its range but {@code player}, whose client plays it itself as it acts. */
        ALL_NEAR_BUT_PLAYER,
        /** Only {@code player}. */
        PLAYER_ONLY;

        /** Whether it names a player: every kind but those sent to everyone near or everywhere. */
        boolean namesPlayer() {
            return this == ALL_NEAR_BUT_PLAYER || this == PLAYER_ONLY;
        }

        String describe(Optional<Player> player) {
            String name = player.map(p -> p.getGameProfile().getName()).orElse("?");
            return switch (this) {
                case EVERYONE -> "sent to everyone";
                case ALL_NEAR -> "sent to all near";
                case ALL_NEAR_BUT_PLAYER -> "sent to all near but " + name + ", whose client plays it itself";
                case PLAYER_ONLY -> "sent to " + name + " only";
            };
        }

        static void check(Heard heard, Optional<Player> player) {
            if (heard.namesPlayer() != player.isPresent()) {
                throw new IllegalArgumentException(heard + (heard.namesPlayer() ? " needs" : " takes no") + " player");
            }
        }
    }

    /**
     * The server sent the sound {@code sound} at {@code pos} in {@code level}, following {@code following} if it moves
     * with an entity. {@code player} is who {@code heard} names: the player left out because their own client plays it,
     * or the only one sent it.
     */
    record SoundPlayed(Holder<SoundEvent> sound, SoundSource source, Level level, Vec3 pos, Optional<Entity> following,
            Heard heard, Optional<Player> player, List<StackTraceElement> from) implements Effect {
        public SoundPlayed {
            Heard.check(heard, player);
        }

        /** Such as {@code sound minecraft:block.lever.click (block) at 2 64 0, sent to all near but alice, ...}. */
        @Override
        public String toString() {
            return "sound " + sound.unwrapKey().map(k -> k.location().toString())
                    .orElse(sound.value().getLocation().toString()) + " (" + source.getName() + ") at " + at(pos)
                    + following.map(e -> " on " + e.getName().getString()).orElse("") + " in "
                    + level.dimension().location() + ", " + heard.describe(player) + frames(from);
        }
    }

    /**
     * The server sent one of vanilla's numbered events that plays a sound ({@link LevelEvent}: a dispenser's click, a
     * door a zombie bangs on, a block breaking), at {@code pos} in {@code level}, with its {@code data}.
     */
    record LevelEventPlayed(int type, Level level, BlockPos pos, int data, Heard heard, Optional<Player> player,
            List<StackTraceElement> from) implements Effect {
        public LevelEventPlayed {
            Heard.check(heard, player);
        }

        /** Its name in {@link LevelEvent}, such as {@code SOUND_DISPENSER_DISPENSE}, or its number if it has none. */
        public String name() {
            for (java.lang.reflect.Field f : LevelEvent.class.getFields()) {
                try {
                    if (f.getType() == int.class && f.getInt(null) == type) return f.getName();
                } catch (IllegalAccessException e) {
                    break;
                }
            }
            return String.valueOf(type);
        }

        /** Such as {@code level event 1000 SOUND_DISPENSER_DISPENSE at 2 64 0 in ..., sent to all near, from ...}. */
        @Override
        public String toString() {
            return "level event " + type + " " + name() + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                    + " in " + level.dimension().location() + ", " + heard.describe(player) + frames(from);
        }
    }

    private static String at(Vec3 pos) {
        return (int) Math.floor(pos.x) + " " + (int) Math.floor(pos.y) + " " + (int) Math.floor(pos.z);
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
