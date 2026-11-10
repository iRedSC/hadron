package dev.iredsc.hadron.harness;

import dev.iredsc.hadron.checks.StaticState;
import dev.iredsc.hadron.neoforge.HeldEffects;
import dev.iredsc.hadron.test.SideEffects;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.StatAwardEvent;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import org.jspecify.annotations.Nullable;

/**
 * One {@link SideEffects} watch of one test. NeoForge's events report statistics, game events and experience; the
 * harness's mixins report advancement triggers ({@code SimpleCriterionTriggerMixin}), food exhaustion
 * ({@code PlayerExhaustionMixin}) and sounds, as the server sends them ({@code ServerLevelSoundMixin},
 * {@code ServerPlayerNotifySoundMixin}). Every hook returns at once while no watch is running.
 */
public final class TestSideEffects implements SideEffects {
    @StaticState(reason = "the running test's watches; TestWorld.close stops each at the test's end")
    private static final List<TestSideEffects> ACTIVE = new CopyOnWriteArrayList<>();

    /**
     * How deep the server thread is inside an ambient trigger: what one causes (standing in water completes the boat
     * recipes' advancements, which unlock recipes and pay rewards) is as ambient as the trigger, so it isn't recorded.
     */
    @StaticState(reason = "a depth counter of trigger calls on the server thread, back to 0 as each call returns")
    private static int ambientDepth;

    /** Frames of the bookkeeping between an action and the hook, skipped so {@code from} starts at the action. */
    private static final Set<String> BOOKKEEPING_METHODS = Set.of("awardStat", "resetStat", "gameEvent",
            "giveExperiencePoints", "giveExperienceLevels", "onEnchantmentPerformed", "causeFoodExhaustion",
            "awardRecipes", "awardRecipesByKey", "playSound", "playSeededSound", "levelEvent", "globalLevelEvent",
            "playNotifySound");
    private static final List<String> BOOKKEEPING_PACKAGES = List.of("dev.iredsc.hadron.harness.",
            "dev.iredsc.hadron.neoforge.HeldEffects",
            "net.minecraft.advancements.", "net.minecraft.server.PlayerAdvancements", "net.minecraft.stats.", "net.minecraft.world.level.gameevent.",
            "net.neoforged.bus.", "net.neoforged.neoforge.event.EventHooks", "net.neoforged.neoforge.common.CommonHooks",
            "java.", "jdk.");
    private static final int FRAMES = 3;

    /**
     * What every player's tick or a wandering mob makes, whatever the test does. Its own class, so Minecraft's
     * registries are read at the first hook, not when the harness registers its listeners as mods load.
     */
    private static final class Ambient {
        /** Counted by every player's tick, whatever the test does. */
        private static final Set<ResourceLocation> CLOCK_STATS = Set.of(Stats.PLAY_TIME, Stats.TOTAL_WORLD_TIME,
                Stats.TIME_SINCE_DEATH, Stats.TIME_SINCE_REST);

        /** Fired by every player's tick, whatever the test does: {@code enter_block} for each block they stand in. */
        private static final Set<CriterionTrigger<?>> CLOCK_TRIGGERS = Set.of(CriteriaTriggers.TICK,
                CriteriaTriggers.LOCATION, CriteriaTriggers.ENTER_BLOCK, CriteriaTriggers.LEVITATION);

        /** Motion: a wandering mob's, recorded only when a test player's own. */
        private static final Set<Holder<GameEvent>> MOTION = Set.of(GameEvent.STEP, GameEvent.SWIM, GameEvent.FLAP,
                GameEvent.HIT_GROUND, GameEvent.SPLASH, GameEvent.ELYTRA_GLIDE);
    }

    /**
     * Sounds: what a mob's idle life makes (its ambient sound, steps, swimming, landing, a shoulder parrot's call),
     * named by the method that plays it, and the numbered events that play no sound, or that dripstone makes alone.
     */
    private static final class AmbientSounds {
        private static final int STACK_DEPTH = 24;

        private static final Set<String> IDLE_METHODS = Set.of("playAmbientSound", "playStepSound",
                "playMuffledStepSound", "playCombinationStepSounds", "playAmethystStepSound", "playSwimSound",
                "waterSwimSound", "doWaterSplashEffect", "playBlockFallSound", "playShoulderEntityAmbientSound");

        /**
         * Numbered events whose client effect plays no sound (LevelRenderer.levelEvent: particles only), and the drip
         * of dripstone into a cauldron, which a random tick makes.
         */
        /**
         * The sound of a hit that dealt no damage, which is what a refused hit is: vanilla plays it for every such hit,
         * as Bukkit does for a cancelled one, so it tells the players near that nothing happened.
         */
        private static final SoundEvent NO_DAMAGE = SoundEvents.PLAYER_ATTACK_NODAMAGE;

