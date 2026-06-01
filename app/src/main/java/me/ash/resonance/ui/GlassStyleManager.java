package me.ash.resonance.ui;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public class GlassStyleManager {

  private static final String PREF_FILE = "resonance_prefs";
  private static final String PREF_KEY = "glass_style";
  private static final String PREF_POSITION = "player_position";
  private static GlassStyleManager instance;
  private final MutableLiveData<GlassStyle> styleLive = new MutableLiveData<>();
  private final MutableLiveData<PlayerPosition> positionLive = new MutableLiveData<>();
  private final SharedPreferences prefs;

  private GlassStyleManager(Context ctx) {
    prefs = ctx.getApplicationContext()
            .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    styleLive.setValue(load());
    positionLive.setValue(loadPosition());
  }

  public static synchronized GlassStyleManager get(Context ctx) {
    if (instance == null) instance = new GlassStyleManager(ctx);
    return instance;
  }

  public LiveData<GlassStyle> observe() {
    return styleLive;
  }

  public GlassStyle current() {
    GlassStyle v = styleLive.getValue();
    return v != null ? v : GlassStyle.FROSTED;
  }

  public LiveData<PlayerPosition> observePosition() {
    return positionLive;
  }

  public PlayerPosition currentPosition() {
    PlayerPosition v = positionLive.getValue();
    return v != null ? v : PlayerPosition.FLOATING;
  }

  public void setPosition(PlayerPosition pos) {
    prefs.edit().putString(PREF_POSITION, pos.name()).apply();
    positionLive.postValue(pos);
  }

  private PlayerPosition loadPosition() {
    String name = prefs.getString(PREF_POSITION, PlayerPosition.FLOATING.name());
    try {
      return PlayerPosition.valueOf(name);
    } catch (Exception e) {
      return PlayerPosition.FLOATING;
    }
  }

  /**
   * Save + notify all observers immediately on the main thread.
   */
  public void set(GlassStyle style) {
    prefs.edit().putString(PREF_KEY, style.name()).apply();
    styleLive.postValue(style);
  }

  private GlassStyle load() {
    String name = prefs.getString(PREF_KEY, GlassStyle.FROSTED.name());
    try {
      return GlassStyle.valueOf(name);
    } catch (Exception e) {
      return GlassStyle.FROSTED;
    }
  }
}