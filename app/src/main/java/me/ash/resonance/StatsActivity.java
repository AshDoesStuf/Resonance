package me.ash.resonance;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.session.MediaController;
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

  private MiniPlayerManager miniPlayerManager;
  private MediaController controller;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_stats);

    findViewById(R.id.btnBack).setOnClickListener(v -> finish());

    miniPlayerManager = new MiniPlayerManager(this);
    ((ResonanceApp) getApplication()).getSharedController(ctrl -> {
      controller = ctrl;
      miniPlayerManager.init(controller);
    });

    // Load all songs off-main-thread, then populate
    new Thread(() -> {
      List<Song> allSongs = MusicLoader.loadSongs(this);
      Map<String, Song> songMap = new HashMap<>();
      for (Song s : allSongs) songMap.put(String.valueOf(s.id), s);

      PlaybackStatsManager stats = PlaybackStatsManager.get(this);

      // Filter to only existing songs and limit to 10
      List<String> mostPlayedIds = new ArrayList<>();
      for (String id : stats.getMostPlayed(100)) {
        if (songMap.containsKey(id)) {
          mostPlayedIds.add(id);
          if (mostPlayedIds.size() >= 10) break;
        }
      }

      List<String> recentIds = new ArrayList<>();
      for (String id : stats.getRecentlyPlayed(100)) {
        if (songMap.containsKey(id)) {
          recentIds.add(id);
          if (recentIds.size() >= 10) break;
        }
      }

      // Build top artists by summing play counts of all their songs
      Map<String, Integer> artistCounts = new HashMap<>();
      Map<String, Integer> playCounts = stats.getPlayCounts();
      for (Map.Entry<String, Integer> entry : playCounts.entrySet()) {
        Song s = songMap.get(entry.getKey());
        if (s == null) continue;
        artistCounts.put(s.artist, artistCounts.getOrDefault(s.artist, 0) + entry.getValue());
      }

      List<Map.Entry<String, Integer>> topArtists = new ArrayList<>(artistCounts.entrySet());
      topArtists.sort((a, b) -> b.getValue() - a.getValue());
      if (topArtists.size() > 10) topArtists = topArtists.subList(0, 10);

      final List<String> finalMostPlayed = mostPlayedIds;
      final List<String> finalRecent = recentIds;
      final List<Map.Entry<String, Integer>> finalTopArtists = topArtists;

      runOnUiThread(() -> {
        // Overview cards
        ((TextView) findViewById(R.id.tvTotalSongsCount)).setText(String.valueOf(allSongs.size()));
        ((TextView) findViewById(R.id.tvListenTimeCount)).setText(
                String.valueOf(stats.getTotalPlayCount())
        );

        // Most Played
        RecyclerView rvMost = findViewById(R.id.rvMostPlayed);
        rvMost.setLayoutManager(new LinearLayoutManager(this));
        rvMost.setAdapter(new StatSongAdapter(finalMostPlayed, songMap, playCounts, true));

        // Recently Played
        RecyclerView rvRecent = findViewById(R.id.rvRecentlyPlayed);
        rvRecent.setLayoutManager(new LinearLayoutManager(this));
        rvRecent.setAdapter(new StatSongAdapter(finalRecent, songMap, playCounts, false));

        // Top Artists
        RecyclerView rvArtists = findViewById(R.id.rvTopArtists);
        rvArtists.setLayoutManager(new LinearLayoutManager(this));
        rvArtists.setAdapter(new StatArtistAdapter(finalTopArtists));
      });
    }).start();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (miniPlayerManager != null) {
      miniPlayerManager.detach();
    }
  }
}