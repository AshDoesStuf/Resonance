package me.ash.resonance.sharedlistening.model;

public class SeekPacket {
    public long positionMs;

    public SeekPacket(long positionMs) {
        this.positionMs = positionMs;
    }
}
