package me.ash.resonance.services;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaSession;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.ash.resonance.queue.QueueManager;

/**
 * MediaSession.Callback responsible for resolving ytmusic:// URIs into
 * placeholder MediaItems and handling playback resumption. Extracted from
 * MusicService — depends only on the shared `pending` map it's given.
 */
public class ResolverCallback implements MediaSession.Callback {

  private final Map<String, MediaItem> pending;

  public ResolverCallback(Map<String, MediaItem> pending) {
    this.pending = pending;
  }

  @OptIn(markerClass = UnstableApi.class)
  @NonNull
  @Override
  public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
          MediaSession mediaSession,
          MediaSession.ControllerInfo controller
  ) {
    // If we have a pending restore, return it
    QueueManager.SavedQueueState pendingRestore = QueueManager.get().getPendingRestore();
    if (pendingRestore != null && !pendingRestore.items().isEmpty()) {
      QueueManager.get().clearPendingRestore();
      List<MediaItem> items = new ArrayList<>();
      for (MediaItem item : pendingRestore.items()) {
        items.add(QueueManager.buildPlayableItem(item));
      }
      return Futures.immediateFuture(
              new MediaSession.MediaItemsWithStartPosition(
                      items, pendingRestore.index(), pendingRestore.positionMs()
              )
      );
    }
    // Nothing to resume
    return Futures.immediateFailedFuture(
            new UnsupportedOperationException("No items to resume")
    );
  }

  @NonNull
  @Override
  public ListenableFuture<List<MediaItem>> onAddMediaItems(
          MediaSession session,
          MediaSession.ControllerInfo controller,
          List<MediaItem> items
  ) {
    List<MediaItem> out = new ArrayList<>();

    for (MediaItem item : items) {
      Uri uri = item.localConfiguration != null
              ? item.localConfiguration.uri
              : null;

      if (uri == null) {
        continue;
      }

      String scheme = uri.getScheme();
      if (scheme == null) {
        out.add(item);
        continue;
      }

      switch (scheme) {
        case "ytmusic": {

          String videoId;

          if (uri.getAuthority() != null && !"track".equals(uri.getAuthority())) {
            // ytmusic://VIDEO_ID
            videoId = uri.getAuthority();
          } else {
            // ytmusic://track/VIDEO_ID
            List<String> segments = uri.getPathSegments();
            videoId = segments.isEmpty() ? null : segments.get(segments.size() - 1);
          }

          if (videoId == null || videoId.isEmpty()) {
            Log.e("MusicService", "Invalid YT URI: " + uri);
            break;
          }

          MediaItem placeholder = new MediaItem.Builder()
                  .setMediaId(videoId)
                  .setUri(Uri.parse("ytmusic://" + videoId))
                  .setMediaMetadata(item.mediaMetadata)
                  .build();

          pending.put(videoId, placeholder);
          out.add(placeholder);

          Log.d("MusicService", "Resolving stream for " + videoId);

//            resolveAsync(videoId);
          break;
        }
        case "file":
        case "content":
        default:
          out.add(item);
          break;
      }
    }

    return Futures.immediateFuture(out);
  }
}