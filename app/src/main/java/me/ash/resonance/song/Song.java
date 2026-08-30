package me.ash.resonance.song;

import android.net.Uri;

public class Song {
  public String id;
  public String title;
  public String artist;
  public String album;
  public String duration;
  public Uri uri;
  public Uri albumArtUri;

  public Song(String id, String title, String artist, String album, String duration, Uri uri, Uri albumArtUri) {
    this.id = id;
    this.title = title;
    this.artist = artist;
    this.album = album;
    this.duration = duration;
    this.uri = uri;
    this.albumArtUri = albumArtUri;
  }
}
