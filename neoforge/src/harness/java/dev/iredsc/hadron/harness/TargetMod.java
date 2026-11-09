package dev.iredsc.hadron.harness;

import net.neoforged.fml.common.Mod;

/** {@code hadron_test_target}: an empty mod the harness claims as a bridge, so self-tests see a real claim. */
@Mod(TargetMod.MOD_ID)
public final class TargetMod {
    public static final String MOD_ID = "hadron_test_target";
}
