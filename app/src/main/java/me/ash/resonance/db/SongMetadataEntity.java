package me.ash.resonance.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "song_metadata")
public class SongMetadataEntity {
  @PrimaryKey
  @NonNull
  public String songId; // can be videoId or a unique string for local files

  public String status = "PENDING"; // PENDING, COMPLETED, FAILED
  public int retryCount = 0;
  public long lastRetryTimestamp = 0;
  public long lastUpdated = 0;

  // Enriched data
  public String genre; // Primary normalized genre
  public String genres; // Serialized list
  public String tags; // Serialized list of raw tags
  public String similarArtists; // Serialized list of artist names
  public String releaseDate;
  public String lastFmUrl;
  public String artistFmUrl;
  public String trackFmUrl;

  // MusicBrainz IDs
  public String mbid; // Recording ID
  public String artistMbid;
  public String albumMbid;
  public String releaseMbid;

  public String artistBio;
  public String albumDescription;

  public String providerSource; // e.g., "lastfm,musicbrainz"

  public SongMetadataEntity() {
  }

  public SongMetadataEntity(@NonNull String songId) {
    this.songId = songId;
  }
}
