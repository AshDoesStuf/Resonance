package me.ash.resonance.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Caches metadata for online songs (e.g. those added to playlists)
 * so they can be displayed in the UI without immediate network fetches.
 */
@Entity(tableName = "remote_songs")
public class RemoteSongEntity {
  @PrimaryKey
  @NonNull
  public String videoId;
  public String title;
  public String artist;
  public String album;
  public String duration; // "m:ss"
  public long durationSeconds;
  public String thumbnailUrl;
}
