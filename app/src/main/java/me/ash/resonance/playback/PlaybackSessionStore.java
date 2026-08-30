package me.ash.resonance.playback;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/**
 * Consolidated store for playback sessions.
 * Replaces both the old PlaybackSessionStore and QueueStore.
 */
public class PlaybackSessionStore {

  private static final String PREFS = "playback_session";
  private static final String KEY_QUEUE = "queue";
  private static final String KEY_INDEX = "index";
  private static final String KEY_POSITION = "position";
  private static final String KEY_SHUFFLE = "shuffle";
  private static final String KEY_REPEAT = "repeat";

  private final Gson gson = new Gson();

  public void save(Context ctx, Player player) {
    save(ctx, player, player.getShuffleModeEnabled(), player.getRepeatMode());
  }

  public void save(Context ctx, Player player, boolean shuffle, int repeatMode) {
    // Capture state immediately on the calling thread to ensure consistency
    int mediaItemCount = player.getMediaItemCount();
    List<SerializedMediaItem> items = new ArrayList<>(mediaItemCount);
    for (int i = 0; i < mediaItemCount; i++) {
      MediaItem item = player.getMediaItemAt(i);
      items.add(SerializedMediaItem.fromMediaItem(item));
    }
    int currentIndex = player.getCurrentMediaItemIndex();
    long currentPosition = player.getCurrentPosition();

    // Perform heavy JSON serialization and Disk I/O on a background thread
    new Thread(() -> {
      try {
        String json = gson.toJson(items);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_QUEUE, json)
                .putInt(KEY_INDEX, currentIndex)
                .putLong(KEY_POSITION, currentPosition)
                .putBoolean(KEY_SHUFFLE, shuffle)
                .putInt(KEY_REPEAT, repeatMode)
                .apply();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }).start();
  }

  public Session load(Context ctx) {
    SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

    String json = p.getString(KEY_QUEUE, null);
    if (json == null) {
      // Fallback to old QueueStore location if present
      json = ctx.getSharedPreferences("queue_store", Context.MODE_PRIVATE)
              .getString("current_queue", null);
      if (json == null) return null;
    }

    try {
      List<SerializedMediaItem> serialized = gson.fromJson(json, new TypeToken<List<SerializedMediaItem>>() {
      }.getType());
      List<MediaItem> items = new ArrayList<>();

      for (SerializedMediaItem s : serialized) {
        MediaMetadata meta = new MediaMetadata.Builder()
                .setTitle(s.title)
                .setArtist(s.artist)
                .setArtworkUri(s.artworkUri != null && !s.artworkUri.isEmpty() ? Uri.parse(s.artworkUri) : null)
                .build();

        MediaItem.Builder b = new MediaItem.Builder()
                .setMediaId(s.mediaId)
                .setMediaMetadata(meta);

        if (s.uri != null && !s.uri.isEmpty()) {
          Uri stored = Uri.parse(s.uri);
          // Robust URI handling from old QueueStore
          if ("content".equals(stored.getScheme())) {
            try {
              Uri fresh = ContentUris.withAppendedId(
                      MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                      Long.parseLong(s.mediaId)
              );
              b.setUri(fresh);
            } catch (Exception e) {
              b.setUri(stored);
            }
          } else {
            b.setUri(stored);
          }
        }

        if (s.requestUri != null && !s.requestUri.isEmpty()) {
          b.setRequestMetadata(new MediaItem.RequestMetadata.Builder()
                  .setMediaUri(Uri.parse(s.requestUri))
                  .build());
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

  public record Session(List<MediaItem> items, int index, long position, boolean shuffle,
                        int repeat) {
  }

  private static class SerializedMediaItem {
    String mediaId;
    String title;
    String artist;
    String artworkUri;
    String uri;
    String requestUri;

    static SerializedMediaItem fromMediaItem(MediaItem item) {
      SerializedMediaItem s = new SerializedMediaItem();
      s.mediaId = item.mediaId;
      s.title = item.mediaMetadata.title != null ? item.mediaMetadata.title.toString() : "";
      s.artist = item.mediaMetadata.artist != null ? item.mediaMetadata.artist.toString() : "";
      s.artworkUri = item.mediaMetadata.artworkUri != null ? item.mediaMetadata.artworkUri.toString() : "";

      if (item.localConfiguration != null) {
        s.uri = item.localConfiguration.uri.toString();
      }

      if (item.requestMetadata.mediaUri != null) {
        s.requestUri = item.requestMetadata.mediaUri.toString();
      }
      return s;
    }
  }
}
