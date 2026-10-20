package com.example.studiomobile.workspace;

import android.content.Context;
import android.content.SharedPreferences;

/** User choices for the MCP feature, kept in app-private preferences. */
public final class McpSettings {
    private static final String PREFERENCES_NAME = "mcp_settings";
    private static final String KEY_READ_ONLY = "read_only";
    private static final String KEY_RELAY_URL = "relay_url";
    private static final String KEY_RELAY_TOKEN = "relay_token";
    private static final String KEY_RELAY_ENABLED = "relay_enabled";

    private final SharedPreferences preferences;

    public McpSettings(Context context) {
        this.preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** Read-only is the default: an AI must be allowed to change files on purpose, not by accident. */
    public boolean isReadOnly() { return preferences.getBoolean(KEY_READ_ONLY, true); }

    public void setReadOnly(boolean readOnly) { preferences.edit().putBoolean(KEY_READ_ONLY, readOnly).apply(); }

    /** The relay address the user typed. There is no built-in default: every user brings their own relay. */
    public String getRelayUrl() { return preferences.getString(KEY_RELAY_URL, ""); }

    public void setRelayUrl(String url) { preferences.edit().putString(KEY_RELAY_URL, url == null ? "" : url.trim()).apply(); }

    /** Optional phone access token for relays started with PHONE_TOKEN. Empty when the relay has none. */
    public String getRelayToken() { return preferences.getString(KEY_RELAY_TOKEN, ""); }

    public void setRelayToken(String token) { preferences.edit().putString(KEY_RELAY_TOKEN, token == null ? "" : token.trim()).apply(); }

    /** True while the user wants the phone connected, so the service can resume after Android restarts it. */
    public boolean isRelayEnabled() { return preferences.getBoolean(KEY_RELAY_ENABLED, false); }

    public void setRelayEnabled(boolean enabled) { preferences.edit().putBoolean(KEY_RELAY_ENABLED, enabled).apply(); }
}
