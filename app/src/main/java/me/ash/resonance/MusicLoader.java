package me.ash.resonance;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import me.ash.resonance.db.AppDatabase;
import me.ash.resonance.db.DownloadedSongEntity;
import me.ash.resonance.db.ImportedSongEntity;
import me.ash.resonance.db.RemoteSongEntity;
import me.ash.resonance.song.Song;

public class MusicLoader {

  private static List<Song> cache = null;

  public static void invalidate() {
    cache = null;
  }

  /**
   * Must be called off the main thread.
   */
  public static List<Song> loadSongs(Context context) {
    if (cache != null) return cache;

    List<Song> songs = new ArrayList<>();

    // which columns we want from the MediaStore table
    String[] projection = {
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID
    };

    // only get actual music files, not notification sounds etc.
    String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";

    Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

    Cursor cursor = context.getContentResolver().query(collection, projection, selection, null, MediaStore.Audio.Media.DEFAULT_SORT_ORDER);

    List<ImportedSongEntity> imported = AppDatabase.get(context).importedSongDao().getAll();
    List<DownloadedSongEntity> downloaded = AppDatabase.get(context).downloadedSongDao().getAll();

    // Build a set of localIds that are already imported
    Set<Long> importedIds = new HashSet<>();
    for (ImportedSongEntity e : imported) importedIds.add(e.localId);

    // Map downloaded MediaStore IDs to their entities to prefer our metadata over MediaStore's
    java.util.Map<Long, DownloadedSongEntity> downloadedMap = new java.util.HashMap<>();
    for (DownloadedSongEntity e : downloaded) downloadedMap.put(e.mediaStoreId, e);


    if (cursor != null) {
      int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
      int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
      int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
      int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
      int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);

      while (cursor.moveToNext()) {
        long id = cursor.getLong(idCol);
        if (importedIds.contains(id)) continue;

        String title, artist, album, duration;
        Uri albumArtUri;

        DownloadedSongEntity dl = downloadedMap.get(id);
        if (dl != null) {
          // Prefer metadata from our database for downloaded songs
          title = dl.title;
          artist = dl.artist;
          album = dl.album;
          duration = dl.duration;
          albumArtUri = dl.thumbnailUrl != null ? Uri.parse(dl.thumbnailUrl) : null;
        } else {
          title = cursor.getString(titleCol);
          artist = cursor.getString(artistCol);
          album = cursor.getString(albumCol);
          long durationMs = cursor.getLong(durationCol);

          // convert duration from milliseconds to m:ss
          @SuppressLint("DefaultLocale")
          String d = String.format("%d:%02d", TimeUnit.MILLISECONDS.toMinutes(durationMs), TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60);
          duration = d;

          long albumId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
          albumArtUri = Uri.parse("content://media/external/audio/albumart/" + albumId);
        }

        // build a content URI that MediaPlayer can play directly
        Uri uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));

        songs.add(new Song(String.valueOf(id), title, artist, album, duration, uri, albumArtUri));
      }

      cursor.close();
    }


    for (ImportedSongEntity e : imported) {
      Uri uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(e.localId));
      Uri artUri = e.thumbnailUrl != null ? Uri.parse(e.thumbnailUrl) : null;
      songs.add(new Song(String.valueOf(e.localId), e.title, e.artist, e.album, e.duration, uri, artUri));
    }

    Set<String> seenIds = new HashSet<>();
    for (Song s : songs) seenIds.add(s.id);

    // Add downloaded songs that might have been missed by MediaStore
    for (DownloadedSongEntity e : downloaded) {
      String sid = String.valueOf(e.mediaStoreId);
      if (seenIds.contains(sid)) continue; // Already added
      Uri uri = Uri.withAppendedPath(
              MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
              sid);
      Uri artUri = e.thumbnailUrl != null ? Uri.parse(e.thumbnailUrl) : null;
      songs.add(new Song(sid, e.title, e.artist, e.album, e.duration, uri, artUri));
    }

    // Add remote songs (online songs in playlists)
    List<RemoteSongEntity> remote = AppDatabase.get(context).remoteSongDao().getAll();
    for (RemoteSongEntity e : remote) {
      if (seenIds.contains(e.videoId)) continue;
      Uri uri = Uri.parse("ytmusic://" + e.videoId);
      Uri artUri = e.thumbnailUrl != null ? Uri.parse(e.thumbnailUrl) : null;
      songs.add(new Song(e.videoId, e.title, e.artist, e.album, e.duration, uri, artUri));
    }

    cache = songs;

    // Background enrichment queueing
    me.ash.resonance.metadata.MetadataRepository.get(context).enqueueEnrichmentFor(songs);

    return cache;
  }

  public static List<String> getRecentlyAddedIds(Context context, int limit) {
    List<String> ids = new ArrayList<>();
    String[] projection = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATE_ADDED
    };
    String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
    String sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC";

    try (Cursor cursor = context.getContentResolver().query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, null, sortOrder)) {
      if (cursor != null) {
        int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
        while (cursor.moveToNext() && ids.size() < limit) {
          ids.add(String.valueOf(cursor.getLong(idCol)));
        }
      }
    } catch (Exception ignored) {
    }
    return ids;
  }
}
