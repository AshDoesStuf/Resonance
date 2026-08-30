package me.ash.resonance.remote;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import java.util.ArrayList;
import java.util.List;

import me.ash.resonance.remote.model.PlaybackStateModel;
import me.ash.resonance.remote.model.QueueItemModel;

public class PlaybackStateSerializer {

  private static String cachedIp = null;
  private static long lastIpFetch = 0;

  public static PlaybackStateModel.PlaybackSlice serializePlaybackSlice(Player player) {
    RemoteStreamManager rsm = null;
    RemoteControlManager rcm = RemoteControlManager.getInstance();
    if (rcm != null) rsm = rcm.getRemoteStreamManager();

    PlaybackStateModel.PlaybackSlice slice = new PlaybackStateModel.PlaybackSlice();

    if (rsm != null && rsm.isEnabled()) {
      slice.isPlaying = rsm.isLogicalPlaying();
    } else {
      slice.isPlaying = player.isPlaying();
    }

    slice.position = player.getCurrentPosition();
    slice.duration = player.getDuration();
    slice.shuffleEnabled = player.getShuffleModeEnabled();

    // Use device volume if available, otherwise player volume
    if (player.getDeviceInfo() != null && player.getDeviceInfo().maxVolume > 0) {
      slice.volume = (player.getDeviceVolume() * 100) / player.getDeviceInfo().maxVolume;
    } else {
      slice.volume = (int) (player.getVolume() * 100);
    }

    switch (player.getRepeatMode()) {
      case Player.REPEAT_MODE_ALL:
        slice.repeatMode = "ALL";
        break;
      case Player.REPEAT_MODE_ONE:
        slice.repeatMode = "ONE";
        break;
      default:
        slice.repeatMode = "NONE";
        break;
    }
    return slice;
  }

  public static PlaybackStateModel.TrackSlice serializeTrackSlice(Player player) {
    MediaItem currentItem = player.getCurrentMediaItem();
    if (currentItem == null) {
      return null;
    }

    PlaybackStateModel.TrackSlice slice = new PlaybackStateModel.TrackSlice();
    MediaMetadata metadata = currentItem.mediaMetadata;
    slice.trackId = currentItem.mediaId;
    slice.title = metadata.title != null ? metadata.title.toString() : "Unknown";
    slice.artist = metadata.artist != null ? metadata.artist.toString() : "Unknown";
    slice.album = metadata.albumTitle != null ? metadata.albumTitle.toString() : "";
    slice.artworkUrl = formatArtworkUrl(metadata.artworkUri);
    return slice;
  }

  public static PlaybackStateModel.QueueSlice serializeQueueSlice(Player player) {
    PlaybackStateModel.QueueSlice slice = new PlaybackStateModel.QueueSlice();
    List<QueueItemModel> items = new ArrayList<>();
    for (int i = 0; i < player.getMediaItemCount(); i++) {
      MediaItem item = player.getMediaItemAt(i);
      QueueItemModel model = new QueueItemModel();
      model.trackId = item.mediaId;
      model.title = item.mediaMetadata.title != null ? item.mediaMetadata.title.toString() : "Unknown";
      model.artist = item.mediaMetadata.artist != null ? item.mediaMetadata.artist.toString() : "Unknown";
      model.artworkUrl = formatArtworkUrl(item.mediaMetadata.artworkUri);
      items.add(model);
    }
    slice.items = items;
    slice.currentIndex = player.getCurrentMediaItemIndex();
    return slice;
  }

  private static String formatArtworkUrl(android.net.Uri uri) {
    if (uri == null) return "";
    String uriString = uri.toString();

    // If it's already a web URL, return it as is
    if (uriString.startsWith("http")) {
      return uriString;
    }

    // If it's a MediaStore artwork URI, point it to our local server
    if (uriString.startsWith("content://media/external/audio/albumart/")) {
      String albumId = uriString.substring("content://media/external/audio/albumart/".length());

      long now = System.currentTimeMillis();
      if (cachedIp == null || now - lastIpFetch > 60000) { // Cache for 1 minute
        cachedIp = me.ash.resonance.util.NetworkUtils.getLocalIpAddress();
        lastIpFetch = now;
      }

      return "http://" + cachedIp + ":8081/artwork/" + albumId;
    }

    // Fallback for other content URIs or local paths if needed
    return uriString;
  }

  public static PlaybackStateModel.RemoteStreamSlice serializeRemoteStreamSlice(RemoteStreamManager manager, Player player) {
    if (manager == null) return null;
    PlaybackStateModel.RemoteStreamSlice slice = new PlaybackStateModel.RemoteStreamSlice();
    slice.enabled = manager.isEnabled();
    if (slice.enabled && player != null && player.getCurrentMediaItem() != null) {
      String ip = me.ash.resonance.util.NetworkUtils.getLocalIpAddress();
      slice.streamUrl = "http://" + ip + ":8081/stream/" + player.getCurrentMediaItem().mediaId;
    }
    return slice;
  }

  // Retained for compatibility with SyncManagers until they are updated
  public static PlaybackStateModel.PlaybackSlice serialize(Player player) {
    return serializePlaybackSlice(player);
  }

  public static List<QueueItemModel> serializeQueue(Player player) {
    return serializeQueueSlice(player).items;
  }
}
