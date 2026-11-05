package dev.iredsc.hadron.api.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method of a {@link Listener} that hears one event: it takes exactly that event (or a parent class of it)
 * and returns nothing. Any visibility; may be static.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EventHandler {
    EventPriority priority() default EventPriority.NORMAL;

    /** Skip events another handler already cancelled. */
    boolean ignoreCancelled() default false;
}
