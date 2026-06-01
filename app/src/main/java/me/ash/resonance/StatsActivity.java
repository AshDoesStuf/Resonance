package me.ash.resonance;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.ash.resonance.playlist.PlaybackStatsManager;
import me.ash.resonance.song.Song;
import me.ash.resonance.stats.StatArtistAdapter;
import me.ash.resonance.stats.StatSongAdapter;

public class StatsActivity extends AppCompatActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_stats);

    findViewById(R.id.btnBack).setOnClickListener(v -> finish());

    // Load all songs off-main-thread, then populate
    new Thread(() -> {
      List<Song> allSongs = MusicLoader.loadSongs(this);
      Map<String, Song> songMap = new HashMap<>();
      for (Song s : allSongs) songMap.put(String.valueOf(s.id), s);

      PlaybackStatsManager stats = PlaybackStatsManager.get(this);
      List<String> mostPlayedIds = stats.getMostPlayed(10);
      List<String> recentIds = stats.getRecentlyPlayed(10);

      // Build top artists from most-played songs
      Map<String, Integer> artistCounts = new HashMap<>();
      for (String id : stats.getMostPlayed(200)) {
        Song s = songMap.get(id);
        if (s == null) continue;
        artistCounts.put(s.artist, artistCounts.getOrDefault(s.artist, 0) + 1);
      }
      List<Map.Entry<String, Integer>> topArtists = new ArrayList<>(artistCounts.entrySet());
      topArtists.sort((a, b) -> b.getValue() - a.getValue());
      if (topArtists.size() > 10) topArtists = topArtists.subList(0, 10);

      // Total play count across all songs
      int totalPlays = 0;
      for (String id : stats.getMostPlayed(9999)) {
        // getMostPlayed returns all if limit > total — we just want a sum
        // We'll count via a workaround below
        totalPlays++;
      }

      final int finalTotalPlays = mostPlayedIds.size(); // placeholder — see note below
      final List<Map.Entry<String, Integer>> finalTopArtists = topArtists;

      runOnUiThread(() -> {
        // Overview cards
        ((TextView) findViewById(R.id.tvTotalSongsCount)).setText(String.valueOf(allSongs.size()));
        ((TextView) findViewById(R.id.tvListenTimeCount)).setText(
                String.valueOf(PlaybackStatsManager.get(this).getTotalPlayCount())
        );

        // Most Played
        RecyclerView rvMost = findViewById(R.id.rvMostPlayed);
        rvMost.setLayoutManager(new LinearLayoutManager(this));
        rvMost.setAdapter(new StatSongAdapter(mostPlayedIds, songMap, true));

        // Recently Played
        RecyclerView rvRecent = findViewById(R.id.rvRecentlyPlayed);
        rvRecent.setLayoutManager(new LinearLayoutManager(this));
        rvRecent.setAdapter(new StatSongAdapter(recentIds, songMap, false));

        // Top Artists
        RecyclerView rvArtists = findViewById(R.id.rvTopArtists);
        rvArtists.setLayoutManager(new LinearLayoutManager(this));
        rvArtists.setAdapter(new StatArtistAdapter(finalTopArtists));
      });
    }).start();
  }
}