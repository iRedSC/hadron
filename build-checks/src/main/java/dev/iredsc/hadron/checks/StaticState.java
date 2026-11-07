package dev.iredsc.hadron.checks;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lets one of Hadron's own static fields hold mutable state, which {@code NoStaticMutableState} otherwise refuses. On
 * a class, it vouches that the class's instances never change (say, final fields holding stateless functions, which
 * the check can't see through), so a static final field holding one is allowed; this works within the module that
 * declares the class, since the annotation isn't kept in class files.
 * Only Hadron's code ({@code dev.iredsc.hadron}) may use it; a plugin keeps state on its plugin instance or in
 * {@code PersistentData}, so a reload never sees the old one.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface StaticState {
    /**
     * Why this state must be static and what keeps it from going stale, e.g. "cleared in Hadron's server-stop hook";
     * on a class, why its instances never change.
     */
    String reason();
}
