package me.ash.resonance.sharedlistening.transport;

import me.ash.resonance.sharedlistening.transport.model.Packet;

public interface Transport {
  void connect(String endpointId);

  void disconnect(String endpointId);

  void sendPacket(String endpointId, Packet packet);

  void broadcastPacket(Packet packet);

  void registerCallbacks(Callbacks callbacks);

  void onConnectionEstablished(String endpointId);

  void onConnectionLost(String endpointId);

  interface Callbacks {
    void onPacketReceived(String endpointId, Packet packet);

    void onConnectionEstablished(String endpointId);

    void onConnectionLost(String endpointId);

    void onError(String endpointId, Exception e);
  }
}
