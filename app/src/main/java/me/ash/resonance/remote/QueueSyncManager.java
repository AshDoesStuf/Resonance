package me.ash.resonance.remote;

import androidx.media3.common.Player;
import androidx.media3.common.Timeline;

import com.google.gson.Gson;

import java.util.List;

import me.ash.resonance.remote.model.QueueItemModel;
import me.ash.resonance.remote.model.RemoteMessage;

public class QueueSyncManager {
  private final ConnectionManager connectionManager;
  private final Player player;
  private final Gson gson;

  private final Player.Listener playerListener = new Player.Listener() {
    @Override
    public void onTimelineChanged(Timeline timeline, int reason) {
      broadcastQueue();
    }
  };

  public QueueSyncManager(ConnectionManager connectionManager, Player player, Gson gson) {
    this.connectionManager = connectionManager;
    this.player = player;
    this.gson = gson;

    player.addListener(playerListener);
  }

  public void broadcastQueue() {
    List<QueueItemModel> queue = PlaybackStateSerializer.serializeQueue(player);
    RemoteMessage message = new RemoteMessage(RemoteMessage.TYPE_QUEUE_UPDATE, queue);
    connectionManager.broadcast(gson.toJson(message));
  }

  public void stop() {
    player.removeListener(playerListener);
  }
}
