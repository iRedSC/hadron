package dev.iredsc.hadron.checks;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lets one of Hadron's own static fields hold state that {@code NoStaticMutableState} refuses. On a class, it exempts
 * the static fields the class declares, and on an enum, its constants wherever they're held. Only Hadron's own builds
 * accept it; a plugin keeps state on its plugin instance or in {@code PersistentData}, so a reload never sees the old
 * one.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface StaticState {
    /**
     * Why this state must be static and what keeps it from going stale, e.g. "cleared in Hadron's server-stop hook".
     */
    String reason();
}
