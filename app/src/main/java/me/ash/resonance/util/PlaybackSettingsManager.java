package me.ash.resonance.util;

import android.content.Context;
import android.content.SharedPreferences;

public class PlaybackSettingsManager {
    private static final String PREF_NAME = "playback_settings";
    private static final String KEY_CROSSFADE_DURATION = "crossfade_duration";
    private static PlaybackSettingsManager instance;
    private final SharedPreferences prefs;

    private PlaybackSettingsManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized PlaybackSettingsManager get(Context context) {
        if (instance == null) {
            instance = new PlaybackSettingsManager(context);
        }
        return instance;
    }

    public int getCrossfadeDuration() {
        return prefs.getInt(KEY_CROSSFADE_DURATION, 0); // Default 0 (off)
    }

    public void setCrossfadeDuration(int seconds) {
        prefs.edit().putInt(KEY_CROSSFADE_DURATION, seconds).apply();
    }
}
