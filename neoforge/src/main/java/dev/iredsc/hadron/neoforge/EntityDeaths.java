package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.entity.EntityDeathEvent;
import dev.iredsc.hadron.api.event.player.PlayerDeathEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * The death event, fired once a dying entity's drops and experience are both known. Vanilla decides experience
 * before drops, so while a death listener waits, the experience is held back on the entity and dropped by us with
 * whatever the listeners chose.
 */
public final class EntityDeaths {
    private EntityDeaths() {}

    /** {@code LivingEntity.dropAllDeathLoot} begins. */
    public static void begin(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel)) return;
        Class<? extends Event> type = entity instanceof ServerPlayer ? PlayerDeathEvent.class : EntityDeathEvent.class;
        if (!Hooks.listens(type)) return;
        EntityState state = (EntityState) entity;
        state.hadron$setDying(true);
        state.hadron$setDeathExp(0);
    }

    /** The experience vanilla would drop; held back while dying. Returns what vanilla should drop now. */
    static int holdExp(LivingEntity entity, int exp) {
        EntityState state = (EntityState) entity;
        if (!state.hadron$dying()) return exp;
        state.hadron$setDeathExp(state.hadron$deathExp() + exp);
        return 0;
    }

    /** NeoForge's drop list is final: fire the event and apply what listeners chose. */
    static void drops(LivingEntity entity, DamageSource source, Collection<ItemEntity> drops) {
        EntityState state = (EntityState) entity;
        if (!state.hadron$dying()) return;
        state.hadron$setDying(false);
        fire(entity, source, drops);
    }

    /** {@code dropAllDeathLoot} returns; if a mod skipped the drops event, fire with no drops so death is heard. */
    public static void end(LivingEntity entity, DamageSource source) {
        EntityState state = (EntityState) entity;
        if (!state.hadron$dying()) return;
        state.hadron$setDying(false);
        List<ItemEntity> spawned = new ArrayList<>();
        fire(entity, source, spawned);
        for (ItemEntity item : spawned) entity.level().addFreshEntity(item);
    }

    private static void fire(LivingEntity entity, DamageSource source, Collection<ItemEntity> drops) {
        ServerLevel level = (ServerLevel) entity.level();
        Map<ItemStack, ItemEntity> origin = new IdentityHashMap<>();
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemEntity drop : drops) {
            origin.put(drop.getItem(), drop);
            stacks.add(drop.getItem());
        }
        int exp = ((EntityState) entity).hadron$deathExp();
        ((EntityState) entity).hadron$setDeathExp(0);
        List<ItemStack> kept = new ArrayList<>();
        // A player's death line was held back in die(); the event decides it, and it is shown once listeners finish.
        boolean heldLine = entity instanceof ServerPlayer p && PlayerMessages.deathHeld(p);
        EntityDeathEvent event = entity instanceof ServerPlayer player
                ? new PlayerDeathEvent(player, source, stacks, kept, exp, PlayerMessages.takeDeath(player),
                        EntityActors.damage(source, player))
                : new EntityDeathEvent(entity, source, stacks, exp, EntityActors.damage(source, entity));
        Hooks.post(event);
        if (heldLine && event instanceof PlayerDeathEvent death) {
            PlayerMessages.death(death.getPlayer(), death.getDeathMessage());
        }

        // A stack listed as both kept and dropped is kept, so a listener that forgets to remove it can't duplicate it.
        Set<ItemStack> keptSet = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ItemStack stack : kept) {
            if (stack != null && !stack.isEmpty()) keptSet.add(stack);
        }
        drops.clear();
        for (ItemStack stack : event.getDrops()) {
            if (stack == null || stack.isEmpty() || keptSet.contains(stack)) continue;
            ItemEntity drop = origin.remove(stack);
            if (drop == null) {
                drop = new ItemEntity(level, entity.getX(), entity.getY(), entity.getZ(), stack);
                drop.setDefaultPickUpDelay();
            }
            drops.add(drop);
        }
        if (entity instanceof ServerPlayer player && !keptSet.isEmpty()) EntityKeptItems.keep(player, keptSet);
        if (event.getDroppedExp() > 0) ExperienceOrb.award(level, entity.position(), event.getDroppedExp());
    }
}
