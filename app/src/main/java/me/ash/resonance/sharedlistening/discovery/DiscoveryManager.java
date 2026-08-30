package me.ash.resonance.sharedlistening.discovery;

public interface DiscoveryManager {
    void startAdvertising(String displayName, String sessionId);
    void stopAdvertising();
    void setListener(Listener listener);

    interface Listener {
        void onConnectionRequested(String endpointId, String displayName, ConnectionResponseCallback callback);
        void onConnectionEstablished(String endpointId);
        void onConnectionLost(String endpointId);
    }

    interface ConnectionResponseCallback {
        void accept();
        void reject();
    }
}
