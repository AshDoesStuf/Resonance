package me.ash.resonance.sharedlistening.session;

import me.ash.resonance.sharedlistening.transport.Transport;

public class ConnectedListener {
    private final String endpointId;
    private final String displayName;
    private final long connectedAt;
    private final Transport transport;
    
    private long lastHeartbeat;
    private long latency;

    public ConnectedListener(String endpointId, String displayName, Transport transport) {
        this.endpointId = endpointId;
        this.displayName = displayName;
        this.transport = transport;
        this.connectedAt = System.currentTimeMillis();
        this.lastHeartbeat = this.connectedAt;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getConnectedAt() {
        return connectedAt;
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void updateHeartbeat() {
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public long getLatency() {
        return latency;
    }

    public void setLatency(long latency) {
        this.latency = latency;
    }
}
