package dev.iredsc.hadron.regions;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * A protected box in one world, both corners included. Immutable: every change makes a new one, so a half-edited region
 * is never seen. Owners and members are disjoint; adding someone as one removes them from the other.
 *
 * @param priority where regions overlap, only those with the highest priority decide.
 * @param flags only what was set; anything missing uses the flag's default.
 */
public record Region(String id, ResourceLocation world, BoundingBox box, int priority, Set<UUID> owners,
        Set<UUID> members, Map<Flag, Boolean> flags) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    public Region {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException(idProblem(id));
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(box, "box");
        owners = Set.copyOf(owners);
        Set<UUID> rest = new LinkedHashSet<>(members);
        rest.removeAll(owners);
        members = Set.copyOf(rest);
        flags = flags.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(flags));
    }

    public static Region of(String id, ResourceLocation world, BoundingBox box) {
        return new Region(id, world, box, 0, Set.of(), Set.of(), Map.of());
    }

    /** Why {@code id} can't name a region, said so the reader knows the fix. */
    static String idProblem(String id) {
        return "\"" + id + "\" can't name a region: use 1 to 32 lowercase letters, digits, - or _, such as spawn or "
                + "market-1";
    }

    static boolean validId(String id) {
        return ID.matcher(id).matches();
    }

    public boolean contains(ResourceLocation world, BlockPos pos) {
        return this.world.equals(world) && box.isInside(pos);
    }

    public boolean isMember(UUID who) {
        return owners.contains(who) || members.contains(who);
    }

    /** What the region set for this flag, if it set anything. */
    public Optional<Boolean> flag(Flag flag) {
        return Optional.ofNullable(flags.get(flag));
    }

    public Region withBox(ResourceLocation world, BoundingBox box) {
        return new Region(id, world, box, priority, owners, members, flags);
    }

    public Region withPriority(int priority) {
        return new Region(id, world, box, priority, owners, members, flags);
    }

    /** {@code value} null clears the flag back to its default. */
    public Region withFlag(Flag flag, @Nullable Boolean value) {
        Map<Flag, Boolean> next = new EnumMap<>(Flag.class);
        next.putAll(flags);
        if (value == null) next.remove(flag);
        else next.put(flag, value);
        return new Region(id, world, box, priority, owners, members, next);
    }

    public Region withOwner(UUID who) {
        Set<UUID> next = new HashSet<>(owners);
        next.add(who);
        Set<UUID> rest = new HashSet<>(members);
        rest.remove(who);
        return new Region(id, world, box, priority, next, rest, flags);
    }

    public Region withMember(UUID who) {
        Set<UUID> rest = new HashSet<>(owners);
        rest.remove(who);
        Set<UUID> next = new HashSet<>(members);
        next.add(who);
        return new Region(id, world, box, priority, rest, next, flags);
    }

    /** Neither an owner nor a member any more. */
    public Region without(UUID who) {
        Set<UUID> o = new HashSet<>(owners);
        o.remove(who);
        Set<UUID> m = new HashSet<>(members);
        m.remove(who);
        return new Region(id, world, box, priority, o, m, flags);
    }
}
