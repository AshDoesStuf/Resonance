package me.ash.resonance.sharedlistening.transport;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import me.ash.resonance.sharedlistening.discovery.NearbyDiscoveryManager;
import me.ash.resonance.sharedlistening.transport.model.Packet;

public class NearbyTransport implements Transport {
  private static final String TAG = "NearbyTransport";

  private final ConnectionsClient connectionsClient;
  private final NearbyDiscoveryManager discoveryManager;
  private final Gson gson;
  private final Set<String> connectedEndpoints = new HashSet<>();
  private Callbacks callbacks;
  private final PayloadCallback payloadCallback = new PayloadCallback() {
    @Override
    public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
      if (payload.getType() == Payload.Type.BYTES) {
        byte[] bytes = payload.asBytes();
        if (bytes != null) {
          String json = new String(bytes, StandardCharsets.UTF_8);
          try {
            Packet packet = gson.fromJson(json, Packet.class);
            if (callbacks != null) {
              callbacks.onPacketReceived(endpointId, packet);
            }
          } catch (Exception e) {
            Log.e(TAG, "Failed to parse packet", e);
          }
        }
      }
    }

    @Override
    public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
      // Monitor transfer progress if needed
    }
  };

  public NearbyTransport(Context context, NearbyDiscoveryManager discoveryManager) {
    this.connectionsClient = Nearby.getConnectionsClient(context);
    this.discoveryManager = discoveryManager;
    this.gson = new Gson();
  }

  @Override
  public void connect(String endpointId) {
    discoveryManager.acceptConnection(endpointId, payloadCallback);
  }

  @Override
  public void disconnect(String endpointId) {
    connectionsClient.disconnectFromEndpoint(endpointId);
    connectedEndpoints.remove(endpointId);
  }

  @Override
  public void sendPacket(String endpointId, Packet packet) {
    String json = gson.toJson(packet);
    Payload payload = Payload.fromBytes(json.getBytes(StandardCharsets.UTF_8));
    connectionsClient.sendPayload(endpointId, payload);
  }

  @Override
  public void broadcastPacket(Packet packet) {
    if (connectedEndpoints.isEmpty()) return;
    String json = gson.toJson(packet);
    Payload payload = Payload.fromBytes(json.getBytes(StandardCharsets.UTF_8));
    connectionsClient.sendPayload(new java.util.ArrayList<>(connectedEndpoints), payload);
  }

  @Override
  public void registerCallbacks(Callbacks callbacks) {
    this.callbacks = callbacks;
  }

  public void onConnectionEstablished(String endpointId) {
    connectedEndpoints.add(endpointId);
    if (callbacks != null) {
      callbacks.onConnectionEstablished(endpointId);
    }
  }

  public void onConnectionLost(String endpointId) {
    connectedEndpoints.remove(endpointId);
    if (callbacks != null) {
      callbacks.onConnectionLost(endpointId);
    }
  }
}
