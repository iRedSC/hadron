package dev.iredsc.hadron.common;

/** {@code &} color codes to Minecraft's section-sign codes, as Bukkit's ChatColor.translateAlternateColorCodes does. */
final class Colors {
    private Colors() {}

    static String translate(String text) {
        char[] c = text.toCharArray();
        for (int i = 0; i < c.length - 1; i++) {
            if (c[i] == '&' && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(c[i + 1]) >= 0) {
                c[i] = '§';
                c[i + 1] = Character.toLowerCase(c[i + 1]);
            }
        }
        return new String(c);
    }
}