        private static final Set<Integer> SILENT_OR_AMBIENT_EVENTS = Set.of(1046, 1047, 1504, 2000, 2003, 2004, 2008,
                2009, 2010, 2011, 2012, 2013, 3002, 3004, 3005, 3009, 3011, 3017);
    }

    private final TestWorld world;
    private final List<Effect> seen = new CopyOnWriteArrayList<>();

    private TestSideEffects(TestWorld world) {
        this.world = world;
    }

    /** A watch of the test running in {@code world}, recording from now until {@link #stop}. */
    static TestSideEffects start(TestWorld world) {
        TestSideEffects watch = new TestSideEffects(world);
        ACTIVE.add(watch);
        return watch;
    }

    void stop() {
        ACTIVE.remove(this);
    }

    @Override
    public List<Effect> seen() {
        return List.copyOf(seen);
    }

    @Override
    public void mark() {
        seen.clear();
    }

    // ---- Hooks ----

    /** On a test server, so a normal server never has the listeners. */
    static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, TestSideEffects::onStat);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, TestSideEffects::onGameEvent);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (PlayerXpEvent.XpChange e) -> {
            if (e.getAmount() != 0) record(e.getEntity(), p -> new ExperienceChanged(p, e.getAmount(), from()));
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (PlayerXpEvent.LevelChange e) -> {
            if (e.getLevels() != 0) record(e.getEntity(), p -> new LevelsChanged(p, e.getLevels(), from()));
        });
    }

    private static void onStat(StatAwardEvent e) {
        if (ACTIVE.isEmpty() || !(e.getEntity() instanceof ServerPlayer player)) return;
        Stat<?> stat = e.getStat();
        if (stat.getType() == Stats.CUSTOM && stat.getValue() instanceof ResourceLocation id && Ambient.CLOCK_STATS.contains(id)) {
            return;
        }
        int before = player.getStats().getValue(stat);
        if (before == e.getValue()) return;
        record(player, p -> new StatChanged(p, stat, before, e.getValue(), from()));
    }

    /** A step, a swim, a flap: motion, which anyone may make. */
    static boolean isMotion(Holder<GameEvent> event) {
        return Ambient.MOTION.contains(event);
    }

    private static void onGameEvent(VanillaGameEvent e) {
        if (ACTIVE.isEmpty() || ambientDepth > 0) return;
        @Nullable Entity cause = e.getCause();
        for (TestSideEffects watch : ACTIVE) {
            boolean byPlayer = cause != null && watch.world.testPlayer(cause) != null;
            if (!byPlayer && (e.getLevel() != watch.world.level() || Ambient.MOTION.contains(e.getVanillaEvent()))) continue;
            watch.seen.add(new GameEventEmitted(e.getVanillaEvent(), e.getLevel(), e.getEventPosition(), Optional.ofNullable(cause),
                    from()));
        }
    }

    /**
     * From {@code SimpleCriterionTriggerMixin}, as any vanilla advancement trigger fires for a player: records it, then
     * {@code fire}s it. An ambient trigger isn't recorded, and neither is anything it causes while it fires.
     */
    public static void criterionTriggered(CriterionTrigger<?> trigger, ServerPlayer player, Runnable fire) {
        if (ACTIVE.isEmpty()) {
            fire.run();
        } else if (Ambient.CLOCK_TRIGGERS.contains(trigger)) {
            ambientDepth++;
            try {
                fire.run();
            } finally {
                ambientDepth--;
            }
        } else {
            recordUnlessDropped(player, p -> new CriterionTriggered(p, trigger, from()));
            fire.run();
        }
    }

    /**
     * From {@code ServerLevelSoundMixin}, as the server sends a sound at a position, or on {@code following} if it
     * moves with an entity, to everyone near but {@code except}.
     */
    public static void soundSent(ServerLevel level, @Nullable Player except, Holder<SoundEvent> sound,
            SoundSource source, Vec3 pos, @Nullable Entity following) {
        if (ACTIVE.isEmpty() || ambientDepth > 0 || sound.value() == AmbientSounds.NO_DAMAGE) return;
        Heard heard = except == null ? Heard.ALL_NEAR : Heard.ALL_NEAR_BUT_PLAYER;
        sent(level, except != null ? except : following, () -> new SoundPlayed(sound, source, level, pos,
                Optional.ofNullable(following), heard, Optional.ofNullable(except), from()));
    }

    /** From {@code ServerLevelSoundMixin}, as the server sends one of vanilla's numbered events. */
    public static void levelEventSent(ServerLevel level, @Nullable Player except, int type, BlockPos pos, int data,
            boolean global) {
        if (ACTIVE.isEmpty() || ambientDepth > 0 || AmbientSounds.SILENT_OR_AMBIENT_EVENTS.contains(type)) return;
        Heard heard = global ? Heard.EVERYONE : except == null ? Heard.ALL_NEAR : Heard.ALL_NEAR_BUT_PLAYER;
        sent(level, except, () -> new LevelEventPlayed(type, level, pos, data, heard, Optional.ofNullable(except),
                from()));
    }

    /** From {@code ServerPlayerNotifySoundMixin}, as the server sends a sound to one player alone. */
    public static void notifySoundSent(ServerPlayer to, SoundEvent sound, SoundSource source) {
        if (ACTIVE.isEmpty() || ambientDepth > 0) return;
        sent(to.serverLevel(), to, () -> new SoundPlayed(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), source,
                to.serverLevel(), to.position(), Optional.empty(), Heard.PLAYER_ONLY, Optional.of(to), from()));
    }

    /**
     * Records a sound for each watch it concerns: one from the watch's world, or made by or for one of its players
     * anywhere. A mob's idle sound isn't one, unless a test player makes it.
     */
    private static void sent(ServerLevel level, @Nullable Entity who, Supplier<Effect> effect) {
        @Nullable Effect read = null;
        for (TestSideEffects watch : ACTIVE) {
            boolean byPlayer = who != null && watch.world.testPlayer(who) != null;
            if (!byPlayer && (level != watch.world.level() || idle())) continue;
            if (read == null) read = effect.get();
            watch.seen.add(read);
        }
    }

    /** Whether the sound being sent comes from a mob's idle life ({@link AmbientSounds#IDLE_METHODS}). */
    private static boolean idle() {
        return StackWalker.getInstance().walk(frames -> frames.limit(AmbientSounds.STACK_DEPTH)
                .anyMatch(f -> AmbientSounds.IDLE_METHODS.contains(f.getMethodName())));
    }

    /** From {@code PlayerExhaustionMixin}, as a player is made hungrier. */
    public static void foodExhausted(Player player, float exhaustion) {
        if (ACTIVE.isEmpty() || exhaustion == 0 || player.getAbilities().invulnerable || player.level().isClientSide) {
            return;
        }
        record(player, p -> new FoodExhausted(p, exhaustion, from()));
    }

    private static void record(Player player, Function<TestPlayer, Effect> effect) {
        if (ambientDepth > 0) return;
        for (TestSideEffects watch : ACTIVE) {
            TestPlayer testPlayer = watch.world.testPlayer(player);
            if (testPlayer != null) watch.seen.add(effect.apply(testPlayer));
        }
    }

    /**
     * As {@link #record}, for a trigger fired inside core's {@link HeldEffects} hold: its grant waits on whether the
     * action stands, so the record does too, and a refused action's trigger, which grants nothing, isn't recorded.
     * Read now, so it names the line that fired it.
     */
    private static void recordUnlessDropped(Player player, Function<TestPlayer, Effect> effect) {
        if (ambientDepth > 0) return;
        List<Runnable> adds = new ArrayList<>();
        for (TestSideEffects watch : ACTIVE) {
            TestPlayer testPlayer = watch.world.testPlayer(player);
            if (testPlayer == null) continue;
            Effect read = effect.apply(testPlayer);
            adds.add(() -> watch.seen.add(read));
        }
        Runnable commit = () -> adds.forEach(Runnable::run);
        if (!HeldEffects.hold(commit)) commit.run();
    }

    /** The code that caused the effect: the first frames outside the bookkeeping that reports it. */
    private static List<StackTraceElement> from() {
        return StackWalker.getInstance().walk(frames -> frames.map(StackWalker.StackFrame::toStackTraceElement)
                .filter(f -> !bookkeeping(f)).limit(FRAMES).toList());
    }

    private static boolean bookkeeping(StackTraceElement frame) {
        String type = frame.getClassName();
        String method = frame.getMethodName();
        // Core's hold on what may yet be refused (HeldEffects and the mixins that hand it over) is bookkeeping too, and
        // so is a trampoline MixinExtras generates, which has no line of its own.
        if (BOOKKEEPING_METHODS.contains(method) || method.contains("hadron$record") || method.contains("hadron$hold")
                || method.startsWith("mixinextras$bridge$")) {
            return true;
        }
        for (String prefix : BOOKKEEPING_PACKAGES) if (type.startsWith(prefix)) return true;
        return false;
    }
}
