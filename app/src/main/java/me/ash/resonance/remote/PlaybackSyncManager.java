package me.ash.resonance.remote;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;

import com.google.gson.Gson;

import me.ash.resonance.remote.model.PlaybackStateModel;
import me.ash.resonance.remote.model.RemoteMessage;

public class PlaybackSyncManager {
  private final ConnectionManager connectionManager;
  private final Player player;
  private final Gson gson;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable broadcastRunnable = this::broadcastState;
  private final Runnable positionBroadcaster = new Runnable() {
    @Override
    public void run() {
      if (player.isPlaying()) {
        broadcastPosition();
      }
      handler.postDelayed(this, 1000);
    }
  };
  private boolean broadcastScheduled = false;
  private final Player.Listener playerListener = new Player.Listener() {
    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
      scheduleBroadcast();
    }

    @Override
    public void onMediaItemTransition(MediaItem mediaItem, int reason) {
      scheduleBroadcast();
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
      scheduleBroadcast();
    }

    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
      scheduleBroadcast();
    }

    @Override
    public void onVolumeChanged(float volume) {
      scheduleBroadcast();
    }

    @Override
    public void onDeviceVolumeChanged(int volume, boolean muted) {
      scheduleBroadcast();
    }

    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
      broadcastPosition();
    }
  };

  public PlaybackSyncManager(ConnectionManager connectionManager, Player player, Gson gson) {
    this.connectionManager = connectionManager;
    this.player = player;
    this.gson = gson;

    player.addListener(playerListener);
    handler.post(positionBroadcaster);
  }

  private void scheduleBroadcast() {
    if (broadcastScheduled) return;
    broadcastScheduled = true;
    handler.post(() -> {
      broadcastScheduled = false;
      broadcastState();
    });
  }

  @OptIn(markerClass = UnstableApi.class)
  public void broadcastState() {
    PlaybackStateModel.StateUpdate update = new PlaybackStateModel.StateUpdate();
    RemoteStreamManager rsm = null;
    RemoteControlManager rcm = RemoteControlManager.getInstance();
    if (rcm != null) rsm = rcm.getRemoteStreamManager();

    update.playback = PlaybackStateSerializer.serializePlaybackSlice(player);
    update.track = PlaybackStateSerializer.serializeTrackSlice(player);
    update.remoteStream = PlaybackStateSerializer.serializeRemoteStreamSlice(rsm, player);

    RemoteMessage message = new RemoteMessage(RemoteMessage.TYPE_STATE_UPDATE, update);
    connectionManager.broadcast(gson.toJson(message));
  }

  public void broadcastPosition() {
    long position = player.getCurrentPosition();
    RemoteMessage message = new RemoteMessage(RemoteMessage.TYPE_POSITION_UPDATE, position);
    connectionManager.broadcast(gson.toJson(message));
  }

  public void stop() {
    handler.removeCallbacks(positionBroadcaster);
    player.removeListener(playerListener);
  }
}
