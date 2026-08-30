package me.ash.resonance.sharedlistening.model;

public class PlaybackStatePacket {
    public boolean isPlaying;
    public long position;
    public float playbackSpeed;
    public int repeatMode;
    public boolean shuffleMode;

    public PlaybackStatePacket(boolean isPlaying, long position, float playbackSpeed, int repeatMode, boolean shuffleMode) {
        this.isPlaying = isPlaying;
        this.position = position;
        this.playbackSpeed = playbackSpeed;
        this.repeatMode = repeatMode;
        this.shuffleMode = shuffleMode;
    }
}
