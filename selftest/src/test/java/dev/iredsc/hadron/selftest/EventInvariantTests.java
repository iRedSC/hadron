package dev.iredsc.hadron.selftest;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.entity.EntityPotionEffectEvent;
import dev.iredsc.hadron.api.event.entity.EntityUseEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.item.ItemCraftEvent;
import dev.iredsc.hadron.api.event.item.ItemDamageEvent;
import dev.iredsc.hadron.api.event.player.PlayerFishEvent;
import dev.iredsc.hadron.api.event.player.PlayerSwapHandItemsEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * States an event refuses to be built in, and stacks no listener can edit through. These need a real level, entity or
 * player; the rest are fast tests in {@code api} ({@code EventStackTest}, {@code InventoryChangeEventTest}).
 */
public final class EventInvariantTests {
    private static final Actor NOBODY = Actor.Unknown.INSTANCE;
    private static final InventoryHolder NOWHERE = InventoryHolder.Unknown.INSTANCE;

    /** Runs {@code build}, which must throw {@code IllegalArgumentException} with a message holding {@code fix}. */
    private static void refused(TestContext t, String what, String fix, Runnable build) {
        try {
            build.run();
        } catch (IllegalArgumentException e) {
            t.assertTrue(Objects.requireNonNull(e.getMessage()).contains(fix), what + " was refused, but its message "
                    + "doesn't say \"" + fix + "\": " + e.getMessage());
            return;
        }
        t.fail(what + " should be refused, but was built");
    }

    private static Pig pig(TestContext t) {
        return Objects.requireNonNull(EntityType.PIG.create(t.level()), "a pig");
    }

    @HadronTest
    void itemDamageRefusesANegativeAmountFromTheConstructorAsTheSetterDoes(TestContext t) {
        refused(t, "An ItemDamageEvent built with -1 damage", "setDamage(0) or cancel the event",
                () -> new ItemDamageEvent(t.level(), NOWHERE, null, new ItemStack(Items.IRON_PICKAXE), -1, NOBODY));
        ItemDamageEvent e = new ItemDamageEvent(t.level(), NOWHERE, null, new ItemStack(Items.IRON_PICKAXE), 1, NOBODY);
        refused(t, "setDamage(-1)", "setDamage(0) or cancel the event", () -> e.setDamage(-1));
        t.assertEquals(1, e.getDamage(), "the damage after a refused setDamage");
    }

    @HadronTest
    void hugeItemDamageBreaksTheItemRatherThanWrappingAround(TestContext t) {
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        pickaxe.setDamageValue(10);
        ItemDamageEvent e = new ItemDamageEvent(t.level(), NOWHERE, null, pickaxe, Integer.MAX_VALUE, NOBODY);
        t.assertTrue(e.willBreak(), "Integer.MAX_VALUE damage on a used pickaxe should break it");
        t.assertTrue(e.getAfter().isEmpty(), "a broken item's after should be empty, but is " + e.getAfter());
        e.setDamage(Integer.MAX_VALUE - 5);
        t.assertTrue(e.willBreak() && e.getAfter().isEmpty(), "a huge setDamage should break it too");
        e.setDamage(5);
        t.assertEquals(15, e.getAfter().getDamageValue(), "the damage value after 5 more");
    }

