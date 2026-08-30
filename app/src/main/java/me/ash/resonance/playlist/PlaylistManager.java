package me.ash.resonance.playlist;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PlaylistManager {

  public static final String SMART_MOST_PLAYED = "__most_played__";
  public static final String SMART_RECENTLY_PLAYED = "__recently_played__";
  public static final String SMART_RECENTLY_ADDED = "__recently_added__";
  public static final String SMART_DOWNLOADS = "__downloads__";
  private static final String PREFS = "resonance_playlists";
  private static final String KEY_FAVOURITES = "favourites";
  private static final String KEY_PLAYLISTS = "playlists";
  private static final String KEY_SORT = "playlist_sort";

  private static PlaylistManager instance;
  private final SharedPreferences prefs;

  private PlaylistManager(Context ctx) {
    prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public static PlaylistManager get(Context ctx) {
    if (instance == null) instance = new PlaylistManager(ctx.getApplicationContext());
    return instance;
  }

  // ── Favourites ────────────────────────────────────────────────────────

  public boolean isFavourite(String mediaId) {
    return getFavouriteIds().contains(mediaId);
  }

  /**
   * Returns the new state (true = now favourite)
   */
  public boolean toggleFavourite(String mediaId) {
    List<String> favs = getFavouriteIds();
    boolean nowFav;
    if (favs.contains(mediaId)) {
      favs.remove(mediaId);
      nowFav = false;
    } else {
      favs.add(0, mediaId);
      nowFav = true;
    }
    saveFavouriteIds(favs);
    return nowFav;
  }

  /**
   * Moves a song from one index to another within a playlist.
   */
  public void reorderPlaylist(String name, int fromIndex, int toIndex) {
    Map<String, List<String>> all = getAllPlaylists();
    if (!all.containsKey(name)) return;
    List<String> ids = all.get(name);
    if (fromIndex < 0 || toIndex < 0 || fromIndex >= ids.size() || toIndex >= ids.size()) return;
    String moved = ids.remove(fromIndex);
    ids.add(toIndex, moved);
    all.put(name, ids);
    savePlaylists(all);
  }

  public void appendSongsToPlaylist(String playlistName, List<String> mediaIds) {
    Map<String, List<String>> all = getAllPlaylists();
    List<String> ids = all.getOrDefault(playlistName, new ArrayList<>());
    for (String id : mediaIds) {
      if (!ids.contains(id)) ids.add(id);
    }
    all.put(playlistName, ids);
    savePlaylists(all);
  }

  public void saveQueueAsPlaylist(String name, List<String> ids) {
    Map<String, List<String>> all = getAllPlaylists();
    all.put(name, new ArrayList<>(ids));
    savePlaylists(all);
  }

  /**
   * Duplicates a playlist with " (Copy)" appended to the name.
   */
  public void duplicatePlaylist(String name) {
    Map<String, List<String>> all = getAllPlaylists();
    if (!all.containsKey(name)) return;
    String copyName = name + " (Copy)";
    // Ensure unique name
    int suffix = 2;
    while (all.containsKey(copyName)) copyName = name + " (Copy " + suffix++ + ")";
    all.put(copyName, new ArrayList<>(all.get(name)));
    savePlaylists(all);
  }

  public List<String> getFavouriteIds() {
    List<String> ids = new ArrayList<>();
    try {
      JSONArray arr = new JSONArray(prefs.getString(KEY_FAVOURITES, "[]"));
      for (int i = 0; i < arr.length(); i++) ids.add(arr.getString(i));
    } catch (Exception ignored) {
    }
    return ids;
  }

  private void saveFavouriteIds(List<String> ids) {
    prefs.edit().putString(KEY_FAVOURITES, new JSONArray(ids).toString()).apply();
  }

  // ── Named playlists ───────────────────────────────────────────────────

  /**
   * Returns map of playlistName → list of mediaIds, insertion-ordered
   */
  public Map<String, List<String>> getAllPlaylists() {
    Map<String, List<String>> map = new LinkedHashMap<>();
    try {
      JSONObject obj = new JSONObject(prefs.getString(KEY_PLAYLISTS, "{}"));
      for (java.util.Iterator<String> it = obj.keys(); it.hasNext(); ) {
        String name = it.next();
        JSONArray arr = obj.getJSONArray(name);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) ids.add(arr.getString(i));
        map.put(name, ids);
      }
    } catch (Exception ignored) {
    }
    return map;
  }

  public void createPlaylist(String name) {
    Map<String, List<String>> all = getAllPlaylists();
    if (!all.containsKey(name)) {
      all.put(name, new ArrayList<>());
      savePlaylists(all);
    }
  }

  public void setPlaylistSort(String playlistName, String sortMode) {
    prefs.edit().putString(KEY_SORT + "_" + playlistName, sortMode).apply();
  }

  public String getPlaylistSort(String playlistName) {
    return prefs.getString(KEY_SORT + "_" + playlistName, "NONE");
  }

  public void addToPlaylist(String playlistName, String mediaId) {
    Map<String, List<String>> all = getAllPlaylists();
    List<String> ids = all.getOrDefault(playlistName, new ArrayList<>());
    if (!ids.contains(mediaId)) ids.add(mediaId);
    all.put(playlistName, ids);
    savePlaylists(all);
  }

  public void removeFromPlaylist(String playlistName, String mediaId) {
    Map<String, List<String>> all = getAllPlaylists();
    if (all.containsKey(playlistName)) {
      all.get(playlistName).remove(mediaId);
      savePlaylists(all);
    }
  }

  public void deletePlaylist(String name) {
    Map<String, List<String>> all = getAllPlaylists();
    all.remove(name);
    savePlaylists(all);
  }

  public boolean isInPlaylist(String playlistName, String mediaId) {
    Map<String, List<String>> all = getAllPlaylists();
    return all.containsKey(playlistName) && all.get(playlistName).contains(mediaId);
  }

  /**
   * Renames a playlist while preserving its position in the list and all its songs.
   * No-op if oldName doesn't exist or newName is already taken.
   */
  public void renamePlaylist(String oldName, String newName) {
    Map<String, List<String>> all = getAllPlaylists();
    if (!all.containsKey(oldName) || all.containsKey(newName)) return;
    // Rebuild map to preserve insertion order
    Map<String, List<String>> rebuilt = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> e : all.entrySet()) {
      rebuilt.put(e.getKey().equals(oldName) ? newName : e.getKey(), e.getValue());
    }
    savePlaylists(rebuilt);
  }

  private void savePlaylists(Map<String, List<String>> map) {
    try {
      JSONObject obj = new JSONObject();
      for (Map.Entry<String, List<String>> e : map.entrySet())
        obj.put(e.getKey(), new JSONArray(e.getValue()));
      prefs.edit().putString(KEY_PLAYLISTS, obj.toString()).apply();
    } catch (Exception ignored) {
    }
  }
}