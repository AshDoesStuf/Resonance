package me.ash.resonance.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.java_websocket.WebSocket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.ash.resonance.MusicLoader;
import me.ash.resonance.services.MusicService;
import me.ash.resonance.db.AppDatabase;
import me.ash.resonance.db.DownloadedSongEntity;
import me.ash.resonance.playback.PlaybackSessionManager;
import me.ash.resonance.playlist.PlaylistManager;
import me.ash.resonance.queue.QueueManager;
import me.ash.resonance.remote.model.RemoteMessage;
import me.ash.resonance.song.Song;

public class MessageRouter {
  private static final String PLAYLIST_FAVOURITES = "__favourites__";

  private final Context context;
  private final PlaybackSessionManager playbackManager;
  private final QueueManager queueManager;
  private final RemoteStreamManager remoteStreamManager;
  private final Gson gson;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

  public MessageRouter(Context context, PlaybackSessionManager playbackManager, QueueManager queueManager, RemoteStreamManager remoteStreamManager, Gson gson) {
    this.context = context.getApplicationContext();
    this.playbackManager = playbackManager;
    this.queueManager = queueManager;
    this.remoteStreamManager = remoteStreamManager;
    this.gson = gson;
  }

  private String formatArtworkUrl(android.net.Uri uri) {
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
      String ip = me.ash.resonance.util.NetworkUtils.getLocalIpAddress();

      return "http://" + ip + ":8081/artwork/" + albumId;
    }

