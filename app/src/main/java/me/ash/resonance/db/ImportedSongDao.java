package me.ash.resonance.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ImportedSongDao {

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  void insert(ImportedSongEntity song);

  @Query("SELECT * FROM imported_songs")
  List<ImportedSongEntity> getAll();

  @Query("SELECT COUNT(*) FROM imported_songs WHERE videoId = :videoId")
  int countByVideoId(String videoId);

  @Query("SELECT * FROM imported_songs WHERE videoId = :videoId LIMIT 1")
  ImportedSongEntity getByVideoId(String videoId);
}