package me.ash.resonance.yt;

public class YtTrack {
  public final String videoId;
  public final String title;
  public final String artist;
  public final String albumName;
  public final String thumbnailUrl;
  public final long durationSeconds;
  public final String url;
  public transient boolean isDownloading = false;

  public YtTrack(String videoId, String title, String artist,
                 String albumName, String thumbnailUrl,
                 long durationSeconds, String url) {
    this.videoId = videoId;
    this.title = title;
    this.artist = artist;
    this.albumName = albumName;
    this.thumbnailUrl = thumbnailUrl;
    this.durationSeconds = durationSeconds;
    this.url = url;
  }

  public String formattedDuration() {
    long m = durationSeconds / 60;
    long s = durationSeconds % 60;
    return m + ":" + String.format("%02d", s);
  }
}