package me.ash.resonance.playlist;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class PlaybackStatsManager {

  private static final String PREFS = "resonance_playback_stats";
  private static final String KEY_COUNTS = "play_counts";
  private static final String KEY_TIMESTAMPS = "play_timestamps";

  private static PlaybackStatsManager instance;
  private final SharedPreferences prefs;

  private PlaybackStatsManager(Context ctx) {
    prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public static PlaybackStatsManager get(Context ctx) {
    if (instance == null) instance = new PlaybackStatsManager(ctx.getApplicationContext());
    return instance;
  }

  /**
   * Call this every time a song starts playing. mediaId = String.valueOf(song.id)
   */
  public void recordPlay(String mediaId) {
    // Increment count
    JSONObject counts = getJson(KEY_COUNTS);
    JSONObject timestamps = getJson(KEY_TIMESTAMPS);
    try {
      counts.put(mediaId, counts.optInt(mediaId, 0) + 1);
      timestamps.put(mediaId, System.currentTimeMillis());
      prefs.edit()
              .putString(KEY_COUNTS, counts.toString())
              .putString(KEY_TIMESTAMPS, timestamps.toString())
              .apply();
    } catch (Exception ignored) {
    }
  }

  /**
   * Returns mediaIds sorted by play count descending, up to `limit`.
   */
  public List<String> getMostPlayed(int limit) {
    JSONObject counts = getJson(KEY_COUNTS);
    List<String[]> entries = new ArrayList<>(); // [mediaId, count]
    for (Iterator<String> it = counts.keys(); it.hasNext(); ) {
      String id = it.next();
      entries.add(new String[]{id, String.valueOf(counts.optInt(id, 0))});
    }
    Collections.sort(entries, (a, b) -> Integer.compare(
            Integer.parseInt(b[1]), Integer.parseInt(a[1])));
    List<String> result = new ArrayList<>();
    for (int i = 0; i < Math.min(limit, entries.size()); i++) {
      result.add(entries.get(i)[0]);
    }
    return result;
  }

  /**
   * Returns mediaIds sorted by last-played timestamp descending, up to `limit`.
   */
  public List<String> getRecentlyPlayed(int limit) {
    JSONObject timestamps = getJson(KEY_TIMESTAMPS);
    List<String[]> entries = new ArrayList<>();
    for (Iterator<String> it = timestamps.keys(); it.hasNext(); ) {
      String id = it.next();
      entries.add(new String[]{id, String.valueOf(timestamps.optLong(id, 0))});
    }
    Collections.sort(entries, (a, b) -> Long.compare(
            Long.parseLong(b[1]), Long.parseLong(a[1])));
    List<String> result = new ArrayList<>();
    for (int i = 0; i < Math.min(limit, entries.size()); i++) {
      result.add(entries.get(i)[0]);
    }
    return result;
  }

  public int getTotalPlayCount() {
    JSONObject counts = getJson(KEY_COUNTS);
    int total = 0;
    for (Iterator<String> it = counts.keys(); it.hasNext(); ) {
      total += counts.optInt(it.next(), 0);
    }
    return total;
  }

  private JSONObject getJson(String key) {
    try {
      return new JSONObject(prefs.getString(key, "{}"));
    } catch (Exception e) {
      return new JSONObject();
    }
  }
}