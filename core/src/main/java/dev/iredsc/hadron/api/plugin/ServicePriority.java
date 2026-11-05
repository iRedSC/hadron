package dev.iredsc.hadron.api.plugin;

/** Which provider {@link ServicesManager#load} picks when several plugins provide the same service. */
public enum ServicePriority {
    Lowest, Low, Normal, High, Highest
}
