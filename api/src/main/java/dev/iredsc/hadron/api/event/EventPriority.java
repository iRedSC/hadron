package dev.iredsc.hadron.api.event;

/** When a handler runs, first to last. MONITOR is for looking only: it sees the outcome and must not change it. */
public enum EventPriority {
    LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR
}
