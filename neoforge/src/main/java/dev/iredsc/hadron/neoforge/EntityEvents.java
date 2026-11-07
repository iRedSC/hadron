package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.event.entity.EntityBreedEvent;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.entity.EntityDismountEvent;
import dev.iredsc.hadron.api.event.entity.EntityKnockbackEvent;
import dev.iredsc.hadron.api.event.entity.EntityMountEvent;
import dev.iredsc.hadron.api.event.entity.EntityPotionEffectEvent;
import dev.iredsc.hadron.api.event.entity.EntityPotionEffectExpireEvent;
import dev.iredsc.hadron.api.event.entity.EntityRegainHealthEvent;
import dev.iredsc.hadron.api.event.entity.EntityTameEvent;
import dev.iredsc.hadron.api.event.entity.EntityTargetEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent.TeleportCause;
import dev.iredsc.hadron.api.event.entity.EntityTransformEvent;
import dev.iredsc.hadron.api.event.entity.EntityUseEvent;
import dev.iredsc.hadron.api.event.entity.ExperiencePickupEvent;
import dev.iredsc.hadron.api.event.entity.ProjectileHitEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;

/**
 * Hadron's entity events ({@code dev.iredsc.hadron.api.event.entity}) from NeoForge's events and the entity mixins
 * ({@code hadron.entity.mixins.json}). Cancellable translations listen last, so plugins hear only what mods let
 * through and a cancel here is the final word.
 */
final class EntityEvents {
    private EntityEvents() {}

    static void register() {
        AttributionMemory.register(ModLoadingContext.get().getActiveContainer().getEventBus());
        Actors.Port.installMemory(new AttributionMemory());

        var bus = NeoForge.EVENT_BUS;
        bus.addListener(EventPriority.LOWEST, EntityEvents::damage);
        bus.addListener(EventPriority.LOWEST, EntityEvents::heal);
        bus.addListener(EventPriority.LOWEST, EntityEvents::knockback);
        bus.addListener(EventPriority.LOWEST, EntityEvents::effectAdded);
        bus.addListener(EventPriority.LOWEST, EntityEvents::effectRemoved);
        bus.addListener(EventPriority.LOWEST, EntityEvents::effectExpired);
        bus.addListener(EventPriority.LOWEST, EntityEvents::target);
        bus.addListener(EventPriority.LOWEST, EntityEvents::mount);
        bus.addListener(EventPriority.LOWEST, EntityEvents::tame);
        bus.addListener(EventPriority.LOWEST, EntityEvents::breed);
        bus.addListener(EventPriority.LOWEST, EntityEvents::transform);
        bus.addListener(EventPriority.LOWEST, EntityEvents::transformed);
        bus.addListener(EventPriority.LOWEST, EntityEvents::projectileHit);
        bus.addListener(EventPriority.LOWEST, EntityEvents::experiencePickup);
        bus.addListener(EventPriority.LOWEST, EntityEvents::use);
        bus.addListener(EventPriority.LOWEST, EntityEvents::useAt);
        bus.addListener(EventPriority.LOWEST, EntityEvents::teleport);
        // Experience is final once every mod has had its say; drops are decided first, before graves take them.
        bus.addListener(EventPriority.LOWEST, EntityEvents::deathExp);
        bus.addListener(EventPriority.HIGHEST, true, EntityEvents::deathDrops);
        bus.addListener(EntityEvents::respawn);
    }

    private static boolean server(Entity entity) {
        return entity.level() instanceof ServerLevel;
    }

    private static void damage(LivingIncomingDamageEvent event) {
        LivingEntity entity = event.getEntity();
        if (!server(entity) || !Hooks.listens(EntityDamageEvent.class)) return;
        EntityDamageEvent hadron = Hooks.post(new EntityDamageEvent(entity, event.getSource(), event.getAmount(),
                EntityActors.damage(event.getSource(), entity)));
        if (hadron.isCancelled()) {
            event.setCanceled(true);
        } else if (hadron.getDamage() != event.getAmount()) {
            event.setAmount(hadron.getDamage());
        }
    }

