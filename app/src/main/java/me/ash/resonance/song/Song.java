package me.ash.resonance.song;

import android.net.Uri;

public class Song {
  public long id;
  public String title;
  public String artist;
  public String duration;
  public Uri uri;
  public Uri albumArtUri;

  public Song(long id, String title, String artist, String duration, Uri uri, Uri albumArtUri) {
    this.id = id;
    this.title = title;
    this.artist = artist;
    this.duration = duration;
    this.uri = uri;
    this.albumArtUri = albumArtUri;
  }
}