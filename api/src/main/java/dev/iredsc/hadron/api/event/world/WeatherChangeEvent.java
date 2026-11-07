package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;

/**
 * The weather players see is about to change: the natural cycle ({@link Actor.Nature}), sleeping through a storm, a
 * command, a ritual or a weather machine. Fires only when what players see changes. Cancel it and the weather stays
 * as it was; the natural cycle then waits for its next turn.
 */
public class WeatherChangeEvent extends WorldEvent implements Cancellable {
    /** What players see. */
    public enum Weather {
        CLEAR, RAIN, THUNDER;

        /** From the level's two flags; thunder without rain looks clear. */
        public static Weather of(boolean raining, boolean thundering) {
            return !raining ? CLEAR : thundering ? THUNDER : RAIN;
        }
    }

    private final Weather from;
    private final Weather to;
    private boolean cancelled;

    public WeatherChangeEvent(Actor actor, ServerLevel level, Weather from, Weather to) {
        super(actor, level, null);
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
    }

    public Weather getFrom() {
        return from;
    }

    public Weather getTo() {
        return to;
    }

    /** Whether it will be raining (or thundering) afterwards, as Bukkit's {@code toWeatherState()}. */
    public boolean toWeatherState() {
        return to != Weather.CLEAR;
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
