package me.ash.resonance.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface SongMetadataDao {

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  void insert(SongMetadataEntity metadata);

  @Update
  void update(SongMetadataEntity metadata);

  @Query("SELECT * FROM song_metadata WHERE songId = :songId")
  SongMetadataEntity getById(String songId);

  @Query("SELECT * FROM song_metadata WHERE status = 'PENDING' AND retryCount < :maxRetries ORDER BY lastRetryTimestamp ASC LIMIT :limit")
  List<SongMetadataEntity> getPending(int maxRetries, int limit);

  @Query("SELECT COUNT(*) FROM song_metadata WHERE status = 'PENDING'")
  int getPendingCount();

  @Query("SELECT * FROM song_metadata")
  List<SongMetadataEntity> getAll();
}
