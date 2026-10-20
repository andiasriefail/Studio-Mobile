package com.example.studiomobile.workspace;

import android.content.Context;
import android.content.SharedPreferences;

/** The Debug Mode choice from the editor header, kept in app-private preferences. Off by default. */
public final class DebugModeSettings {
    private static final String PREFERENCES_NAME = "debug_mode_settings";
    private static final String KEY_ENABLED = "debug_mode_enabled";

    /** Shown to an AI client as the description of the Debug On/Off tool. */
    public static final String TOOL_DESCRIPTION =
        "Enable Debug Mode To See Live On Screen If An Application Crash Occurs,"
            + "If Debug Mode Still Cannot Detect It, Please Analyze Further.";

    private final SharedPreferences preferences;

    public DebugModeSettings(Context context) {
        this.preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() { return preferences.getBoolean(KEY_ENABLED, false); }

    public void setEnabled(boolean enabled) { preferences.edit().putBoolean(KEY_ENABLED, enabled).apply(); }
}
