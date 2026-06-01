package me.ash.resonance.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Tracks every successfully downloaded YT song.
 */
@Entity(tableName = "downloaded_songs")
public class DownloadedSongEntity {
  @PrimaryKey
  public long mediaStoreId;   // the ID MediaStore assigned after download
  public String videoId;      // YT video ID  e.g. "dQw4w9WgXcQ"
  public String title;
  public String artist;
  public String duration;     // "m:ss"
  public String thumbnailUrl;
}