    private static void heal(LivingHealEvent event) {
        LivingEntity entity = event.getEntity();
        if (!server(entity) || event.getAmount() <= 0 || !Hooks.listens(EntityRegainHealthEvent.class)) return;
        EntityRegainHealthEvent hadron = Hooks.post(new EntityRegainHealthEvent(entity, event.getAmount(),
                EntityActors.fromStack(entity, Actor.Nature.INSTANCE)));
        if (hadron.isCancelled()) {
            event.setCanceled(true);
        } else {
            event.setAmount(hadron.getAmount());
        }
    }

    private static void knockback(LivingKnockBackEvent event) {
        LivingEntity entity = event.getEntity();
        if (!server(entity) || !Hooks.listens(EntityKnockbackEvent.class)) return;
        EntityKnockbackEvent hadron = Hooks.post(new EntityKnockbackEvent(entity, event.getStrength(),
                event.getRatioX(), event.getRatioZ(), EntityActors.lastHit(entity)));
        if (hadron.isCancelled()) {
            event.setCanceled(true);
        } else {
            event.setStrength(hadron.getStrength());
        }
    }

    private static void effectAdded(MobEffectEvent.Applicable event) {
        LivingEntity entity = event.getEntity();
        MobEffectInstance added = event.getEffectInstance();
        if (!server(entity) || added == null || !event.getApplicationResult()
                || !Hooks.listens(EntityPotionEffectEvent.class)) return;
        MobEffectInstance old = entity.getEffect(added.getEffect());
        Entity source = event.getEffectSource();
        Actor actor = source != null && source != entity
                ? EntityActors.of(source) : EntityActors.fromStack(entity, Actor.Nature.INSTANCE);
        EntityPotionEffectEvent hadron = Hooks.post(new EntityPotionEffectEvent(entity, added.getEffect(), old, added,
                old == null ? EntityPotionEffectEvent.Action.ADDED : EntityPotionEffectEvent.Action.CHANGED, actor));
        if (hadron.isCancelled()) event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
    }

    private static void effectRemoved(MobEffectEvent.Remove event) {
        LivingEntity entity = event.getEntity();
        if (!server(entity) || !Hooks.listens(EntityPotionEffectEvent.class)) return;
        MobEffectInstance old = entity.getEffect(event.getEffect());
        if (old == null) return;
        EntityPotionEffectEvent hadron = Hooks.post(new EntityPotionEffectEvent(entity, event.getEffect(), old, null,
                EntityPotionEffectEvent.Action.REMOVED, EntityActors.fromStack(entity, Actor.Nature.INSTANCE)));
        if (hadron.isCancelled()) event.setCanceled(true);
    }

    private static void effectExpired(MobEffectEvent.Expired event) {
        LivingEntity entity = event.getEntity();
        MobEffectInstance effect = event.getEffectInstance();
        if (!server(entity) || effect == null || !Hooks.listens(EntityPotionEffectExpireEvent.class)) return;
        Hooks.post(new EntityPotionEffectExpireEvent(entity, effect, Actor.Nature.INSTANCE));
    }

    private static void target(LivingChangeTargetEvent event) {
        LivingEntity entity = event.getEntity();
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (!server(entity) || !Hooks.listens(EntityTargetEvent.class)) return;
        // Mobs re-set the target they already have every few ticks; only a change is a choice.
        if (entity instanceof Mob mob && mob.getTarget() == target) return;
        EntityTargetEvent hadron = Hooks.post(new EntityTargetEvent(entity, target, EntityActors.of(entity)));
        if (hadron.isCancelled()) {
            event.setCanceled(true);
        } else if (hadron.getTarget().orElse(null) != target) {
            event.setNewAboutToBeSetTarget(hadron.getTarget().orElse(null));
        }
    }

