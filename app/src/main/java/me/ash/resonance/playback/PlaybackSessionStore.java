package me.ash.resonance.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class PlaybackSessionStore {

  private static final String PREFS = "playback_session";

  private static final String KEY_QUEUE = "queue";
  private static final String KEY_INDEX = "index";
  private static final String KEY_POSITION = "position";
  private static final String KEY_SHUFFLE = "shuffle";
  private static final String KEY_REPEAT = "repeat";

  public void save(Context ctx, Player player, boolean shuffle, int repeatMode) {
    try {
      JSONArray arr = new JSONArray();

      for (int i = 0; i < player.getMediaItemCount(); i++) {
        MediaItem item = player.getMediaItemAt(i);

        JSONObject o = new JSONObject();
        o.put("id", item.mediaId);

        if (item.localConfiguration != null)
          o.put("uri", item.localConfiguration.uri.toString());

        if (item.requestMetadata.mediaUri != null)
          o.put("requestUri", item.requestMetadata.mediaUri.toString());

        arr.put(o);
      }

      ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
              .edit()
              .putString(KEY_QUEUE, arr.toString())
              .putInt(KEY_INDEX, player.getCurrentMediaItemIndex())
              .putLong(KEY_POSITION, player.getCurrentPosition())
              .putBoolean(KEY_SHUFFLE, shuffle)
              .putInt(KEY_REPEAT, repeatMode)
              .apply();

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public Session load(Context ctx) {
    SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

    String json = p.getString(KEY_QUEUE, null);
    if (json == null) return null;

    try {
      JSONArray arr = new JSONArray(json);
      List<MediaItem> items = new ArrayList<>();

      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.getJSONObject(i);

        MediaItem.Builder b = new MediaItem.Builder()
                .setMediaId(o.getString("id"));

        if (o.has("uri"))
          b.setUri(Uri.parse(o.getString("uri")));

        if (o.has("requestUri")) {
          b.setRequestMetadata(
                  new MediaItem.RequestMetadata.Builder()
                          .setMediaUri(Uri.parse(o.getString("requestUri")))
                          .build()
          );
        }

        items.add(b.build());
      }

      return new Session(
              items,
              p.getInt(KEY_INDEX, 0),
              p.getLong(KEY_POSITION, 0),
              p.getBoolean(KEY_SHUFFLE, false),
              p.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF)
      );

    } catch (Exception e) {
      return null;
    }
  }

  public static class Session {
    public final List<MediaItem> items;
    public final int index;
    public final long position;
    public final boolean shuffle;
    public final int repeat;

    public Session(List<MediaItem> items, int index, long position, boolean shuffle, int repeat) {
      this.items = items;
      this.index = index;
      this.position = position;
      this.shuffle = shuffle;
      this.repeat = repeat;
    }
  }
}