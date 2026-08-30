package me.ash.resonance.yt;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.ash.resonance.db.AppDatabase;
import me.ash.resonance.db.DownloadedSongEntity;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Downloads YT audio streams and registers them properly with MediaStore
 * so they appear in music apps (not the gallery).
 * <p>
 * Also maintains a list of downloaded video IDs, used by the
 * "Downloads" smart playlist in PlaylistManager.
 */
public class YtDownloadManager {

  private static final String TAG = "YtDownloadManager";
  private static final String PREFS = "yt_downloads";
  private static final String KEY_IDS = "downloaded_ids";

  private static YtDownloadManager instance;
  private final Context ctx;
  private final ExecutorService executor = Executors.newFixedThreadPool(4);

  private YtDownloadManager(Context ctx) {
    this.ctx = ctx.getApplicationContext();
  }

  public static YtDownloadManager get(Context ctx) {
    if (instance == null) instance = new YtDownloadManager(ctx);
    return instance;
  }

  /**
   * Resolves the stream URL then downloads the file properly as audio.
   */
  @OptIn(markerClass = UnstableApi.class)
  public void download(YtTrack track, DownloadCallback callback) {
    executor.submit(() -> {
      new android.os.Handler(android.os.Looper.getMainLooper())
              .post(() -> callback.onProgress("Resolving stream…"));
      try {
        StreamData stream = YtMusicService.get().resolveStreamUrlBlocking(track.videoId);
        if (stream == null) {
          new android.os.Handler(android.os.Looper.getMainLooper())
                  .post(() -> callback.onError("Couldn't resolve stream URL"));
          return;
        }
        doDownload(track, stream, callback);
      } catch (Exception e) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> callback.onError("Download error: " + e.getMessage()));
      }
    });
  }

  @OptIn(markerClass = UnstableApi.class)
  private void doDownload(YtTrack track, StreamData stream, DownloadCallback callback) {
    if (stream.mimeType() == null || stream.mimeType().startsWith("application/x-mpegURL")) {
      callback.onError("This track is only available as a live/streaming source and can't be downloaded");
      return;
    }

    // Always save as .m4a — even if the raw stream is webm/opus,
    // we rename it so MediaStore picks it up as audio
    boolean isWebm = stream.mimeType() != null && stream.mimeType().contains("webm");
    // For webm/opus streams we still save as .opus (audio), NOT .webm (video)
    String ext = isWebm ? ".opus" : ".m4a";
    String mimeType = isWebm ? "audio/opus" : "audio/mp4";

    String safeTitle = track.title.replaceAll("[\\\\/:*?\"<>|]", "_");
    String fileName = safeTitle + " [" + track.videoId + "]" + ext;

    callback.onProgress("Downloading " + track.title + "…");

    // At the top of doDownload(), before building the Request:
    OkHttpClient downloadClient = YtMusicService.get().http.newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    try {
      Request req = new Request.Builder()
              .url(stream.url())
              .header("User-Agent", stream.userAgent())
              .header("Referer", "https://music.youtube.com/")
              .header("Origin", "https://music.youtube.com")
              .build();

      try (Response resp = downloadClient.newCall(req).execute()) {
        if (!resp.isSuccessful() || resp.body() == null) {
          callback.onError("Server returned " + resp.code());
          return;
        }

        // Android 10+ — write directly into MediaStore Audio collection
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Audio.Media.TITLE, track.title);
        values.put(MediaStore.Audio.Media.ARTIST,
                track.artist != null ? track.artist : "Unknown Artist");
        values.put(MediaStore.Audio.Media.ALBUM,
                track.albumName != null ? track.albumName : "YouTube Music");
        if (track.durationSeconds > 0) {
          values.put(MediaStore.Audio.Media.DURATION, track.durationSeconds * 1000L);
        }
        values.put(MediaStore.Audio.Media.MIME_TYPE, mimeType);
        values.put(MediaStore.Audio.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/Resonance");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);

        Uri collection = MediaStore.Audio.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = ctx.getContentResolver().insert(collection, values);

        if (itemUri == null) {
          callback.onError("MediaStore insert failed");
          return;
        }

        try {
          try (java.io.OutputStream out =
                       ctx.getContentResolver().openOutputStream(itemUri)) {
            if (out == null) throw new Exception("null output stream");
            pipe(resp.body().byteStream(), out);
          }
        } catch (Exception e) {
          ctx.getContentResolver().delete(itemUri, null, null);
          throw e; // let the outer catch report the error as before
        }

        // Mark as ready — this is what makes it visible to other apps
        values.clear();
        values.put(MediaStore.Audio.Media.IS_PENDING, 0);
        ctx.getContentResolver().update(itemUri, values, null, null);

        // Re-apply metadata IMMEDIATELY after marking as not pending.
        // Android's MediaScanner often wipes manually inserted metadata
        // when it first indexes the file. This second update forces it back.
        values.clear();
        values.put(MediaStore.Audio.Media.TITLE, track.title);
        values.put(MediaStore.Audio.Media.ARTIST,
                track.artist != null ? track.artist : "Unknown Artist");
        values.put(MediaStore.Audio.Media.ALBUM,
                track.albumName != null ? track.albumName : "YouTube Music");
        ctx.getContentResolver().update(itemUri, values, null, null);

        // THEN save to Room and notify the library
        saveToRoom(track, itemUri);

        callback.onSuccess(track.title);
      }

    } catch (Exception e) {
      Log.e(TAG, "Download failed", e);
      callback.onError("Download failed: " + e.getMessage());
    }
  }

  private void pipe(InputStream in, java.io.OutputStream out) throws Exception {
    byte[] buf = new byte[65536];
    int n;
    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    out.flush();
  }

  // ── Downloaded IDs tracking ───────────────────────────────────────────

  public boolean isDownloaded(String videoId) {
    return AppDatabase.get(ctx).downloadedSongDao().getMediaStoreIdForVideo(videoId) != 0;
  }

  /**
   * Returns MediaStore IDs (as strings) so PlaylistDetailSheet can match them.
   */
  public List<String> getDownloadedMediaStoreIds() {
    List<String> ids = new ArrayList<>();
    for (DownloadedSongEntity e : AppDatabase.get(ctx).downloadedSongDao().getAll()) {
      ids.add(String.valueOf(e.mediaStoreId));
    }
    return ids;
  }

  private void saveToRoom(YtTrack track, Uri mediaStoreUri) {
    if (mediaStoreUri == null) return;

    // Extract the numeric ID from the content URI
    // e.g. content://media/external/audio/media/1234  →  1234
    long mediaStoreId;
    try {
      mediaStoreId = Long.parseLong(mediaStoreUri.getLastPathSegment());
    } catch (Exception e) {
      Log.e(TAG, "Could not parse MediaStore ID from URI: " + mediaStoreUri);
      return;
    }

    // Format duration from seconds to "m:ss"
    long durSec = Math.max(track.durationSeconds, 0);
    long m = durSec / 60;
    long s = durSec % 60;
    String duration = m + ":" + String.format("%02d", s);

    DownloadedSongEntity entity = new DownloadedSongEntity();
    entity.mediaStoreId = mediaStoreId;
    entity.videoId = track.videoId;
    entity.title = track.title;
    entity.artist = track.artist != null ? track.artist : "Unknown Artist";
    entity.album = track.albumName != null ? track.albumName : "YouTube Music";
    entity.duration = duration;
    entity.durationSeconds = durSec;
    entity.thumbnailUrl = track.thumbnailUrl;

    AppDatabase.get(ctx).downloadedSongDao().insert(entity);

    // Bust the cache so MusicLoader picks up the new song on next load
    me.ash.resonance.MusicLoader.invalidate();

    // Notify the rest of the app
    android.content.Intent intent = new android.content.Intent(
            me.ash.resonance.MusicLibraryEvent.ACTION_LIBRARY_CHANGED);
    androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(ctx).sendBroadcast(intent);
  }

  public interface DownloadCallback {
    void onProgress(String message);

    void onSuccess(String title);

    void onError(String reason);
  }
}