package me.ash.resonance.sharedlistening.session;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import com.google.gson.Gson;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import me.ash.resonance.sharedlistening.discovery.DiscoveryManager;
import me.ash.resonance.sharedlistening.model.CommandPacket;
import me.ash.resonance.sharedlistening.model.MetadataPacket;
import me.ash.resonance.sharedlistening.model.PlaybackStatePacket;
import me.ash.resonance.sharedlistening.model.SeekPacket;
import me.ash.resonance.sharedlistening.transport.Transport;
import me.ash.resonance.sharedlistening.transport.model.Packet;

public class SessionManager implements DiscoveryManager.Listener, Transport.Callbacks {
  private static final String TAG = "SessionManager";
  private static final long SYNC_INTERVAL_MS = 10000; // Periodic full sync

  private final Context context;
  private final DiscoveryManager discoveryManager;
  private final Transport transport;
  private final Player player;
  private final Map<String, ConnectedListener> listeners = new HashMap<>();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Gson gson = new Gson();

  private String sessionId;
  private boolean isSessionActive = false;
  private SessionListener sessionListener;

  public SessionManager(Context context, DiscoveryManager discoveryManager, Transport transport, Player player) {
    this.context = context;
    this.discoveryManager = discoveryManager;
    this.transport = transport;
    this.player = player;
    this.discoveryManager.setListener(this);
    this.transport.registerCallbacks(this);
  }

  public void setSessionListener(SessionListener listener) {
    this.sessionListener = listener;
  }

  public void startSession(String displayName) {
    if (isSessionActive) return;
    this.sessionId = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    isSessionActive = true;
    discoveryManager.startAdvertising(displayName, sessionId);
    Log.d(TAG, "Session started: " + sessionId);

    if (sessionListener != null) sessionListener.onSessionStateChanged(true);

    // Broadcast session start
    transport.broadcastPacket(new Packet(Packet.TYPE_SESSION_START, null));

    // Broadcast initial state
    broadcastPlaybackState();
    broadcastMetadata();
  }

  public void endSession() {
    if (!isSessionActive) return;
    isSessionActive = false;
    discoveryManager.stopAdvertising();
    transport.broadcastPacket(new Packet(Packet.TYPE_SESSION_END, null));
    listeners.clear();
    Log.d(TAG, "Session ended");

    if (sessionListener != null) {
      sessionListener.onSessionStateChanged(false);
      sessionListener.onListenersChanged(listeners);
    }
  }

  public String getSessionId() {
    return sessionId;
  }

  public boolean isSessionActive() {
    return isSessionActive;
  }

  public Map<String, ConnectedListener> getListeners() {
    return listeners;
  }

  @Override
  public void onConnectionRequested(String endpointId, String displayName, DiscoveryManager.ConnectionResponseCallback callback) {
    Log.d(TAG, "Connection requested from " + displayName);
    // Auto-accept for now, or could show UI dialog
    callback.accept();
    transport.connect(endpointId);
  }

  // --- DiscoveryManager.Listener ---

  @Override
  public void onPacketReceived(String endpointId, Packet packet) {
    if (Packet.TYPE_PING.equals(packet.type)) {
      transport.sendPacket(endpointId, new Packet(Packet.TYPE_PONG, packet.data));
    } else if (Packet.TYPE_COMMAND.equals(packet.type)) {
      handleCommand(packet);
    } else if (Packet.TYPE_SEEK.equals(packet.type)) {
      handleSeek(packet);
    }
  }

  // --- Transport.Callbacks ---

  private void handleCommand(Packet packet) {
    try {
      String json = gson.toJson(packet.data);
      CommandPacket command = gson.fromJson(json, CommandPacket.class);
      if (command == null || command.action == null) return;

      handler.post(() -> {
        switch (command.action) {
          case PLAY:
            player.play();
            break;
          case PAUSE:
            player.pause();
            break;
          case NEXT:
            player.seekToNext();
            break;
          case PREVIOUS:
            player.seekToPrevious();
            break;
          case TOGGLE_PLAY_PAUSE:
            if (player.isPlaying()) player.pause();
            else player.play();
            break;
        }
      });
    } catch (Exception e) {
      Log.e(TAG, "Failed to handle command", e);
    }
  }

  private void handleSeek(Packet packet) {
    try {
      String json = gson.toJson(packet.data);
      SeekPacket seek = gson.fromJson(json, SeekPacket.class);
      if (seek != null) {
        handler.post(() -> player.seekTo(seek.positionMs));
      }
    } catch (Exception e) {
      Log.e(TAG, "Failed to handle seek", e);
    }
  }

  @Override
  public void onConnectionEstablished(String endpointId) {
    Log.d(TAG, "Connection established with " + endpointId);
    transport.onConnectionEstablished(endpointId);
    // Find display name - would need to be passed during discovery
    ConnectedListener listener = new ConnectedListener(endpointId, "Remote Device", transport);
    listeners.put(endpointId, listener);

    if (sessionListener != null) sessionListener.onListenersChanged(listeners);

    // Send session start to individual new listener
    transport.sendPacket(endpointId, new Packet(Packet.TYPE_SESSION_START, null));

    // Send initial sync to new listener
    sendInitialSync(endpointId);
  }

  @Override
  public void onConnectionLost(String endpointId) {
    Log.d(TAG, "Connection lost with " + endpointId);
    transport.onConnectionLost(endpointId);
    listeners.remove(endpointId);
    if (sessionListener != null) sessionListener.onListenersChanged(listeners);
  }

  @Override
  public void onError(String endpointId, Exception e) {
    Log.e(TAG, "Transport error with " + endpointId, e);
  }

  public void broadcastPlaybackState() {
    if (!isSessionActive || listeners.isEmpty()) return;

    PlaybackStatePacket state = new PlaybackStatePacket(
            player.getPlayWhenReady(),
            player.getCurrentPosition(),
            player.getPlaybackParameters().speed,
            player.getRepeatMode(),
            player.getShuffleModeEnabled()
    );

    transport.broadcastPacket(new Packet(Packet.TYPE_PLAYBACK_STATE, state));
  }

  // --- Playback Synchronization ---

  public void broadcastMetadata() {
    if (!isSessionActive || listeners.isEmpty()) return;

    MediaItem item = player.getCurrentMediaItem();
    if (item == null) return;

    MediaMetadata metadata = item.mediaMetadata;
    MetadataPacket data = new MetadataPacket(
            metadata.title != null ? metadata.title.toString() : "",
            metadata.artist != null ? metadata.artist.toString() : "",
            metadata.albumTitle != null ? metadata.albumTitle.toString() : "",
            player.getDuration(),
            item.mediaId
    );

    transport.broadcastPacket(new Packet(Packet.TYPE_METADATA, data));
  }

  public void broadcastSeek(long position) {
    if (!isSessionActive || listeners.isEmpty()) return;
    transport.broadcastPacket(new Packet(Packet.TYPE_SEEK, new SeekPacket(position)));
  }

  private void sendInitialSync(String endpointId) {
    // Send current metadata, playback state, and queue
    broadcastMetadata();
    broadcastPlaybackState();
    // TODO: Send queue
  }

  public interface SessionListener {
    void onListenersChanged(Map<String, ConnectedListener> listeners);

    void onSessionStateChanged(boolean isActive);
  }
}
