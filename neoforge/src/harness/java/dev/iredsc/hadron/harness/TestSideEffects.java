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
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.StatAwardEvent;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import org.jspecify.annotations.Nullable;

/**
 * One {@link SideEffects} watch of one test. NeoForge's events report statistics, game events and experience; the
 * harness's mixins report advancement triggers ({@code SimpleCriterionTriggerMixin}) and food exhaustion
 * ({@code PlayerExhaustionMixin}). Every hook returns at once while no watch is running.
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
            "awardRecipes", "awardRecipesByKey");
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
