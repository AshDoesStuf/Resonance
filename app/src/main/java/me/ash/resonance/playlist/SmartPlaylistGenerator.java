package me.ash.resonance.playlist;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.ash.resonance.MusicLoader;
import me.ash.resonance.db.AppDatabase;
import me.ash.resonance.db.SongMetadataDao;
import me.ash.resonance.db.SongMetadataEntity;
import me.ash.resonance.song.Song;

public class SmartPlaylistGenerator {

  /**
   * Generates a playlist of songs matching a specific genre using ONLY cached metadata.
   * Never triggers network lookups.
   */
  public static List<Song> generateGenreMix(Context context, String targetGenre) {
    SongMetadataDao dao = AppDatabase.get(context).songMetadataDao();
    List<Song> allSongs = MusicLoader.loadSongs(context);
    List<Song> result = new ArrayList<>();

    for (Song song : allSongs) {
      SongMetadataEntity metadata = dao.getById(song.id);
      // REQUIREMENT: Playlist generation must use cached metadata only.
      // If metadata is unavailable or not COMPLETED, skip the track.
      if (metadata != null && "COMPLETED".equals(metadata.status)) {
        if (targetGenre.equalsIgnoreCase(metadata.genre)) {
          result.add(song);
          continue;
        }

        if (metadata.genres != null) {
          String[] genreList = metadata.genres.split(",");
          for (String g : genreList) {
            if (targetGenre.equalsIgnoreCase(g.trim())) {
              result.add(song);
              break;
            }
          }
        }
      }
    }

    Collections.shuffle(result);
    return result;
  }

  /**
   * Generates a "Discovery" mix of tracks that have been enriched but not played much.
   */
  public static List<Song> generateDiscoveryMix(Context context) {
    // Implementation would use both Metadata and PlaybackStats
    return new ArrayList<>();
  }
}
