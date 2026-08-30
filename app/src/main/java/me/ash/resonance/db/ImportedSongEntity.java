package me.ash.resonance.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "imported_songs")
public class ImportedSongEntity {
  @PrimaryKey
  public long localId;
  public String videoId;
  public String title;
  public String artist;
  public String album;
  public String thumbnailUrl;
  public long durationSeconds;
  public String duration; // formatted "m:ss"
}
