package me.ash.resonance.queue;

import android.content.ContentUris;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/**
 * Queue persistence using SharedPreferences with Gson serialization.
 * Simple, type-safe, and efficient.
 */
public class QueueStore {

  private static final String PREFS_NAME = "queue_store";
  private static final String KEY_QUEUE = "current_queue";
  private static final String KEY_INDEX = "queue_index";
  private static final String KEY_POSITION = "queue_position_ms";

  private final Gson gson = new Gson();

  public QueueStore(Context context) {
    // No initialization needed — SharedPreferences is used directly in methods
  }

  /**
   * Save the current queue state.
   */
  public void saveCurrentQueue(Context context, Player player) {
    Log.d("QueueStore", "Saving queue");

    List<SerializedMediaItem> items = new ArrayList<>();
    for (int i = 0; i < player.getMediaItemCount(); i++) {
      MediaItem item = player.getMediaItemAt(i);

      Uri persistUri = null;
      if (item.requestMetadata.mediaUri != null
              && "ytmusic".equals(item.requestMetadata.mediaUri.getScheme())) {
        persistUri = item.requestMetadata.mediaUri;
      } else if (item.localConfiguration != null) {
        persistUri = item.localConfiguration.uri;
      }

      items.add(new SerializedMediaItem(
              item.mediaId,
              item.mediaMetadata.title != null ? item.mediaMetadata.title.toString() : "",
              item.mediaMetadata.artist != null ? item.mediaMetadata.artist.toString() : "",
              item.mediaMetadata.artworkUri != null ? item.mediaMetadata.artworkUri.toString() : "",
              persistUri != null ? persistUri.toString() : ""
      ));
    }

    String json = gson.toJson(items);
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUEUE, json)
            .putInt(KEY_INDEX, player.getCurrentMediaItemIndex())
            .putLong(KEY_POSITION, player.getCurrentPosition())
            .apply();
  }

  /**
   * Restore the current queue state.
   */
  @Nullable
  public QueueManager.SavedQueueState restoreCurrentQueue(Context context) {
    Log.d("QueueStore", "Restoring queue");

    try {
      String json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
              .getString(KEY_QUEUE, null);

      if (json == null) return null;

      List<SerializedMediaItem> serialized = gson.fromJson(
              json,
              new TypeToken<List<SerializedMediaItem>>() {
              }.getType()
      );

      List<MediaItem> items = new ArrayList<>();
      for (SerializedMediaItem s : serialized) {
        MediaMetadata meta = new MediaMetadata.Builder()
                .setTitle(s.title)
                .setArtist(s.artist)
                .setArtworkUri(!s.artworkUri.isEmpty() ? Uri.parse(s.artworkUri) : null)
                .build();

        MediaItem.Builder b = new MediaItem.Builder()
                .setMediaId(s.mediaId)
                .setMediaMetadata(meta);

        if (!s.uri.isEmpty()) {
          Uri stored = Uri.parse(s.uri);
          if ("content".equals(stored.getScheme())) {
            Uri fresh = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    Long.parseLong(s.mediaId)
            );
            b.setUri(fresh);
          } else {
            b.setUri(stored);
          }
        }
        items.add(b.build());
      }

      int index = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
              .getInt(KEY_INDEX, 0);
      long position = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
              .getLong(KEY_POSITION, 0);

      return new QueueManager.SavedQueueState(items, index, position);

    } catch (Exception e) {
      Log.e("QueueStore", "Restore error", e);
      return null;
    }
  }

  /**
   * Simple data class for Gson serialization.
   */
  static class SerializedMediaItem {
    String mediaId;
    String title;
    String artist;
    String artworkUri;
    String uri;

    SerializedMediaItem(String mediaId, String title, String artist, String artworkUri, String uri) {
      this.mediaId = mediaId;
      this.title = title;
      this.artist = artist;
      this.artworkUri = artworkUri;
      this.uri = uri;
    }
  }
}