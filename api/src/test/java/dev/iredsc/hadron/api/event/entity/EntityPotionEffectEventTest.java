package dev.iredsc.hadron.api.event.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import dev.iredsc.hadron.api.event.entity.EntityPotionEffectEvent.Change;
import dev.iredsc.hadron.test.WithMinecraft;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.junit.jupiter.api.Test;

/** A potion event's instances are whole copies: everything Minecraft saves, the hidden weaker effect included. */
@WithMinecraft
class EntityPotionEffectEventTest {
    /** Speed II for 20 ticks over Speed I for 200: when II runs out, the hidden I resumes. */
    private static MobEffectInstance speedOverWeakerSpeed() {
        MobEffectInstance speed = new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 0);
        speed.update(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20, 1));
        return speed;
    }

    @Test
    void everyCopyKeepsTheHiddenWeakerEffect() {
        MobEffectInstance speed = speedOverWeakerSpeed();
        Change.Removed removed = new Change.Removed(speed);
        Change.Added added = new Change.Added(speed);
        Change.Changed changed = new Change.Changed(speed, speed);

        assertEquals(speed.save(), removed.old().save(), "Removed.old");
        assertEquals(speed.save(), added.added().save(), "Added.added");
        assertEquals(speed.save(), changed.old().save(), "Changed.old");
        assertEquals(speed.save(), changed.added().save(), "Changed.added");
        assertNotSame(removed.old(), removed.old());
    }

    @Test
    void anEditToTheCallersInstanceOrAReadCopyReachesNoOne() {
        MobEffectInstance speed = speedOverWeakerSpeed();
        Change.Removed removed = new Change.Removed(speed);
        String saved = speed.save().toString();

        speed.update(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 9000, 4));
        removed.old().update(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 9000, 4));

        assertEquals(saved, removed.old().save().toString());
    }
}
