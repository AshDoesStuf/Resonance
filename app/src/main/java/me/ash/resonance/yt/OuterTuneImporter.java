package me.ash.resonance.yt;

import static java.lang.String.valueOf;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.ash.resonance.db.ImportedSongEntity;

/**
 * Utility class for scanning the device's MediaStore for files exported by OuterTune.
 * The actual import work is handled by {@link OuterTuneImportWorker}.
 */
public class OuterTuneImporter {

  private static final String TAG = "OuterTuneImporter";

  /**
   * YouTube video IDs are exactly 11 characters: [A-Za-z0-9_-]
   * OuterTune wraps them in square brackets at the end of the filename.
   */
  private static final Pattern YT_ID_PATTERN = Pattern.compile("\\[([A-Za-z0-9_\\-]{11})]");

  /**
   * Queries MediaStore for audio files whose display name contains a YouTube ID
   * in the {@code [xxxxxxxxxxx]} format used by OuterTune.
   */
  static List<ImportedSong> findCandidates(Context context) {
    List<ImportedSong> results = new ArrayList<>();

    String[] projection = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA
    };

    try (Cursor cursor = context.getContentResolver().query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            MediaStore.Audio.Media.DATE_ADDED + " DESC")) {

      if (cursor == null) return results;

      int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
      int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);

      while (cursor.moveToNext()) {
        String name = cursor.getString(nameCol);
        if (name == null) continue;

        Matcher m = YT_ID_PATTERN.matcher(name);
        if (m.find()) {
          String videoId = m.group(1);
          long localId = cursor.getLong(idCol);
          results.add(new ImportedSong(localId, name, videoId));
          Log.d(TAG, "found: " + name + " → " + videoId);
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "Failed to query MediaStore", e);
    }

    return results;
  }

  static ImportedSongEntity buildEntity(ImportedSong song) {
    YtTrack track = song.ytTrack;
    ImportedSongEntity entity = new ImportedSongEntity();
    entity.localId = song.localId;
    entity.videoId = song.videoId;
    entity.title = track.title;
    entity.artist = track.artist;
    entity.album = track.albumName != null ? track.albumName : "YouTube Music";
    entity.thumbnailUrl = track.thumbnailUrl;
    entity.durationSeconds = track.durationSeconds;
    entity.duration = String.format("%d:%02d",
            track.durationSeconds / 60,
            track.durationSeconds % 60);
    return entity;
  }

  /**
   * A local file paired with its resolved YouTube metadata.
   */
  public static class ImportedSong {
    public final long localId;
    public final String fileName;
    public final String videoId;
    public YtTrack ytTrack;

    public ImportedSong(long localId, String fileName, String videoId) {
      this.localId = localId;
      this.fileName = fileName;
      this.videoId = videoId;
    }

    public Uri localUri() {
      return Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, valueOf(localId));
    }
  }
}
