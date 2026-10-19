package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;

/** The launcher's own settings, entered under Settings > Vibe Launcher settings and kept on the device. */
final class VibeSettings {

    private static final String FILE = "vibe_settings";
    private static final String TMDB_KEY = "tmdbKey";
    private static final String PLEX_TOKEN = "plexToken";

    private final SharedPreferences prefs;

    VibeSettings(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** TMDB API key (v3) or read access token, or "" when not set. */
    String tmdbKey() {
        return prefs.getString(TMDB_KEY, "");
    }

    /** Plex account token (X-Plex-Token), or "" when not set. */
    String plexToken() {
        return prefs.getString(PLEX_TOKEN, "");
    }

    void save(String tmdbKey, String plexToken) {
        prefs.edit().putString(TMDB_KEY, tmdbKey.trim()).putString(PLEX_TOKEN, plexToken.trim()).apply();
    }
}