    private static void mount(net.neoforged.neoforge.event.entity.EntityMountEvent event) {
        Entity rider = event.getEntityMounting();
        Entity vehicle = event.getEntityBeingMounted();
        if (rider == null || vehicle == null || !server(rider)) return;
        Actor actor = EntityActors.fromStack(rider, EntityActors.of(rider));
        if (event.isMounting()) {
            if (!Hooks.listens(EntityMountEvent.class)) return;
            if (Hooks.post(new EntityMountEvent(rider, vehicle, actor)).isCancelled()) event.setCanceled(true);
            return;
        }
        if (!Hooks.listens(EntityDismountEvent.class)) return;
        boolean forced = rider.isRemoved() || !rider.isAlive() || vehicle.isRemoved() || !vehicle.isAlive()
                || EntityTeleports.inProgress();
        if (Hooks.post(new EntityDismountEvent(rider, vehicle, forced, actor)).isCancelled() && !forced) {
            event.setCanceled(true);
        }
    }

    private static void tame(AnimalTameEvent event) {
        Animal animal = event.getAnimal();
        if (!server(animal) || event.getTamer() == null || !Hooks.listens(EntityTameEvent.class)) return;
        if (Hooks.post(new EntityTameEvent(animal, event.getTamer(), EntityActors.of(event.getTamer())))
                .isCancelled()) {
            event.setCanceled(true);
        }
    }

    private static void breed(BabyEntitySpawnEvent event) {
        if (event.getChild() == null || !server(event.getChild()) || !Hooks.listens(EntityBreedEvent.class)) return;
        // Vanilla places the baby only after this event; put it at its parent now so the event says where it is born.
        Mob parent = event.getParentA();
        event.getChild().moveTo(parent.getX(), parent.getY(), parent.getZ(), 0.0F, 0.0F);
        ServerPlayer breeder = event.getCausedByPlayer() instanceof ServerPlayer p ? p : null;
        Actor actor = breeder != null ? EntityActors.of(breeder)
                : EntityActors.fromStack(event.getChild(), EntityActors.of(event.getParentA()));
        if (Hooks.post(new EntityBreedEvent(event.getChild(), event.getParentA(), event.getParentB(), breeder, actor))
                .isCancelled()) {
            event.setCanceled(true);
        }
    }

    private static void transform(LivingConversionEvent.Pre event) {
        LivingEntity entity = event.getEntity();
        if (!server(entity) || !Hooks.listens(EntityTransformEvent.class)) return;
        if (Hooks.post(new EntityTransformEvent(entity, event.getOutcome(),
                EntityActors.fromStack(entity, Actor.Nature.INSTANCE))).isCancelled()) {
            event.setCanceled(true);
        }
    }

    /** The new entity of a conversion: not a spawn, and carries who was behind the old one. */
    private static void transformed(LivingConversionEvent.Post event) {
        if (!server(event.getEntity()) || event.getOutcome() == null) return;
        if (!event.getOutcome().isAddedToLevel()) ((EntityState) event.getOutcome()).hadron$setConverted(true);
        EntitySpawns.inherit(event.getEntity(), event.getOutcome());
    }

    private static void projectileHit(ProjectileImpactEvent event) {
        var projectile = event.getProjectile();
        if (!server(projectile) || !Hooks.listens(ProjectileHitEvent.class)) return;
        if (Hooks.post(new ProjectileHitEvent(projectile, event.getRayTraceResult(), EntityActors.of(projectile)))
                .isCancelled()) {
            event.setCanceled(true);
        }
    }

    private static void experiencePickup(PlayerXpEvent.PickupXp event) {
        if (!server(event.getOrb()) || !Hooks.listens(ExperiencePickupEvent.class)) return;
        if (Hooks.post(new ExperiencePickupEvent(event.getOrb(), EntityActors.of(event.getEntity()))).isCancelled()) {
            event.setCanceled(true);
        }
    }

    // The client sends "interact at" first and plain "interact" only if that did nothing, so one click can reach both.
    // The first decides for the click; the second in the same tick repeats its answer without asking again.
    private static Entity lastUser;
    private static Entity lastUsed;
    private static InteractionHand lastHand;
    private static long lastTick = Long.MIN_VALUE;
    private static boolean lastCancelled;

