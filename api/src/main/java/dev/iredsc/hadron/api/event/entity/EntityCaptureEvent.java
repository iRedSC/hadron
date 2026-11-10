package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * An entity is about to be stored somewhere out of the world: a fish or axolotl scooped into a water bucket, a mob
 * caught in a mob catcher or jar, a mob picked up by Carry On. Cancel it and the entity stays in the world and the
 * player keeps what they held. Its cause is always {@link Cause#CAPTURED}; {@link #getStorage()} says where it goes.
 *
 * <p>Letting it out again is an {@link EntitySpawnEvent}, whose spawn reason for a bucket is {@code BUCKET}.
 *
 * <p><b>Core fires it for water buckets</b>, including a mod's own fish that use Minecraft's bucket code. Mob
 * catchers and carrying need their mod's bridge.
 */
@FiredBy(core = PARTIAL,
        coreSees = "water buckets, for vanilla's and mods' fish, axolotls and tadpoles",
        bridges = "the mod storing an entity in an item, a block or a player's arms (mob catchers, jars, Carry On)")
public class EntityCaptureEvent extends EntityRemoveEvent {
    /** Where a captured entity goes. */
    public sealed interface Storage {
        /** Into an item, such as a bucket of cod or a filled mob catcher. */
        record Item(ItemStack stack) implements Storage {
            /** {@code stack} is copied: the event says what the item will be, and changing it changes nothing. */
            public Item {
                Objects.requireNonNull(stack, "stack; pass the item the entity will be stored in");
                if (stack.isEmpty()) {
                    throw new IllegalArgumentException("An empty stack can't hold an entity: pass the item it will "
                            + "be stored in, such as the filled mob catcher");
                }
                stack = stack.copy();
            }

            /** A copy of the item it will be stored in. */
            @Override
            public ItemStack stack() {
                return stack.copy();
            }
        }

        /** Into a block, such as a mob jar or a spawner-like trap. */
        record Block(ServerLevel level, BlockPos pos) implements Storage {
            public Block {
                Objects.requireNonNull(level, "level");
                pos = Objects.requireNonNull(pos, "pos").immutable();
            }
        }

        /** Carried by another entity, such as a player holding a mob with Carry On. */
        record Carried(Entity carrier) implements Storage {
            public Carried {
                Objects.requireNonNull(carrier, "carrier; pass the entity that will carry it");
            }
        }
    }

    private final Storage storage;

    public EntityCaptureEvent(Entity entity, Storage storage, Actor actor) {
        super(entity, actor);
        this.storage = Objects.requireNonNull(storage,
                "storage; say where the entity goes, such as new Storage.Item(stack)");
    }

    /** Where the entity will be stored. */
    public Storage getStorage() {
        return storage;
    }

    /**
     * Where the entity is, then where it is stored: the block, or where its carrier is. An item it goes into adds no
     * place of its own: the item is made here, and wherever it goes next is that event's.
     */
    @Override
    public Places getPlaces() {
        Places own = super.getPlaces();
        return switch (getStorage()) {
            case Storage.Block b -> own.and(Place.Spot.at(b.level(), b.pos()));
            case Storage.Carried c -> own.and(Place.of(c.carrier()));
            case Storage.Item i -> own;
        };
    }
}
