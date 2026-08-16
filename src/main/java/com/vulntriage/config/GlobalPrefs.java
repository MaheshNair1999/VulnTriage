package com.vulntriage.config;

import java.util.prefs.Preferences;

/**
 * Global (OS-level) preferences using java.util.prefs.Preferences.
 *
 * Stored in the OS registry (Windows) or ~/.java/.userPrefs (Linux/Mac),
 * NOT in any per-project SQLite database. This ensures settings like dark
 * mode persist across project switches.
 */
public final class GlobalPrefs {

    private static final Preferences PREFS =
        Preferences.userRoot().node("com/vulntriage/app");

    private static final String KEY_DARK_MODE = "dark_mode";

    private GlobalPrefs() {}

    public static boolean isDarkMode() {
        return PREFS.getBoolean(KEY_DARK_MODE, false);
    }

    public static void saveDarkMode(boolean dark) {
        try {
            PREFS.putBoolean(KEY_DARK_MODE, dark);
            PREFS.flush();
        } catch (Exception ignored) {}
    }
}
