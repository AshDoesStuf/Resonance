package me.ash.resonance.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface DownloadedSongDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void insert(DownloadedSongEntity entity);

  @Query("SELECT * FROM downloaded_songs")
  List<DownloadedSongEntity> getAll();

  @Query("SELECT mediaStoreId FROM downloaded_songs WHERE videoId = :videoId LIMIT 1")
  long getMediaStoreIdForVideo(String videoId);

  @Query("SELECT videoId FROM downloaded_songs")
  List<String> getAllVideoIds();

  @Query("DELETE FROM downloaded_songs WHERE videoId = :videoId")
  void deleteByVideoId(String videoId);
}