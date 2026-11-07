package dev.iredsc.hadron.api.event.resource;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;

import static dev.iredsc.hadron.api.event.FiredBy.Core.NONE;

/**
 * A holder is about to turn one resource into another in one step: fuel to heat, heat to steam, mana to energy.
 * {@link #getResource()} and the old and new amounts are the input; {@link #getOutput()} is what it becomes. Cancel
 * it and neither changes. Who fires it: see {@link ResourceEvent}.
 */
@FiredBy(core = NONE,
        bridges = "turns one resource into another in one step (fuel to heat, heat to steam, mana to energy)",
        fillIn = "resource id and unit, holder, old and new amount of the input, and the output")
public class ResourceConvertEvent extends ResourceChangeEvent {
    private final Resource output;
    private final long outputAmount;

    public ResourceConvertEvent(Actor actor, ResourceHolder holder, Resource input, long oldInput, long newInput,
            Resource output, long outputAmount) {
        super(actor, input, holder, oldInput, newInput);
        this.output = Objects.requireNonNull(output, "output");
        if (newInput > oldInput) {
            throw new IllegalArgumentException("Converting uses up " + input.id() + ", but it goes " + oldInput
                    + " -> " + newInput + "; the output is getOutput()");
        }
        if (outputAmount < 0) {
            throw new IllegalArgumentException("Output amount can't be negative, got " + outputAmount);
        }
        this.outputAmount = outputAmount;
    }

    /** What the input becomes. */
    public Resource getOutput() {
        return output;
    }

    /** How much of {@link #getOutput()} is made. */
    public long getOutputAmount() {
        return outputAmount;
    }
}
