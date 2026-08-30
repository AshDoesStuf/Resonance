package me.ash.resonance.playlist;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.ash.resonance.MusicLoader;
import me.ash.resonance.db.AppDatabase;
import me.ash.resonance.db.DownloadedSongDao;
import me.ash.resonance.db.DownloadedSongEntity;
import me.ash.resonance.db.ImportedSongDao;
import me.ash.resonance.db.ImportedSongEntity;
import me.ash.resonance.song.Song;

public class M3uImporter {

  /**
   * Parses an M3U/M3U8 file from the given URI, matches tracks against
   * the device MediaStore, and saves the result as a named playlist.
   * Must be called from the main thread — spawns its own background thread.
   */
  public static void importFromUri(Context context, Uri fileUri, ImportCallback callback) {
    new Thread(() -> {
      try {
        // ── 1. Parse the M3U file ─────────────────────────────────
        List<M3uEntry> entries = parseM3u(context, fileUri);
        for (M3uEntry entry : entries) {
          Log.d("M3UIMPORTER", entry.path);
        }

        // ── 2. Derive playlist name from filename ─────────────────
        String playlistName = resolveFileName(context, fileUri);
        if (playlistName.toLowerCase().endsWith(".m3u8"))
          playlistName = playlistName.substring(0, playlistName.length() - 5);
        else if (playlistName.toLowerCase().endsWith(".m3u"))
          playlistName = playlistName.substring(0, playlistName.length() - 4);

        // Make name unique
        playlistName = uniqueName(context, playlistName);

        // ── 3. Build filename → Song map from MediaStore ──────────
        List<Song> allSongs = MusicLoader.loadSongs(context);
        Map<String, Song> byFilename = new HashMap<>();
        Map<String, Song> byId = new HashMap<>();
        Map<String, Song> byMetadata = new HashMap<>();

        for (Song s : allSongs) {
          String filename = getFilename(context, s);
          if (filename != null) byFilename.put(filename.toLowerCase(), s);
          byId.put(s.id, s);

          if (s.title != null && s.artist != null) {
            byMetadata.put((s.artist + " - " + s.title).toLowerCase(), s);
            byMetadata.put((s.title + " - " + s.artist).toLowerCase(), s);
          }
        }

        // Also index by videoId for YouTube matching
        ImportedSongDao iDao = AppDatabase.get(context).importedSongDao();
        DownloadedSongDao dDao = AppDatabase.get(context).downloadedSongDao();

        for (ImportedSongEntity e : iDao.getAll()) {
          Song s = byId.get(String.valueOf(e.localId));
          if (s != null) byId.put(e.videoId, s);
        }
        for (DownloadedSongEntity e : dDao.getAll()) {
          Song s = byId.get(String.valueOf(e.mediaStoreId));
          if (s != null) byId.put(e.videoId, s);
        }

        // ── 4. Match M3U entries to songs ─────────────────────────
        int total = entries.size();
        int matched = 0;
        PlaylistManager pm = PlaylistManager.get(context);
        pm.createPlaylist(playlistName);

        for (M3uEntry entry : entries) {
          Song song = null;
          String path = entry.path;

          // Check if this entry is a YouTube URL
          String videoId = extractYouTubeVideoId(path);
          if (videoId != null) {
            song = byId.get(videoId);
          }

          // Metadata matching (from #EXTINF)
          if (song == null && entry.metadata != null) {
            song = byMetadata.get(entry.metadata.toLowerCase());
          }

          // Fall back to filename matching for normal M3U file paths
          if (song == null) {
            String filename = lastSegment(path).toLowerCase();
            song = byFilename.get(filename);
          }

          if (song != null) {
            pm.addToPlaylist(playlistName, song.id);
            matched++;
          }
        }

        final String finalName = playlistName;
        final int finalMatched = matched;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                callback.onDone(finalName, finalMatched, total));

      } catch (Exception e) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                callback.onError(e.getMessage()));
      }
    }).start();
  }

  /**
   * Extracts the YouTube video ID from a URL.
   * Handles https://youtube.com/watch?v=ID and https://youtu.be/ID forms.
   * Returns null if the path is not a YouTube URL.
   */
  private static String extractYouTubeVideoId(String path) {
    if (path == null) return null;
    String lower = path.toLowerCase();

    // youtube.com/watch?v=ID (with possible extra params)
    if (lower.contains("youtube.com/watch")) {
      Uri uri = Uri.parse(path);
      return uri.getQueryParameter("v");
    }

    // youtu.be/ID
    if (lower.contains("youtu.be/")) {
      Uri uri = Uri.parse(path);
      String segment = uri.getLastPathSegment();
      return (segment != null && !segment.isEmpty()) ? segment : null;
    }

    return null;
  }

  private static List<M3uEntry> parseM3u(Context context, Uri uri) throws Exception {
    List<M3uEntry> entries = new ArrayList<>();
    InputStream is = context.getContentResolver().openInputStream(uri);
    if (is == null) throw new Exception("Cannot open file");

    BufferedReader reader = new BufferedReader(new InputStreamReader(is));
    String line;
    String lastMetadata = null;
    while ((line = reader.readLine()) != null) {
      line = line.trim();
      if (line.isEmpty()) continue;
      if (line.startsWith("#EXTINF:")) {
        int comma = line.indexOf(',');
        if (comma >= 0) lastMetadata = line.substring(comma + 1).trim();
        continue;
      }
      if (line.startsWith("#")) continue;

      M3uEntry entry = new M3uEntry();
      entry.path = line;
      entry.metadata = lastMetadata;
      entries.add(entry);
      lastMetadata = null;
    }
    reader.close();
    return entries;
  }

  // ── M3U parser ────────────────────────────────────────────────────────

  private static String resolveFileName(Context context, Uri uri) {
    // Try content resolver display name first (SAF URIs)
    try (Cursor c = context.getContentResolver().query(
            uri,
            new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
            null, null, null)) {
      if (c != null && c.moveToFirst()) {
        String name = c.getString(0);
        if (name != null && !name.isEmpty()) return name;
      }
    } catch (Exception ignored) {
    }
    // Fallback: last path segment
    String path = uri.getPath();
    return path != null ? lastSegment(path) : "Imported Playlist";
  }

  // ── Helpers ───────────────────────────────────────────────────────────

  private static String getFilename(Context context, Song song) {
    // Query MediaStore for the DATA (file path) column
    try (Cursor c = context.getContentResolver().query(
            song.uri,
            new String[]{MediaStore.Audio.Media.DATA},
            null, null, null)) {
      if (c != null && c.moveToFirst()) {
        String path = c.getString(0);
        if (path != null) return lastSegment(path);
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private static String lastSegment(String path) {
    // Handle both / and \ separators
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  private static String uniqueName(Context context, String name) {
    Map<String, java.util.List<String>> all =
            PlaylistManager.get(context).getAllPlaylists();
    if (!all.containsKey(name)) return name;
    int suffix = 2;
    while (all.containsKey(name + " (" + suffix + ")")) suffix++;
    return name + " (" + suffix + ")";
  }

  public interface ImportCallback {
    /**
     * Called on the main thread when import finishes.
     */
    void onDone(String playlistName, int matched, int total);

    void onError(String message);
  }

  private static class M3uEntry {
    String path;
    String metadata;
  }
}
