package me.ash.resonance.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;

import com.google.gson.Gson;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

import me.ash.resonance.playback.PlaybackSessionManager;
import me.ash.resonance.queue.QueueManager;
import me.ash.resonance.remote.model.PlaybackStateModel;
import me.ash.resonance.remote.model.RemoteMessage;

@UnstableApi
public class RemoteControlManager {
  private static final String TAG = "RemoteControlManager";
  private static final int PORT = 8080;

  private static RemoteControlManager instance;
  private final ConnectionManager connectionManager;
  private final WebSocketServerManager serverManager;
  private final MessageRouter messageRouter;
  private final Handler mainHandler;
  private final Gson gson;
  private final PlaybackSyncManager playbackSync;
  private final QueueSyncManager queueSync;
  private final LocalMediaServer artworkServer;
  private final RemoteStreamManager remoteStreamManager;

  @OptIn(markerClass = UnstableApi.class)
  private RemoteControlManager(Context context, PlaybackSessionManager playbackManager, QueueManager queueManager) {
    this.gson = new Gson();
    this.mainHandler = new Handler(Looper.getMainLooper());
    this.connectionManager = new ConnectionManager();
    this.remoteStreamManager = new RemoteStreamManager(context, playbackManager, connectionManager, gson);
    this.messageRouter = new MessageRouter(context, playbackManager, queueManager, remoteStreamManager, gson);

    this.serverManager = new WebSocketServerManager(PORT);
    this.artworkServer = new LocalMediaServer(context, 8081);
    this.serverManager.setListener(new WebSocketServerManager.ServerListener() {
      @Override
      public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Log.d(TAG, "New connection: " + conn.getRemoteSocketAddress());
        connectionManager.addConnection(conn);

        // Send initial state
        mainHandler.post(() -> sendInitialState(conn, playbackManager.getPlayer()));
      }

      @Override
      public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Log.d(TAG, "Closed connection: " + conn.getRemoteSocketAddress());
        connectionManager.removeConnection(conn);
        remoteStreamManager.handleDisconnect();
      }

      @Override
      public void onMessage(WebSocket conn, String message) {
        Log.d(TAG, "Message from client: " + message);
        mainHandler.post(() -> messageRouter.route(conn, message));
      }

      @Override
      public void onError(WebSocket conn, Exception ex) {
        Log.e(TAG, "Error on connection: " + (conn != null ? conn.getRemoteSocketAddress() : "null"), ex);
      }

      @Override
      public void onStart() {
        Log.i(TAG, "WebSocket server started on port " + PORT);
      }
    });

    this.playbackSync = new PlaybackSyncManager(connectionManager, playbackManager.getPlayer(), gson);
    this.queueSync = new QueueSyncManager(connectionManager, playbackManager.getPlayer(), gson);
  }

  public static synchronized RemoteControlManager getInstance(Context context, PlaybackSessionManager playbackManager, QueueManager queueManager) {
    if (instance == null && playbackManager != null) {
      instance = new RemoteControlManager(context, playbackManager, queueManager);
    }
    return instance;
  }

  public static synchronized RemoteControlManager getInstance() {
    return instance;
  }

  public RemoteStreamManager getRemoteStreamManager() {
    return remoteStreamManager;
  }

  public void start() {
    serverManager.start();
    artworkServer.start();
  }

  public void stop() {
    try {
      playbackSync.stop();
      queueSync.stop();
      serverManager.stop();
      artworkServer.stop();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private void sendInitialState(WebSocket conn, Player player) {
    // Build the composite snapshot object mapping to the unified protocol
    PlaybackStateModel.ConnectedSnapshot snapshot = new PlaybackStateModel.ConnectedSnapshot();

    // Populate slices using your serializer or helper utilities mapped to the contract schemas
    snapshot.playback = PlaybackStateSerializer.serializePlaybackSlice(player);
    snapshot.track = PlaybackStateSerializer.serializeTrackSlice(player);
    snapshot.queue = PlaybackStateSerializer.serializeQueueSlice(player);
    snapshot.remoteStream = PlaybackStateSerializer.serializeRemoteStreamSlice(remoteStreamManager, player);

    // Package up into a single clean message delivery transaction
    RemoteMessage connectedMessage = new RemoteMessage(RemoteMessage.TYPE_CONNECTED, snapshot);
    conn.send(gson.toJson(connectedMessage));
  }
}
