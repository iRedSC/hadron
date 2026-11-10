package dev.iredsc.hadron.api.event.entity;

/** The one rule for a hit's amount, shared by the damage events' constructors and setters. */
final class DamageAmounts {
    private DamageAmounts() {}

    static float checked(float damage) {
        if (!(damage >= 0) || Float.isInfinite(damage)) {
            throw new IllegalArgumentException("Damage must be a finite number of at least 0, got " + damage
                    + "; to stop the damage use setCancelled(true), and for a blow that should kill anything use "
                    + "Float.MAX_VALUE");
        }
        return damage;
    }
}
