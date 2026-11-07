package dev.iredsc.hadron.api.event;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Who fires an event: how much of it core covers, and what a mod's bridge must fire it for. Every concrete event in
 * {@code api} carries it, and the build checks it against the code: an event marked {@link Core#NONE} fails the build
 * if core constructs it (or a subclass of it), and one marked {@link Core#FULL} or {@link Core#PARTIAL} fails if core
 * never does. {@code docs/bridge-hooks.md}'s tables are generated from these ({@code ./gradlew bridgeHooks}).
 *
 * <p>A parent event counts as fired by core when core fires one of its leaves. The texts are table cells: short
 * phrases, no trailing period.
 *
 * <p>Kept in class files but not at run time: it documents and checks who fires an event, and nothing in a running
 * server asks. {@link Core#FULL} against {@link Core#PARTIAL} is a claim the build can't prove (it sees that core
 * fires the event, not that it fires on every path), so reviewers judge it.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface FiredBy {
    /** How much of the event core fires with no bridge. */
    Core core();

    /**
     * What core fires it for, such as {@code "hoppers, droppers"}. Needed for {@link Core#PARTIAL}, and for
     * {@link Core#FULL} when {@link #bridges()} says something; empty for {@link Core#NONE}.
     */
    String coreSees() default "";

    /**
     * What a bridge fires it for. For {@link Core#NONE}, what the mod does ("changes a claim or trust list"); for
     * {@link Core#PARTIAL}, the mod's paths core misses ("the mod's pipes and networks"). Needed for both; for
     * {@link Core#FULL}, optional advice to bridges.
     */
    String bridges() default "";

    /** What a bridge must fill in when it fires it. Needed for {@link Core#NONE} only. */
    String fillIn() default "";

    enum Core {
        /** Core fires it wherever it happens; a bridge only pushes the actor. */
        FULL,
        /** Core fires it for vanilla's paths; a bridge fires it for its mod's. */
        PARTIAL,
        /** Core never fires it: with no bridge, plugins hear nothing for it. */
        NONE
    }
}
