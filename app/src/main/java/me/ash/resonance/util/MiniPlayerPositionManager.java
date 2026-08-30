package me.ash.resonance.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import me.ash.resonance.ui.PlayerPosition;

public class MiniPlayerPositionManager {

  private static final String PREF_FILE = "resonance_prefs";
  private static final String PREF_POSITION = "player_position";
  private static MiniPlayerPositionManager instance;
  private final MutableLiveData<PlayerPosition> positionLive = new MutableLiveData<>();
  private final SharedPreferences prefs;

  private MiniPlayerPositionManager(Context ctx) {
    prefs = ctx.getApplicationContext()
            .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    positionLive.setValue(loadPosition());
  }

  public static synchronized MiniPlayerPositionManager get(Context ctx) {
    if (instance == null) instance = new MiniPlayerPositionManager(ctx);
    return instance;
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
    positionLive.setValue(pos);
  }

  private PlayerPosition loadPosition() {
    String name = prefs.getString(PREF_POSITION, PlayerPosition.FLOATING.name());
    try {
      return PlayerPosition.valueOf(name);
    } catch (Exception e) {
      return PlayerPosition.FLOATING;
    }
  }
}