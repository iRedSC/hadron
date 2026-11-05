package dev.iredsc.hadron.api;

/** Who has a node that no permissions plugin grants or denies. */
public enum Default {
    EVERYONE,
    /** Server operators, at any level. */
    OPS,
    NOBODY
}
