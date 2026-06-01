package me.ash.resonance.playlist;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.ash.resonance.MusicLoader;
import me.ash.resonance.R;
import me.ash.resonance.queue.QueueManager;
import me.ash.resonance.song.Song;
import me.ash.resonance.ui.BaseDetailActivity;
import me.ash.resonance.yt.YtTrack;

public class PlaylistDetailActivity extends BaseDetailActivity
        implements SuggestSongsBottomSheet.OnSuggestionsAddedListener {

  public static final String TAG = "PlaylistDetailSheet";
  private static final String ARG_NAME = "playlist_name";
  private static final String LIKED = "__liked__";

  private ItemTouchHelper touchHelper;
  private PlaylistDetailAdapter adapter;
  private List<Song> playlistSongs; // promote to field
  private String playlistName;      // promote to field
  private boolean isLiked;          // promote to field

  private String currentSort = "NONE";

  private android.content.BroadcastReceiver libraryReceiver;

  public static Intent createIntent(Context context, String name) {
    Intent i = new Intent(context, PlaylistDetailActivity.class);
    i.putExtra(ARG_NAME, name);
    return i;
  }

  @Override
  protected int getLayoutRes() {
    return R.layout.activity_detail;
  }

  @Override
  protected void bindHeader(View root) {
    findViewById(R.id.btnShufflePlaylist).setVisibility(View.VISIBLE);
    findViewById(R.id.btnSortPlaylist).setVisibility(View.VISIBLE);
    findViewById(R.id.btnSuggest).setVisibility(View.VISIBLE);
  }

  @Override
  protected List<Song> loadSongs() {

    playlistName = getIntent().getStringExtra(ARG_NAME);

    if (playlistName == null) return new ArrayList<>();

    isLiked = LIKED.equals(playlistName);

    boolean isMostPlayed = PlaylistManager.SMART_MOST_PLAYED.equals(playlistName);

    boolean isRecentlyPlayed = PlaylistManager.SMART_RECENTLY_PLAYED.equals(playlistName);

    boolean isRecentlyAdded = PlaylistManager.SMART_RECENTLY_ADDED.equals(playlistName);

    boolean isDownloads = PlaylistManager.SMART_DOWNLOADS.equals(playlistName);

    if (!isLiked) {
      currentSort = PlaylistManager.get(this).getPlaylistSort(playlistName);
    }

    List<String> ids;

    if (isLiked) {

      ids = PlaylistManager.get(this).getFavouriteIds();

    } else if (isMostPlayed) {

      ids = PlaybackStatsManager.get(this).getMostPlayed(30);

    } else if (isRecentlyPlayed) {

      ids = PlaybackStatsManager.get(this).getRecentlyPlayed(30);

    } else if (isRecentlyAdded) {

      ids = MusicLoader.getRecentlyAddedIds(this, 30);

    } else if (isDownloads) {

      MusicLoader.invalidate();

      ids = me.ash.resonance.yt.YtDownloadManager.get(this).getDownloadedMediaStoreIds();

    } else {

      ids = PlaylistManager.get(this).getAllPlaylists().getOrDefault(playlistName, new ArrayList<>());
    }

    List<Song> allSongs = MusicLoader.loadSongs(this);

    List<Song> result = new ArrayList<>();

    Map<String, Song> songMap = new HashMap<>();

    for (Song s : allSongs) {
      songMap.put(String.valueOf(s.id), s);
    }

    for (String id : ids) {
      Song song = songMap.get(id);

      if (song != null) {
        result.add(song);
      }
    }

    playlistSongs = result;

    return applySortMode(result, currentSort);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    String name = getIntent().getExtras().getString(ARG_NAME, "");
    boolean isLiked = LIKED.equals(name);
    boolean isMostPlayed = PlaylistManager.SMART_MOST_PLAYED.equals(name);
    boolean isRecentlyPlayed = PlaylistManager.SMART_RECENTLY_PLAYED.equals(name);
    boolean isRecentlyAdded = PlaylistManager.SMART_RECENTLY_ADDED.equals(name);
    boolean isDownloads = PlaylistManager.SMART_DOWNLOADS.equals(name);
    boolean isSmart = isMostPlayed || isRecentlyPlayed || isRecentlyAdded || isDownloads;

    String displayName = isLiked ? "Liked Songs" : isMostPlayed ? "Most Played" : isRecentlyPlayed ? "Recently Played" : isRecentlyAdded ? "Recently Added" : isDownloads ? "Downloads" : name;


    if (!isLiked) {
      currentSort = PlaylistManager.get(this).getPlaylistSort(name);
    }

    TextView tvTitle = findViewById(R.id.tvDetailTitle);
    tvTitle.setText(displayName);

    // Show edit/delete only for named playlists
    ImageButton btnEdit = findViewById(R.id.btnEditPlaylist);
    ImageButton btnDelete = findViewById(R.id.btnDeletePlaylist);
    ImageButton btnShuffle = findViewById(R.id.btnShufflePlaylist);
    ImageButton btnDuplicate = findViewById(R.id.btnDuplicatePlaylist);

    ImageButton btnSuggest = findViewById(R.id.btnSuggest);
    btnSuggest.setOnClickListener(v -> {
      if (playlistSongs == null) return;
      new SuggestSongsBottomSheet(playlistSongs, playlistName)
              .show(getSupportFragmentManager(), "suggest");
    });

    btnShuffle.setOnClickListener(v -> {
      if (controller == null || playlistSongs == null || playlistSongs.isEmpty()) return;
      List<MediaItem> items = buildMediaItems(playlistSongs);
      List<MediaItem> shuffled = new ArrayList<>(items);
      java.util.Collections.shuffle(shuffled);
      QueueManager.get().setOriginalItems(items);
      controller.setMediaItems(shuffled, 0, 0);
      controller.prepare();
      
      controller.play();
    });

    ImageButton btnSort = findViewById(R.id.btnSortPlaylist);

    if (isLiked || isSmart) {
      btnSort.setVisibility(View.GONE);
      btnEdit.setVisibility(View.GONE);
      btnDelete.setVisibility(View.GONE);
      btnDuplicate.setVisibility(View.GONE);
    } else {
      btnSort.setOnClickListener(v -> {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, btnSort);
        popup.getMenu().add(0, 0, 0, "Default Order");
        popup.getMenu().add(0, 1, 1, "Title (A-Z)");
        popup.getMenu().add(0, 2, 2, "Artist (A-Z)");
        popup.getMenu().add(0, 3, 3, "Duration");
        popup.setOnMenuItemClickListener(item -> {
          switch (item.getItemId()) {
            case 0:
              currentSort = "NONE";
              break;
            case 1:
              currentSort = "TITLE";
              break;
            case 2:
              currentSort = "ARTIST";
              break;
            case 3:
              currentSort = "DURATION";
              break;
          }
          PlaylistManager.get(this).setPlaylistSort(name, currentSort);
          List<Song> sorted = applySortMode(playlistSongs, currentSort);
          adapter.updateSongs(sorted);
          return true;
        });
        popup.show();
      });
    }

    if (!isLiked) {
      btnDuplicate.setVisibility(View.VISIBLE);
      btnDuplicate.setOnClickListener(v -> {
        PlaylistManager.get(this).duplicatePlaylist(name);
        Toast.makeText(this, "Duplicated \"" + name + "\"", Toast.LENGTH_SHORT).show();
      });
    }

    RecyclerView rv = findViewById(R.id.rvDetailSongs);
    rv.setLayoutManager(new LinearLayoutManager(this));
  }

  @Override
  public void onStart() {
    super.onStart();
    libraryReceiver = new android.content.BroadcastReceiver() {
      @Override
      public void onReceive(android.content.Context context, android.content.Intent intent) {
        if (adapter == null) return;
        new Thread(() -> {
          MusicLoader.invalidate();
          List<String> freshIds = me.ash.resonance.yt.YtDownloadManager.get(PlaylistDetailActivity.this).getDownloadedMediaStoreIds();
          List<Song> allSongs = MusicLoader.loadSongs(PlaylistDetailActivity.this);
          playlistSongs = new ArrayList<>();
          for (String id : freshIds) {
            for (Song s : allSongs) {
              if (String.valueOf(s.id).equals(id)) {
                playlistSongs.add(s);
                break;
              }
            }
          }
          runOnUiThread(() -> {
            adapter.updateSongs(applySortMode(playlistSongs, currentSort));
            updateHeader(playlistSongs);
          });
        }).start();
      }
    };
    // Only register for the Downloads smart playlist
    if (PlaylistManager.SMART_DOWNLOADS.equals(playlistName)) {
      androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).registerReceiver(libraryReceiver, new android.content.IntentFilter(me.ash.resonance.MusicLibraryEvent.ACTION_LIBRARY_CHANGED));
    }
  }

  @Override
  public void onStop() {
    super.onStop();
    if (libraryReceiver != null) {
      androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(libraryReceiver);
    }
  }

  @Override
  protected void playSong(List<Song> songs, Song song) {
    super.playSong(songs, song);

    int idx = songs.indexOf(song);

    adapter.setPlayingIdx(idx);
  }

  @Override
  protected void onSongsLoaded(View root, RecyclerView rv, List<Song> songs) {

    playlistSongs = songs;

    updateHeader(songs);

    adapter = new PlaylistDetailAdapter(songs, song -> playSong(playlistSongs, song),

            song -> {

              String id = String.valueOf(song.id);

              if (isLiked) {

                PlaylistManager.get(this).toggleFavourite(id);

              } else {

                PlaylistManager.get(this).removeFromPlaylist(playlistName, id);
              }

              playlistSongs.remove(song);

              updateHeader(playlistSongs);

              adapter.updateSongs(playlistSongs);
            });

    rv.setAdapter(adapter);

    if (!isLiked) {

      adapter.setDragListener(holder -> touchHelper.startDrag(holder));

      touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {

        @Override
        public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {

          int f = from.getAdapterPosition();

          int t = to.getAdapterPosition();

          Collections.swap(playlistSongs, f, t);

          adapter.notifyItemMoved(f, t);

          PlaylistManager.get(PlaylistDetailActivity.this).reorderPlaylist(playlistName, f, t);

          return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
        }
      });

      touchHelper.attachToRecyclerView(rv);
    }
  }

  @Override
  public void onSuggestionsAdded(List<YtTrack> addedTracks) {
    // Reload the playlist from PlaylistManager so newly added YT tracks appear.
    // Re-use the existing loadSongs() + onSongsLoaded() path via a simple refresh.
    runOnUiThread(() -> {
      List<Song> refreshed = loadSongs();          // re-queries PlaylistManager
      if (adapter != null) {
        adapter.updateSongs(applySortMode(refreshed, currentSort));
      }
      updateHeader(refreshed);
    });
  }

  private void updateHeader(List<Song> songs) {
    int count = songs.size();
    long totalSec = 0;
    for (Song s : songs) {
      if (s.duration == null || s.duration.isEmpty()) continue;
      try {
        String[] parts = s.duration.split(":");
        if (parts.length == 2) {
          totalSec += Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1]);
        }
      } catch (NumberFormatException ignored) {
      }
    }

    String duration = totalSec >= 3600 ? String.format("%dh %dm", totalSec / 3600, (totalSec % 3600) / 60) : String.format("%dm %ds", totalSec / 60, totalSec % 60);

    String text = count + (count == 1 ? " song" : " songs");
    if (totalSec > 0) text += " · " + duration;
    ((TextView) findViewById(R.id.tvDetailCount)).setText(text);
  }

  private List<Song> applySortMode(List<Song> songs, String mode) {
    List<Song> sorted = new ArrayList<>(songs);
    switch (mode) {
      case "TITLE":
        sorted.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        break;
      case "ARTIST":
        sorted.sort((a, b) -> a.artist.compareToIgnoreCase(b.artist));
        break;
      case "DURATION":
        sorted.sort((a, b) -> {
          long da = parseDurationSecs(a.duration);
          long db = parseDurationSecs(b.duration);
          return Long.compare(da, db);
        });
        break;
      default:
        break; // NONE — keep original order
    }
    return sorted;
  }

  private long parseDurationSecs(String duration) {
    if (duration == null || duration.isEmpty()) return 0;
    try {
      String[] parts = duration.split(":");
      if (parts.length == 2) return Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1]);
    } catch (NumberFormatException ignored) {
    }
    return 0;
  }
}