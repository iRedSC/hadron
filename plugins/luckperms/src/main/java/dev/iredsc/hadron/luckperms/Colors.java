package dev.iredsc.hadron.luckperms;

import org.jspecify.annotations.Nullable;

/** LuckPerms prefixes are written with {@code &} codes, as on Bukkit; Minecraft draws section-sign codes. */
final class Colors {
    private static final String CODES = "0123456789abcdefklmnorABCDEFKLMNOR";

    private Colors() {}

    /** {@code &c[Admin]} to {@code §c[Admin]}; empty for null. A lone {@code &} or one before another letter stays. */
    static String translate(@Nullable String text) {
        if (text == null) return "";
        char[] c = text.toCharArray();
        for (int i = 0; i < c.length - 1; i++) {
            if (c[i] == '&' && CODES.indexOf(c[i + 1]) >= 0) {
                c[i] = '§';
                c[i + 1] = Character.toLowerCase(c[i + 1]);
            }
        }
        return new String(c);
    }
}
