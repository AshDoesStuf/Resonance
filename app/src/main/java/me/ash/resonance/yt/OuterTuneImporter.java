package me.ash.resonance.yt;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.ash.resonance.MusicLoader;
import me.ash.resonance.db.AppDatabase;
import me.ash.resonance.db.ImportedSongDao;
import me.ash.resonance.db.ImportedSongEntity;

/**
 * Scans the device's MediaStore for files exported by OuterTune (or any NewPipe/ViMusic-based
 * app) whose filenames embed a YouTube video ID in the format:
 * <p>
 * Song Title [dQw4w9WgXcQ].opus   (most common)
 * Song Title [dQw4w9WgXcQ].m4a
 * Song Title [dQw4w9WgXcQ].mp3
 * <p>
 * For each matched file it resolves full metadata from YouTube via {@link YtMusicService},
 * giving you a {@link YtTrack} you can use to fill in artist, album, artwork, etc.
 * <p>
 * ── Usage ────────────────────────────────────────────────────────────────────
 * <p>
 * OuterTuneImporter.scan(context, new OuterTuneImporter.ImportCallback() {
 * <p>
 * public void onProgress(int done, int total, String title) {
 * // update a progress bar
 * }
 * <p>
 * public void onComplete(List<ImportedSong> songs) {
 * // songs now have YT metadata attached — persist or display them
 * }
 * <p>
 * public void onError(Exception e) { ... }
 * });
 * <p>
 * ── Threading ────────────────────────────────────────────────────────────────
 * All MediaStore queries and YT network calls are done off the main thread.
 * Callbacks are invoked on the same background thread — post to a Handler /
 * runOnUiThread() as needed.
 */
public class OuterTuneImporter {

  private static final String TAG = "OuterTuneImporter";

  /**
   * YouTube video IDs are exactly 11 characters: [A-Za-z0-9_-]
   * OuterTune wraps them in square brackets at the end of the filename.
   */
  private static final Pattern YT_ID_PATTERN = Pattern.compile("\\[([A-Za-z0-9_\\-]{11})]");

  // ── Public data model ─────────────────────────────────────────────────────

  /**
   * Scans MediaStore for OuterTune-exported files and fetches YouTube metadata for each.
   * Safe to call from any thread — runs its own background work internally.
   */
  public static void scan(Context context, ImportCallback callback) {
    new Thread(() -> {
      try {
        ImportedSongDao dao = AppDatabase.get(context).importedSongDao();
        List<ImportedSong> candidates = findCandidates(context);

        List<ImportedSong> toFetch = new ArrayList<>();
        for (ImportedSong s : candidates) {
          if (dao.countByVideoId(s.videoId) == 0) toFetch.add(s);
        }

        if (toFetch.isEmpty()) {
          callback.onComplete(toFetch);
          return;
        }

        int total = toFetch.size();
        AtomicInteger done = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(total);
        ExecutorService pool = Executors.newFixedThreadPool(4); // tune this

        for (ImportedSong song : toFetch) {
          pool.submit(() -> {
            try {
              YtMusicService.get().fetchTrack(song.videoId,
                      new YtMusicService.TrackCallback() {
                        @Override
                        public void onTrack(YtTrack track) {
                          try {
                            song.ytTrack = track;
                            ImportedSongEntity entity = new ImportedSongEntity();
                            entity.localId = song.localId;
                            entity.videoId = song.videoId;
                            entity.title = track.title;
                            entity.artist = track.artist;
                            entity.thumbnailUrl = track.thumbnailUrl;
                            entity.durationSeconds = track.durationSeconds;
                            entity.duration = String.format("%d:%02d",
                                    track.durationSeconds / 60,
                                    track.durationSeconds % 60);
                            dao.insert(entity);
                          } finally {
                            int n = done.incrementAndGet();
                            callback.onProgress(n, total, track.title);
                            latch.countDown();
                          }
                        }

                        @Override
                        public void onError(Exception e) {
                          song.metadataError = e;
                          int n = done.incrementAndGet();
                          callback.onProgress(n, total, song.fileName);
                          latch.countDown();
                        }
                      });
            } catch (Throwable t) {
              // fetchTrack itself threw before even starting — still must count down
              Log.e(TAG, "fetchTrack dispatch failed for " + song.videoId, t);
              song.metadataError = new Exception(t);
              done.incrementAndGet();
              latch.countDown();
            }
          });
        }

        boolean finished = latch.await(5, TimeUnit.MINUTES);
        if (!finished) {
          Log.e(TAG, "Import timed out — some fetches never completed");
        }
        pool.shutdown();
        MusicLoader.invalidate();
        callback.onComplete(toFetch);

      } catch (Exception e) {
        Log.e(TAG, "scan failed", e);
        callback.onError(e);
      }
    }, "OuterTuneImporter").start();
  }

  // ── Callback ──────────────────────────────────────────────────────────────

  /**
   * Queries MediaStore for audio files whose display name contains a YouTube ID
   * in the {@code [xxxxxxxxxxx]} format used by OuterTune.
   */
  private static List<ImportedSong> findCandidates(Context context) {
    List<ImportedSong> results = new ArrayList<>();

    String[] projection = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA          // full file path (backup)
    };

    // No WHERE clause — we filter in-memory via regex so we catch all formats
    try (Cursor cursor = context.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {

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
    }

    return results;
  }

  // ── Entry point ───────────────────────────────────────────────────────────

  public interface ImportCallback {
    /**
     * Called after each metadata fetch completes.
     */
    void onProgress(int done, int total, String trackTitle);

    /**
     * Called when all songs have been processed.
     */
    void onComplete(List<ImportedSong> songs);

    /**
     * Called on a fatal error (e.g. MediaStore unavailable).
     */
    void onError(Exception e);
  }

  // ── MediaStore scan ───────────────────────────────────────────────────────

  /**
   * A local file paired with its resolved YouTube metadata.
   */
  public static class ImportedSong {
    /**
     * MediaStore _ID — can be used to build a playback URI immediately.
     */
    public final long localId;
    /**
     * The raw filename as stored in MediaStore (no path).
     */
    public final String fileName;
    /**
     * 11-char YouTube video ID extracted from the filename.
     */
    public final String videoId;
    /**
     * Full metadata fetched from YouTube.
     * May be null if the fetch failed — check {@link #metadataError} in that case.
     */
    public YtTrack ytTrack;
    /**
     * Non-null when metadata fetch failed.
     */
    public Exception metadataError;

    public ImportedSong(long localId, String fileName, String videoId) {
      this.localId = localId;
      this.fileName = fileName;
      this.videoId = videoId;
    }

    /**
     * Convenience: direct-playback URI for the local file.
     */
    public Uri localUri() {
      return Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(localId));
    }
  }
}