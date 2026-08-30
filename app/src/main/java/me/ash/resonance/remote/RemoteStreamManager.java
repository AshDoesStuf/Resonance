package me.ash.resonance.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import com.google.gson.Gson;

import me.ash.resonance.playback.PlaybackSessionManager;
import me.ash.resonance.remote.model.PlaybackStateModel;
import me.ash.resonance.remote.model.RemoteMessage;
import me.ash.resonance.services.MusicService;
import me.ash.resonance.util.NetworkUtils;

public class RemoteStreamManager {
  private static final String TAG = "RemoteStreamManager";
  private final Context context;
  private final PlaybackSessionManager playbackManager;
  private final ConnectionManager connectionManager;
  private final Gson gson;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final java.util.List<RemoteStreamListener> listeners = new java.util.ArrayList<>();
  private boolean enabled = false;
  private boolean logicalIsPlaying = false;
  private long lastReportedPositionMs = 0;

  public RemoteStreamManager(Context context, PlaybackSessionManager playbackManager, ConnectionManager connectionManager, Gson gson) {
    this.context = context.getApplicationContext();
    this.playbackManager = playbackManager;
    this.connectionManager = connectionManager;
    this.gson = gson;
  }

  public void addListener(RemoteStreamListener listener) {
    if (!listeners.contains(listener)) listeners.add(listener);
  }

  public void removeListener(RemoteStreamListener listener) {
    listeners.remove(listener);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean isLogicalPlaying() {
    return logicalIsPlaying;
  }

  public long getLastReportedPositionMs() {
    return lastReportedPositionMs;
  }

  public void onSetRemoteStreamMode(boolean enabled) {
    if (this.enabled == enabled) return;
    this.enabled = enabled;

    mainHandler.post(() -> {
      MusicService service = MusicService.getInstance();
      if (service == null) return;
      Player rawPlayer = service.getRawPlayer();
      Player forwardingPlayer = service.getPlayer();

      if (enabled) {
        logicalIsPlaying = forwardingPlayer.isPlaying();
        rawPlayer.pause();
        lastReportedPositionMs = forwardingPlayer.getCurrentPosition();
        broadcastStreamSource(forwardingPlayer.getCurrentMediaItem());
        preloadNextTrack(forwardingPlayer);
      } else {
        rawPlayer.seekTo(lastReportedPositionMs);
        if (logicalIsPlaying) {
          rawPlayer.play();
        } else {
          rawPlayer.pause();
        }
      }
      broadcastStateUpdate();
      for (RemoteStreamListener l : listeners) l.onRemoteStreamModeChanged(enabled);
    });
  }

  public void onRemotePlayPause(boolean play) {
    if (!enabled) return;
    this.logicalIsPlaying = play;
    broadcastStateUpdate(); // Necessary because this doesn't trigger rawPlayer
  }

  public void onRemotePosition(long positionMs) {
    this.lastReportedPositionMs = positionMs;
    // The ForwardingPlayer in MusicService will use this value
  }

  public void onRemoteStreamError(String error) {
    Log.e(TAG, "Remote stream error: " + error);
    onSetRemoteStreamMode(false);
  }

  public void handleTrackChange(MediaItem item) {
    if (enabled && item != null) {
      broadcastStreamSource(item);
      mainHandler.post(() -> {
        MusicService service = MusicService.getInstance();
        if (service != null) preloadNextTrack(service.getPlayer());
      });
    }
  }

  private void preloadNextTrack(Player player) {
    if (!enabled) return;
    int nextIndex = player.getNextMediaItemIndex();
    if (nextIndex != androidx.media3.common.C.INDEX_UNSET) {
      MediaItem nextItem = player.getMediaItemAt(nextIndex);
      if (nextItem != null) {
        String ip = NetworkUtils.getLocalIpAddress();
        String url = "http://" + ip + ":8081/stream/" + nextItem.mediaId;

        java.util.Map<String, String> data = new java.util.HashMap<>();
        data.put("trackId", nextItem.mediaId);
        data.put("url", url);

        RemoteMessage msg = new RemoteMessage(RemoteMessage.TYPE_PRELOAD_SOURCE, data);
        connectionManager.broadcast(gson.toJson(msg));
      }
    }
  }

  public void handleDisconnect() {
    if (enabled) {
      Log.d(TAG, "Streaming client disconnected, falling back to local playback");
      onSetRemoteStreamMode(false);
    }
  }

  private void broadcastStreamSource(MediaItem item) {
    if (item == null) return;

    String ip = NetworkUtils.getLocalIpAddress();
    String url = "http://" + ip + ":8081/stream/" + item.mediaId;

    java.util.Map<String, String> data = new java.util.HashMap<>();
    data.put("trackId", item.mediaId);
    data.put("url", url);

    RemoteMessage msg = new RemoteMessage(RemoteMessage.TYPE_STREAM_SOURCE, data);
    connectionManager.broadcast(gson.toJson(msg));
  }

  private void broadcastStateUpdate() {
    Player player = playbackManager.getPlayer();
    PlaybackStateModel.StateUpdate update = new PlaybackStateModel.StateUpdate();
    update.playback = PlaybackStateSerializer.serializePlaybackSlice(player);
    update.track = PlaybackStateSerializer.serializeTrackSlice(player);
    update.remoteStream = PlaybackStateSerializer.serializeRemoteStreamSlice(this, player);

    connectionManager.broadcast(gson.toJson(new RemoteMessage(
            RemoteMessage.TYPE_STATE_UPDATE,
            update
    )));
  }

  public interface RemoteStreamListener {
    void onRemoteStreamModeChanged(boolean enabled);
  }
}
