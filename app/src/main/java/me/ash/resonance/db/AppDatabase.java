package me.ash.resonance.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {ImportedSongEntity.class, DownloadedSongEntity.class, RemoteSongEntity.class, SongMetadataEntity.class}, version = 4)
public abstract class AppDatabase extends RoomDatabase {

  private static AppDatabase instance;

  public static AppDatabase get(Context context) {
    if (instance == null) {
      instance = Room.databaseBuilder(
                      context.getApplicationContext(),
                      AppDatabase.class,
                      "resonance.db"
              ).fallbackToDestructiveMigration()
              .build();
    }
    return instance;
  }

  public abstract ImportedSongDao importedSongDao();

  public abstract DownloadedSongDao downloadedSongDao();

  public abstract RemoteSongDao remoteSongDao();

  public abstract SongMetadataDao songMetadataDao();
}
