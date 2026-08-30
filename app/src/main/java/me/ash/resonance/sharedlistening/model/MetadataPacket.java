package me.ash.resonance.sharedlistening.model;

public class MetadataPacket {
    public String title;
    public String artist;
    public String album;
    public long duration;
    public String mediaId;

    public MetadataPacket(String title, String artist, String album, long duration, String mediaId) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
        this.mediaId = mediaId;
    }
}
