package me.ash.resonance.sharedlistening.transport.model;

public class Packet {
    public static final int VERSION = 1;

    // Session Control
    public static final String TYPE_SESSION_START = "SESSION_START";
    public static final String TYPE_SESSION_END = "SESSION_END";
    
    // Playback State
    public static final String TYPE_PLAYBACK_STATE = "PLAYBACK_STATE";
    public static final String TYPE_METADATA = "METADATA";
    public static final String TYPE_QUEUE = "QUEUE";
    public static final String TYPE_SEEK = "SEEK";
    public static final String TYPE_COMMAND = "COMMAND";

    // Real-time
    public static final String TYPE_AUDIO_FRAME = "AUDIO_FRAME";
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";
    public static final String TYPE_PING = "PING";
    public static final String TYPE_PONG = "PONG";

    public int version = VERSION;
    public String type;
    public Object data;
    public long timestamp;

    public Packet() {}

    public Packet(String type, Object data) {
        this.type = type;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }
}