    private static void use(PlayerInteractEvent.EntityInteract event) {
        if (useCancelled(event, event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    private static void useAt(PlayerInteractEvent.EntityInteractSpecific event) {
        if (useCancelled(event, event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    private static boolean useCancelled(PlayerInteractEvent event, Entity target) {
        Entity player = event.getEntity();
        if (!(player.level() instanceof ServerLevel level) || !Hooks.listens(EntityUseEvent.class)) return false;
        long tick = level.getGameTime();
        if (player == lastUser && target == lastUsed && event.getHand() == lastHand && tick == lastTick) {
            return lastCancelled;
        }
        lastUser = player;
        lastUsed = target;
        lastHand = event.getHand();
        lastTick = tick;
        // A copy: the use that follows spends or replaces the held stack, and the event says what was in the hand.
        lastCancelled = Hooks.post(new EntityUseEvent(target, event.getHand(), event.getItemStack().copy(),
                EntityActors.of(player))).isCancelled();
        return lastCancelled;
    }

    /**
     * NeoForge's own teleports. Same-level ones fire here, where cancelling also skips their side effects (an ender
     * pearl's fall damage); the teleport method they then call won't ask again. Commands may cross dimensions, which
     * NeoForge's event can't say, so for them we only note the cause and let the teleport method ask.
     */
    private static void teleport(EntityTeleportEvent event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) return;
        TeleportCause cause = switch (event) {
            case EntityTeleportEvent.TeleportCommand c -> TeleportCause.COMMAND;
            case EntityTeleportEvent.SpreadPlayersCommand c -> TeleportCause.COMMAND;
            case EntityTeleportEvent.EnderPearl c -> TeleportCause.ENDER_PEARL;
            case EntityTeleportEvent.ChorusFruit c -> TeleportCause.CHORUS_FRUIT;
            case EntityTeleportEvent.EnderEntity c -> TeleportCause.RANDOM_TELEPORT;
            default -> TeleportCause.UNKNOWN;
        };
        if (cause == TeleportCause.COMMAND) {
            EntityTeleports.mark(entity, cause, false, null);
            return;
        }
        if (!Hooks.listens(dev.iredsc.hadron.api.event.entity.EntityTeleportEvent.class)) return;
        var hadron = Hooks.post(new dev.iredsc.hadron.api.event.entity.EntityTeleportEvent(entity, level,
                new net.minecraft.world.phys.Vec3(event.getTargetX(), event.getTargetY(), event.getTargetZ()), cause,
                EntityTeleports.actor(entity, cause)));
        if (hadron.isCancelled()) {
            event.setCanceled(true);
            return;
        }
        var to = hadron.getTo();
        if (hadron.getToLevel() != level && cause != TeleportCause.ENDER_PEARL) {
            // Chorus fruit and endermen search this level for ground and may step back; another level can't be
            // handed to that search, so the teleport is done here instead.
            event.setCanceled(true);
            EntityTeleports.redirect(entity, new EntityTeleports.Destination(hadron.getToLevel(), to.x, to.y, to.z,
                    hadron.getToYRot(), hadron.getToXRot()));
            return;
        }
        // NeoForge's event carries only a position in this level; a new level (a pearl's) or facing is applied by the
        // teleport method called next, which consumes the mark.
        event.setTargetX(to.x);
        event.setTargetY(to.y);
        event.setTargetZ(to.z);
        boolean turned = hadron.getToYRot() != entity.getYRot() || hadron.getToXRot() != entity.getXRot();
        EntityTeleports.mark(entity, cause, true, hadron.getToLevel() != level || turned
                ? new EntityTeleports.Destination(hadron.getToLevel(), to.x, to.y, to.z, hadron.getToYRot(),
                        hadron.getToXRot())
                : null);
    }

    private static void deathExp(LivingExperienceDropEvent event) {
        event.setDroppedExperience(EntityDeaths.holdExp(event.getEntity(), event.getDroppedExperience()));
    }

    private static void deathDrops(LivingDropsEvent event) {
        EntityDeaths.drops(event.getEntity(), event.getSource(), event.getDrops());
    }

    private static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) EntityKeptItems.giveBack(player);
    }
}
