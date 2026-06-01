package me.ash.resonance.album;

import android.net.Uri;

public class Album {
  public final long id;
  public final String name;
  public final String artist;
  public final int songCount;
  public final Uri artUri;

  public Album(long id, String name, String artist, int songCount, Uri artUri) {
    this.id = id;
    this.name = name;
    this.artist = artist;
    this.songCount = songCount;
    this.artUri = artUri;
  }
}