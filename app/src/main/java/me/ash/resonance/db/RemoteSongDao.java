package me.ash.resonance.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface RemoteSongDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void insert(RemoteSongEntity song);

  @Query("SELECT * FROM remote_songs")
  List<RemoteSongEntity> getAll();

  @Query("SELECT * FROM remote_songs WHERE videoId = :videoId LIMIT 1")
  RemoteSongEntity getByVideoId(String videoId);
}
