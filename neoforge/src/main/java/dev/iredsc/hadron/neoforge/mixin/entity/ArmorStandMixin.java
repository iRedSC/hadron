package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.neoforge.EntityActors;
import dev.iredsc.hadron.neoforge.EntityEvents;
import dev.iredsc.hadron.neoforge.EntityHandling;
import dev.iredsc.hadron.neoforge.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A stand's right-clicks and hits. Every right-click that moves an item on or off a stand goes through
 * {@code swapItem}; asked before it changes. {@code ArmorStand.hurt} replaces {@code LivingEntity.hurt} without
 * calling it, so NeoForge's damage event, which fires {@link EntityDamageEvent} for every other living entity, never
 * fires for a stand: a hit that knocks it over, a blast, fire. Cancelled, the stand takes nothing and stays.
 */
@Mixin(ArmorStand.class)
abstract class ArmorStandMixin {
    @Shadow
    private int disabledSlots;

    @Inject(method = "swapItem", at = @At("HEAD"), cancellable = true)
    private void hadron$swap(Player player, EquipmentSlot slot, ItemStack hand, InteractionHand which,
            CallbackInfoReturnable<Boolean> cir) {
        if (EntityHandling.refuseStandSwap((ArmorStand) (Object) this, disabledSlots, player, slot, hand, which)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void hadron$hurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        ArmorStand self = (ArmorStand) (Object) this;
        // Only where vanilla would go on to act: /kill and the void can't be refused, and a marker or an invisible
        // stand ignores hits anyway.
        if (!(self.level() instanceof ServerLevel) || self.isRemoved() || self.isMarker() || self.isInvisible()
                || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || self.isInvulnerableTo(source)
                || !Hooks.listens(EntityDamageEvent.class)) return;
        // Every amount fires it, a negative or NaN one reported as 0, since vanilla breaks a stand on any hit. A
        // stand breaks by hits counted, not health, so what a listener sets the amount to changes nothing.
        if (Hooks.post(new EntityDamageEvent(self, source, EntityEvents.reportedDamage(amount),
                EntityActors.damage(source, self))).isCancelled()) {
            cir.setReturnValue(false);
        }
    }
}