    // Fallback for other content URIs or local paths if needed
    return uriString;
  }

  // ── Playlist helpers ──────────────────────────────────────────────

  public void route(WebSocket conn, String message) {
    try {
      JsonObject json = gson.fromJson(message, JsonObject.class);
      String type = json.get("type").getAsString();
      JsonObject data = json.has("data") && !json.get("data").isJsonNull() ? json.getAsJsonObject("data") : null;

      Player player = playbackManager.getPlayer();

      switch (type) {
        case RemoteMessage.CMD_PLAY:
          if (remoteStreamManager.isEnabled()) {
            remoteStreamManager.onRemotePlayPause(true);
          } else {
            player.play();
          }
          break;
        case RemoteMessage.CMD_PAUSE:
          if (remoteStreamManager.isEnabled()) {
            remoteStreamManager.onRemotePlayPause(false);
          } else {
            player.pause();
          }
          break;
        case RemoteMessage.CMD_TOGGLE_PLAYBACK:
          if (remoteStreamManager.isEnabled()) {
            remoteStreamManager.onRemotePlayPause(!remoteStreamManager.isLogicalPlaying());
          } else {
            playbackManager.playPause();
          }
          break;
        case RemoteMessage.CMD_NEXT:
          if (remoteStreamManager.isEnabled()) {
            MusicService service = MusicService.getInstance();
            if (service != null) {
              Player rawPlayer = service.getRawPlayer();
              rawPlayer.seekToNext();
              rawPlayer.pause(); // Ensure silent on phone
            }
          } else {
            playbackManager.next();
          }
          break;
        case RemoteMessage.CMD_PREVIOUS:
          if (remoteStreamManager.isEnabled()) {
            MusicService service = MusicService.getInstance();
            if (service != null) {
              Player rawPlayer = service.getRawPlayer();
              rawPlayer.seekToPrevious();
              rawPlayer.pause(); // Ensure silent on phone
            }
          } else {
            playbackManager.previous();
          }
          break;
        case RemoteMessage.CMD_SEEK:
          if (data != null && data.has("position")) {
            if (remoteStreamManager.isEnabled()) {
              remoteStreamManager.onRemotePosition(data.get("position").getAsLong());
              // Broadcast update so other clients see new position, browser client handles local seek
              remoteStreamManager.onRemotePlayPause(remoteStreamManager.isLogicalPlaying());
            } else {
              player.seekTo(data.get("position").getAsLong());
            }
          }
          break;
        case RemoteMessage.CMD_SET_VOLUME:
          if (data != null && data.has("volume")) {
            // Media3 elements handle volume via float (0.0f to 1.0f) or device volume
            float vol = data.get("volume").getAsInt() / 100f;
            if (player.getDeviceInfo() != null) {
              player.setDeviceVolume((int) (vol * player.getDeviceInfo().maxVolume));
            } else {
              player.setVolume(vol);
            }
          }
          break;
        case RemoteMessage.CMD_SET_SHUFFLE:
          if (data != null && data.has("enabled")) {
            player.setShuffleModeEnabled(data.get("enabled").getAsBoolean());
          }
          break;
        case RemoteMessage.CMD_SET_REPEAT:
          if (data != null && data.has("mode")) {
            String mode = data.get("mode").getAsString();
            switch (mode) {
              case "ALL":
                player.setRepeatMode(Player.REPEAT_MODE_ALL);
                break;
              case "ONE":
                player.setRepeatMode(Player.REPEAT_MODE_ONE);
                break;
              default:
                player.setRepeatMode(Player.REPEAT_MODE_OFF);
                break;
            }
          }
          break;
        case RemoteMessage.CMD_QUEUE_MOVE:
          if (data != null && data.has("fromIndex") && data.has("toIndex")) {
            player.moveMediaItem(
                    data.get("fromIndex").getAsInt(),
                    data.get("toIndex").getAsInt()
            );
          }
          break;
        case RemoteMessage.CMD_QUEUE_REMOVE:
          if (data != null && data.has("index")) {
            player.removeMediaItem(data.get("index").getAsInt());
          }
          break;
        case RemoteMessage.CMD_QUEUE_CLEAR:
          player.clearMediaItems();
          break;
        case RemoteMessage.CMD_QUEUE_ADD:
          if (data != null && data.has("trackId")) {
            String trackId = data.get("trackId").getAsString();
            androidx.media3.common.MediaItem item = new androidx.media3.common.MediaItem.Builder()
                    .setMediaId(trackId)
                    .setUri("ytmusic://" + trackId)
                    .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(data.has("title") ? data.get("title").getAsString() : "Unknown")
                            .setArtist(data.has("artist") ? data.get("artist").getAsString() : "Unknown")
                            .setArtworkUri(data.has("artworkUrl") ? android.net.Uri.parse(data.get("artworkUrl").getAsString()) : null)
                            .build())
                    .build();
            player.addMediaItem(item);
          }
          break;
        case RemoteMessage.CMD_PLAY_TRACK:
          if (data != null && data.has("index")) {
            player.seekTo(data.get("index").getAsInt(), 0);
            player.play();
          }
          break;
        case RemoteMessage.CMD_GET_PLAYLISTS:
          ioExecutor.execute(() -> sendPlaylistsList(conn));
          break;

        case RemoteMessage.CMD_GET_PLAYLIST_TRACKS:
          if (data != null && data.has("id")) {
            String playlistId = data.get("id").getAsString();
            ioExecutor.execute(() -> sendPlaylistTracks(conn, playlistId));
          }
          break;

        case RemoteMessage.CMD_QUEUE_PLAYLIST:
          if (data != null && data.has("id")) {
            String playlistId = data.get("id").getAsString();
            ioExecutor.execute(() -> applyPlaylistToQueue(playlistId, false, -1));
          }
          break;

        case RemoteMessage.CMD_PLAY_PLAYLIST_TRACK:
          if (data != null && data.has("id") && data.has("index")) {
            String playlistId = data.get("id").getAsString();
            int index = data.get("index").getAsInt();
            ioExecutor.execute(() -> applyPlaylistToQueue(playlistId, true, index));
          }
          break;
        case RemoteMessage.CMD_TRACK_ENDED:
          mainHandler.post(() -> {
            if (remoteStreamManager.isEnabled()) {
              Player p = playbackManager.getPlayer();
              if (p.getRepeatMode() == Player.REPEAT_MODE_ONE) {
                p.seekTo(0);
                // Force a track change broadcast even if it's the same item
                remoteStreamManager.handleTrackChange(p.getCurrentMediaItem());
              } else {
                p.seekToNext();
              }
            }
          });
          break;
        case RemoteMessage.CMD_SET_REMOTE_STREAM_MODE:
          if (data != null && data.has("enabled")) {
            remoteStreamManager.onSetRemoteStreamMode(data.get("enabled").getAsBoolean());
          }
          break;
        case RemoteMessage.CMD_REMOTE_POSITION:
          if (data != null && data.has("position")) {
            remoteStreamManager.onRemotePosition(data.get("position").getAsLong());
          }
          break;
        case RemoteMessage.CMD_REMOTE_STREAM_ERROR:
          if (data != null && data.has("error")) {
            remoteStreamManager.onRemoteStreamError(data.get("error").getAsString());
          }
          break;
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private List<String> getPlaylistMediaIds(String playlistId) {
    PlaylistManager pm = PlaylistManager.get(context);

    if (PLAYLIST_FAVOURITES.equals(playlistId)) {
      return pm.getFavouriteIds();
    }
    if (PlaylistManager.SMART_RECENTLY_ADDED.equals(playlistId)) {
      return MusicLoader.getRecentlyAddedIds(context, 50);
    }
    if (PlaylistManager.SMART_DOWNLOADS.equals(playlistId)) {
      return getDownloadedIds();
    }
    // NOTE: SMART_MOST_PLAYED / SMART_RECENTLY_PLAYED have no backing data
    // source in the code provided so far — they're intentionally omitted.
    // If you have a play-history/play-count store, tell me where it lives
    // and I'll wire these two in.

    return pm.getAllPlaylists().getOrDefault(playlistId, new ArrayList<>());
  }

  private List<String> getDownloadedIds() {
    List<String> ids = new ArrayList<>();
    for (DownloadedSongEntity e : AppDatabase.get(context).downloadedSongDao().getAll()) {
      ids.add(String.valueOf(e.mediaStoreId));
    }
    return ids;
  }

  private Map<String, Song> indexSongsById() {
    // MusicLoader.loadSongs() must be called off the main thread — this method
    // is only ever invoked from ioExecutor, never from mainHandler.
    Map<String, Song> byId = new HashMap<>();
    for (Song s : MusicLoader.loadSongs(context)) {
      byId.put(String.valueOf(s.id), s);
    }
    return byId;
  }

  private void sendPlaylistsList(WebSocket conn) {
    PlaylistManager pm = PlaylistManager.get(context);
    JsonArray arr = new JsonArray();

    arr.add(playlistSummary(PLAYLIST_FAVOURITES, "Favourites", pm.getFavouriteIds().size()));
    arr.add(playlistSummary(PlaylistManager.SMART_RECENTLY_ADDED, "Recently Added",
            MusicLoader.getRecentlyAddedIds(context, 50).size()));
    arr.add(playlistSummary(PlaylistManager.SMART_DOWNLOADS, "Downloads", getDownloadedIds().size()));

    for (Map.Entry<String, List<String>> e : pm.getAllPlaylists().entrySet()) {
      arr.add(playlistSummary(e.getKey(), e.getKey(), e.getValue().size()));
    }

    JsonObject responseData = new JsonObject();
    responseData.add("playlists", arr);
    conn.send(gson.toJson(new RemoteMessage(RemoteMessage.TYPE_PLAYLISTS, responseData)));
  }

  private JsonObject playlistSummary(String id, String name, int trackCount) {
    JsonObject o = new JsonObject();
    o.addProperty("id", id);
    o.addProperty("name", name);
    o.addProperty("trackCount", trackCount);
    return o;
  }

  private void sendPlaylistTracks(WebSocket conn, String playlistId) {
    List<String> mediaIds = getPlaylistMediaIds(playlistId);
    Map<String, Song> songsById = indexSongsById();

    JsonArray tracks = new JsonArray();
    for (String id : mediaIds) {
      Song song = songsById.get(id);
      if (song == null) continue; // stale id (file removed/moved)

      JsonObject t = new JsonObject();
      t.addProperty("trackId", id);
      t.addProperty("title", song.title);
      t.addProperty("artist", song.artist);
      // TODO: point this at your ArtworkServer's actual route for album art.
      // Using song.albumArtUri directly won't be reachable from the browser.

      String artworkUrl = formatArtworkUrl(song.albumArtUri);
      t.addProperty("artworkUrl", artworkUrl);
      tracks.add(t);
    }

    JsonObject responseData = new JsonObject();
    responseData.addProperty("id", playlistId);
    responseData.add("tracks", tracks);
    conn.send(gson.toJson(new RemoteMessage(RemoteMessage.TYPE_PLAYLIST_TRACKS, responseData)));
  }

  /**
   * Adds a playlist's tracks to the queue. If playFromIndex is true, the
   * current queue is cleared first and playback jumps to `index`.
   */
  private void applyPlaylistToQueue(String playlistId, boolean playFromIndex, int index) {
    List<String> mediaIds = getPlaylistMediaIds(playlistId);
    Map<String, Song> songsById = indexSongsById();

    List<MediaItem> items = new ArrayList<>();
    for (String id : mediaIds) {
      Song song = songsById.get(id);
      if (song == null) continue;

      items.add(new MediaItem.Builder()
              .setMediaId(id)
              .setUri(song.uri)
              .setMediaMetadata(new MediaMetadata.Builder()
                      .setTitle(song.title)
                      .setArtist(song.artist)
                      .setArtworkUri(song.albumArtUri)
                      .build())
              .build());
    }

    if (items.isEmpty()) return;

    // Player must be touched on the main thread.
    mainHandler.post(() -> {
      Player player = playbackManager.getPlayer();
      if (playFromIndex) {
        player.clearMediaItems();
      }
      player.addMediaItems(items);
      if (playFromIndex) {
        player.seekTo(index, 0);
        player.play();
      }
    });
  }
}