    @HadronTest
    @Covers(ItemDamageEvent.class)
    void hugeDurabilityDamageBreaksTheItemAsTheEventSaid(TestContext t) {
        ServerPlayer alice = t.player("alice").player();
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        pickaxe.setDamageValue(10);
        boolean[] predicted = {false};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemDamageEvent e) {
                e.setDamage(Integer.MAX_VALUE);
                predicted[0] = e.willBreak() && e.getAfter().isEmpty();
            }
        });
        pickaxe.hurtAndBreak(1, t.level(), alice, item -> {});
        t.assertTrue(predicted[0], "the event should say Integer.MAX_VALUE damage breaks the pickaxe");
        t.assertTrue(pickaxe.isEmpty(), "the pickaxe should break as the event said, but is " + pickaxe
                + " at damage " + pickaxe.getDamageValue());
    }

    @HadronTest
    void anItemChangesStacksAreCopiesSoAListenerCantChangeTheDamageMaths(TestContext t) {
        ItemDamageEvent e = new ItemDamageEvent(t.level(), NOWHERE, null, new ItemStack(Items.IRON_PICKAXE), 3,
                NOBODY);
        e.getBefore().setDamageValue(e.getBefore().getMaxDamage() - 1); // a listener writing to what it read
        e.getItem().setDamageValue(e.getBefore().getMaxDamage() - 1);
        e.getAfter().setCount(0);
        t.assertEquals(0, e.getBefore().getDamageValue(), "the stack before, after a listener edited a read copy");
        t.assertFalse(e.willBreak(), "3 damage on a new pickaxe shouldn't break it, whatever a listener edited");
        t.assertEquals(3, e.getAfter().getDamageValue(), "the stack after");
    }

    @HadronTest
    void entityDamageRefusesAnAmountTheSetterRefuses(TestContext t) {
        Pig pig = pig(t);
        for (float bad : new float[] {-1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            refused(t, "An EntityDamageEvent built with " + bad + " damage", "setCancelled(true)",
                    () -> new EntityDamageEvent(pig, t.level().damageSources().generic(), bad, NOBODY));
        }
        EntityDamageEvent max = new EntityDamageEvent(pig, t.level().damageSources().generic(), Float.MAX_VALUE,
                NOBODY);
        t.assertEquals(Float.MAX_VALUE, max.getDamage(), "the largest finite damage");
        refused(t, "setDamage(NaN)", "setCancelled(true)", () -> max.setDamage(Float.NaN));
    }

    @HadronTest
    void aPotionChangeMustKeepItsEffect(TestContext t) {
        Pig pig = pig(t);
        MobEffectInstance speed = new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200);
        MobEffectInstance poison = new MobEffectInstance(MobEffects.POISON, 200);
        refused(t, "A change from speed to poison", "entity.getEffect(added.getEffect())",
                () -> new EntityPotionEffectEvent.Change.Changed(speed, poison));

        EntityPotionEffectEvent added = new EntityPotionEffectEvent(pig,
                new EntityPotionEffectEvent.Change.Added(speed), NOBODY);
        t.assertEquals(EntityPotionEffectEvent.Action.ADDED, added.getAction(), "added's action");
        t.assertTrue(added.getOldEffect().isEmpty() && added.getNewEffect().isPresent(), "added has only a new effect");
        EntityPotionEffectEvent removed = new EntityPotionEffectEvent(pig,
                new EntityPotionEffectEvent.Change.Removed(poison), NOBODY);
        t.assertEquals(MobEffects.POISON, removed.getEffect(), "removed's effect, taken from the instance");
        t.assertTrue(removed.getOldEffect().isPresent() && removed.getNewEffect().isEmpty(),
                "removed has only an old effect");

        added.getNewEffect().orElseThrow().update(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 9000, 4));
        t.assertEquals(0, added.getNewEffect().orElseThrow().getAmplifier(), "a listener's edit reached the effect");
        speed.update(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 9000, 4));
        t.assertEquals(200, added.getNewEffect().orElseThrow().getDuration(), "the caller's later edit reached it");
    }

    @HadronTest
    void aFishingStepRefusesWhatOnlyAnotherStepHas(TestContext t) {
        ServerPlayer alice = t.player("alice").player();
        FishingHook hook = Objects.requireNonNull(EntityType.FISHING_BOBBER.create(t.level()), "a bobber");
        Actor actor = new Actor.Player(alice);
        refused(t, "A BITE with a hooked entity", "pass null as caught",
                () -> new PlayerFishEvent(alice, hook, PlayerFishEvent.State.BITE, pig(t), null, 0, actor));
        refused(t, "A CAUGHT_ENTITY without one", "pass it as caught",
                () -> new PlayerFishEvent(alice, hook, PlayerFishEvent.State.CAUGHT_ENTITY, null, null, 0, actor));
        refused(t, "A REEL_IN with a catch", "pass null as items and 0 as exp",
                () -> new PlayerFishEvent(alice, hook, PlayerFishEvent.State.REEL_IN, null,
                        List.of(new ItemStack(Items.COD)), 0, actor));
        refused(t, "A FISHING with experience", "pass null as items and 0 as exp",
                () -> new PlayerFishEvent(alice, hook, PlayerFishEvent.State.FISHING, null, null, 5, actor));
    }

    @HadronTest
    void handedOutStacksAreNewOnEveryRead(TestContext t) {
        ServerPlayer alice = t.player("alice").player();
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        PlayerSwapHandItemsEvent swap = new PlayerSwapHandItemsEvent(alice, sword, ItemStack.EMPTY);
        swap.getMainHandItem().setCount(0);
        sword.setCount(0);
        t.assertTrue(swap.getMainHandItem().is(Items.IRON_SWORD), "a swap's main hand after edits to read stacks");

        ItemStack carrot = new ItemStack(Items.CARROT, 2);
        EntityUseEvent use = new EntityUseEvent(pig(t), InteractionHand.MAIN_HAND, carrot, NOBODY);
        use.getItem().shrink(2);
        carrot.shrink(2);
        t.assertEquals(2, use.getItem().getCount(), "an entity use's item after edits to read stacks");

        ItemCraftEvent craft = new ItemCraftEvent(t.level(), NOWHERE, List.of(new ItemStack(Items.STICK)),
                List.of(new ItemStack(Items.TORCH, 4)), null, NOBODY);
        craft.getInputs().get(0).setCount(0);
        craft.getOutputs().get(0).setCount(64);
        craft.getResult().setCount(64);
        t.assertEquals(1, craft.getInputs().get(0).getCount(), "a craft's input after a listener edited it");
        t.assertEquals(4, craft.getResult().getCount(), "a craft's result after a listener edited it");
    }
}
