package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.Place;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A living entity is about to be hurt. The actor is who hurt it: the attacker, the arrow (which remembers its
 * shooter), the machine or the mob; {@link Actor.Nature} for falls, drowning, lava and the like. Damage from an effect
 * (poison, wither) or from burning acts for whoever applied the effect or set the entity alight, even long after,
 * and {@link #getAppliedAt()} says where that happened. {@link #getSourceLocation()} says where the hit set out from:
 * the attacker's block, where an arrow was launched, where a blast went off. Cancel it and no damage, knockback or
 * weapon wear happens. {@link #setDamage} changes the amount before armour and other reductions.
 *
 * <p>Every hit fires it, whatever its amount, so protection can refuse any of them. An amount Minecraft was handed
 * that isn't a finite number of at least 0 (only a mod's mistake makes one) is reported as one, and that is what the
 * hit then deals: a negative or NaN amount as 0, an infinite one as {@code Float.MAX_VALUE}.
 */
@FiredBy(core = FULL)
public class EntityDamageEvent extends EntityEvent implements Cancellable {
    private final DamageSource source;
    private final @Nullable Place appliedAt;
    private final Optional<Place> sourceLocation;
    private float damage;
    private boolean cancelled;

    /**
     * For damage nothing applied earlier: a blow, a shot, a blast, a fall. Damage from an effect or fire, or from a
     * bridge's own lingering effect, uses the constructor taking where it was applied, so {@link #getAppliedAt()}
     * isn't empty.
     *
     * @param damage the amount before armour and other reductions: finite and at least 0. Report an infinite blow
     *     as {@code Float.MAX_VALUE} and a negative or NaN one as 0, and apply what was reported.
     * @param sourceLocation where the hit set out from, as {@link #getSourceLocation()} says: a turret's or a
     *     machine's own block, a gun's shooter's block, a projectile's {@link
     *     dev.iredsc.hadron.api.actor.Actors#origin}; {@link Place.Unknown} ({@link
     *     Place.Unknown.Reason#UNSEEN_ORIGIN}) when the hit came from somewhere nothing can say; {@code
     *     Optional.empty()} only for harm nothing sent, such as a fall or drowning
     */
    public EntityDamageEvent(LivingEntity entity, DamageSource source, float damage, Actor actor,
            Optional<Place> sourceLocation) {
        super(entity, actor);
        this.source = Objects.requireNonNull(source, "source");
        this.damage = DamageAmounts.checked(damage);
        this.appliedAt = null;
        this.sourceLocation = checkedSource(sourceLocation);
    }

    /**
     * Damage dealt by something applied earlier that acts on its own over time: an effect, burning, a bridge's
     * lingering curse. The actor is whoever applied it.
     *
     * @param damage as for the constructor without {@code appliedAt}
     * @param appliedAt where it was applied; {@link Place.Unknown} with {@link Place.Unknown.Reason#UNSEEN_ORIGIN}
     *     when nothing recorded where
     */
    public EntityDamageEvent(LivingEntity entity, DamageSource source, float damage, Actor actor, Place appliedAt) {
        super(entity, actor);
        this.source = Objects.requireNonNull(source, "source");
        this.damage = DamageAmounts.checked(damage);
        this.appliedAt = Objects.requireNonNull(appliedAt,
                "appliedAt; use the constructor taking sourceLocation for damage nothing applied earlier");
        this.sourceLocation = Optional.empty();
    }

    private static Optional<Place> checkedSource(Optional<Place> sourceLocation) {
        Objects.requireNonNull(sourceLocation, "sourceLocation; pass Optional.empty() for harm nothing sent (a fall), "
                + "or Optional.of(Place.Unknown.anywhere(Place.Unknown.Reason.UNSEEN_ORIGIN)) when nothing saw where "
                + "the hit came from");
        if (sourceLocation.orElse(null) instanceof Place.Nowhere nowhere) {
            throw new IllegalArgumentException("A hit sets out from somewhere in a world, not from " + nowhere
                    + ": pass the Spot it came from, or Place.Unknown.anywhere(Place.Unknown.Reason.UNSEEN_ORIGIN) "
                    + "when nothing saw where");
        }
        return sourceLocation;
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }

    /** The damage type and what dealt it; Minecraft's own. */
    public DamageSource getDamageSource() {
        return source;
    }

    /**
     * The entity that caused it: an arrow's shooter, a melee attacker. Empty for falls, lava and similar. Where it
     * stands now is not where the hit came from (the shooter may have walked away since): that is
     * {@link #getSourceLocation()}.
     */
    public Optional<Entity> getDamager() {
        return Optional.ofNullable(source.getEntity());
    }

    /** The weapon or tool it was dealt with, if one was. A new copy each call, so editing it changes nothing. */
    public Optional<ItemStack> getWeapon() {
        ItemStack weapon = source.getWeaponItem();
        return weapon == null || weapon.isEmpty() ? Optional.empty() : Optional.of(weapon.copy());
    }

    /**
     * Where the thing dealing this damage was applied, when it is something applied earlier that acts on its own: the
     * block the entity stood in when it was poisoned or set alight, which may be far from where it is hurt now.
     * {@link Place.Unknown} ({@link Place.Unknown.Reason#UNSEEN_ORIGIN}) when it is such damage but nothing recorded
     * where: an effect saved before Hadron recorded it, or one a mod added without going through
     * {@code addEffect}. Empty for every other hit: a blow, an arrow, a fall, lava. A hit dealt to the entity from
     * inside the effect's own act (a listener answering its poison with a blow) is part of what the effect did, and
     * says the same place.
     */
    public Optional<Place> getAppliedAt() {
        return Optional.ofNullable(appliedAt);
    }

    /**
     * Where the hit set out from, wherever its attacker is now, so a plugin can judge both ends of a hit:
     * <ul>
     *   <li>a blow, a guardian's beam, a warden's boom, a mob's or player's own attack: the block the attacker is in as
     *       it strikes;</li>
     *   <li>a projectile (an arrow, a trident, a fireball, a firework from a crossbow): the block it was launched
     *       from, its {@link dev.iredsc.hadron.api.actor.Actors#origin}, still that after the shooter moves, dies or
     *       logs off; {@link Place.Unknown} ({@link Place.Unknown.Reason#UNSEEN_ORIGIN}) for one nothing recorded
     *       entering the world;</li>
     *   <li>anything else that hits by itself (a creeper or TNT going off, evoker fangs, a falling anvil, a mod's
     *       vehicle): the block it is in as it hits;</li>
     *   <li>a blast with no entity (a bed in the Nether): where it went off;</li>
     *   <li>a hit with no entity named, dealt while a machine or an entity acts (a bridge's or an unbridged mod's
     *       turret): that machine's or entity's block;</li>
     *   <li>{@link Place.Unknown} when the hit came from somewhere nothing can say: a fake player's blow (it stands
     *       wherever its mod left it), a blast or a shot that names nothing, a cause that has gone.</li>
     * </ul>
     * Empty for harm nothing sent: a fall, drowning, starving, lava, the void, a command's or nature's own. Empty too
     * for damage from an effect or fire ticking ({@link #getAppliedAt()} says where that was applied).
     *
     * <p>Not one of the event's places: the hit happens where the victim is, and whether a hit from inside an area
     * counts against that area is a plugin's own rule (a safe zone's "no PvP if either end is in it"). Minecraft's
     * {@code getDamageSource().getSourcePosition()} is where an arrow is as it lands, not where it was shot from.
     */
    public Optional<Place> getSourceLocation() {
        return sourceLocation;
    }

    /** The damage before armour, enchantments and effects reduce it. */
    public float getDamage() {
        return damage;
    }

    /** Deal this much instead: finite and at least 0. */
    public void setDamage(float damage) {
        this.damage = DamageAmounts.checked(damage);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
