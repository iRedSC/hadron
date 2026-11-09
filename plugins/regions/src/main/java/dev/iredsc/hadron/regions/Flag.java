package dev.iredsc.hadron.regions;

import java.util.Locale;
import java.util.Optional;

/**
 * What a region decides. Each flag has what it does for outsiders when nobody set it, and whether owners and members
 * are exempt: a region keeps outsiders from building and using, lets them walk in, and leaves fighting alone.
 */
public enum Flag {
    /** Placing, breaking or changing blocks, item frames, paintings and armor stands. */
    BUILD(false, true),
    /** Right-clicking blocks and entities: doors, chests, levers, villagers. */
    USE(false, true),
    /** Walking or driving in. */
    ENTRY(true, true),
    /** Players hurting players. Binds members too, so a region set to deny is a safe zone for everyone. */
    PVP(true, false);

    /** What an outsider may do when the region doesn't set it. */
    final boolean outsiderDefault;
    /** Whether owners and members may always do it, whatever the region sets. */
    final boolean membersExempt;

    Flag(boolean outsiderDefault, boolean membersExempt) {
        this.outsiderDefault = outsiderDefault;
        this.membersExempt = membersExempt;
    }

    /** As typed in commands and saved in regions.yml. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Flag> parse(String key) {
        for (Flag f : values()) {
            if (f.key().equals(key.toLowerCase(Locale.ROOT))) return Optional.of(f);
        }
        return Optional.empty();
    }
}
