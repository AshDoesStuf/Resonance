package me.ash.resonance.playlist;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.ash.resonance.R;
import me.ash.resonance.db.AppDatabase;
import me.ash.resonance.db.DownloadedSongEntity;
import me.ash.resonance.db.ImportedSongEntity;
import me.ash.resonance.services.PlaylistSuggestionService;
import me.ash.resonance.song.Song;
import me.ash.resonance.yt.YtDownloadManager;
import me.ash.resonance.yt.YtMusicService;
import me.ash.resonance.yt.YtTrack;

public class SuggestSongsBottomSheet extends BottomSheetDialogFragment {

  private static final String ARG_PLAYLIST_NAME = "playlist_name";
  private static final int MAX_SEEDS = 5;

  // ── In-memory suggestion cache ────────────────────────────────────────────
  // Keyed by playlistName. Cleared when songs are added so the next open
  // re-fetches and reflects the new playlist state.
  private static final long CACHE_TTL_MS = 10 * 60 * 1000L; // 10 minutes

  private static final Map<String, CachedSuggestions> suggestionCache = new HashMap<>();
  private List<Song> currentPlaylist;
  private String playlistName;

  // ── Instance fields ───────────────────────────────────────────────────────
  private ProgressBar progressBar;
  private TextView tvEmpty;
  private TextView tvLoadingHint;
  private RecyclerView recyclerView;
  private Button btnAddSelected;
  private SuggestionAdapter adapter;

  public SuggestSongsBottomSheet(List<Song> playlist, String playlistName) {
    this.currentPlaylist = playlist != null ? new ArrayList<>(playlist) : new ArrayList<>();
    this.playlistName = playlistName;
    Bundle args = new Bundle();
    args.putString(ARG_PLAYLIST_NAME, playlistName);
    setArguments(args);
  }

  /**
   * Call after adding songs so the next open re-fetches fresh results.
   */
  private static void invalidateCache(String playlistName) {
    suggestionCache.remove(playlistName);
  }

  // ── Constructor ───────────────────────────────────────────────────────────

  private static <T> List<T> pickSample(List<T> list, int n) {
    if (list.size() <= n) return new ArrayList<>(list);
    List<T> sample = new ArrayList<>(n);
    double step = (double) list.size() / n;
    for (int i = 0; i < n; i++) sample.add(list.get((int) (i * step)));
    return sample;
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.bottom_sheet_suggest, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    progressBar = view.findViewById(R.id.progressSuggest);
    tvEmpty = view.findViewById(R.id.tvSuggestEmpty);
    tvLoadingHint = view.findViewById(R.id.tvLoadingHint);
    recyclerView = view.findViewById(R.id.rvSuggestions);
    btnAddSelected = view.findViewById(R.id.btnAddSelected);

    if (playlistName == null && getArguments() != null) {
      playlistName = getArguments().getString(ARG_PLAYLIST_NAME, "");
    }
    if (currentPlaylist == null) currentPlaylist = new ArrayList<>();

    adapter = new SuggestionAdapter(this::onSelectionChanged);
    recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
    recyclerView.setAdapter(adapter);

    btnAddSelected.setEnabled(false);
    btnAddSelected.setOnClickListener(v -> addSelectedToPlaylist());

    setState(State.LOADING);
    loadSuggestions();
  }

  // ── State machine ─────────────────────────────────────────────────────────

  private void setState(State state) {
    progressBar.setVisibility(state == State.LOADING ? View.VISIBLE : View.GONE);
    tvLoadingHint.setVisibility(state == State.LOADING ? View.VISIBLE : View.GONE);
    tvEmpty.setVisibility(state == State.EMPTY ? View.VISIBLE : View.GONE);
    recyclerView.setVisibility(state == State.RESULTS ? View.VISIBLE : View.GONE);
    btnAddSelected.setVisibility(state == State.RESULTS ? View.VISIBLE : View.GONE);
  }

  private void onSelectionChanged(int selectedCount) {
    btnAddSelected.setEnabled(selectedCount > 0);
    btnAddSelected.setText(selectedCount > 0
            ? "Add " + selectedCount + " song" + (selectedCount == 1 ? "" : "s")
            : "Add selected");
  }

  // ── Selection callback ────────────────────────────────────────────────────

  /**
   * Entry point. Serves from cache if available and fresh; otherwise fetches.
   * The exclusion filter is always re-applied even on a cache hit so that songs
   * added since the last fetch are still hidden.
   */
  private void loadSuggestions() {
    CachedSuggestions cached = suggestionCache.get(playlistName);

    if (cached != null && !cached.isExpired()) {
      // Cache hit — still filter against the current playlist state in background
      Executors.newSingleThreadExecutor().execute(() -> {
        Set<String> excluded = buildExclusionSet();
        List<YtTrack> filtered = filterExisting(cached.tracks, excluded);
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
          if (filtered.isEmpty()) {
            setState(State.EMPTY);
          } else {
            adapter.setTracks(filtered);
            setState(State.RESULTS);
          }
        });
      });
      return;
    }

    // Cache miss — full resolve + suggest pipeline
    if (currentPlaylist.isEmpty()) {
      if (getActivity() != null) getActivity().runOnUiThread(() -> setState(State.EMPTY));
      return;
    }

    resolveAndSuggest();
  }

  // ── Loading ───────────────────────────────────────────────────────────────

  private void resolveAndSuggest() {
    ExecutorService pool = Executors.newFixedThreadPool(6);

    pool.submit(() -> {
      // Step 1: exclusion set (DB reads — must be off main thread)
      Set<String> excluded = buildExclusionSet();

      // Step 2: search YouTube for real video IDs to use as seeds
      List<Song> sample = pickSample(currentPlaylist, MAX_SEEDS);
      List<YtTrack> resolvedSeeds = new ArrayList<>();
      CountDownLatch latch = new CountDownLatch(sample.size());

      for (Song song : sample) {
        String query = ((song.title != null ? song.title : "") + " " +
                (song.artist != null ? song.artist : "")).trim();

        YtMusicService.get().search(query, new YtMusicService.SearchCallback() {
          @Override
          public void onResults(List<YtTrack> tracks) {
            if (!tracks.isEmpty()) {
              synchronized (resolvedSeeds) {
                resolvedSeeds.add(tracks.get(0));
              }
            }
            latch.countDown();
          }

          @Override
          public void onError(Exception e) {
            latch.countDown();
          }
        });
      }

      try {
        latch.await();
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }

      if (resolvedSeeds.isEmpty()) {
        if (getActivity() != null) getActivity().runOnUiThread(() -> setState(State.EMPTY));
        return;
      }

      // Step 3: get suggestions, cache them, then filter and show
      new PlaylistSuggestionService().suggest(resolvedSeeds, 50,
              new YtMusicService.SearchCallback() {
                @Override
                public void onResults(List<YtTrack> tracks) {
                  if (getActivity() == null) return;

                  // Cache the raw (unfiltered) results so re-opens are instant.
                  // Filtering is cheap and always re-run against the live playlist.
                  if (tracks != null && !tracks.isEmpty()) {
                    suggestionCache.put(playlistName, new CachedSuggestions(tracks));
                  }

                  List<YtTrack> filtered = filterExisting(tracks, excluded);

                  getActivity().runOnUiThread(() -> {
                    if (filtered.isEmpty()) {
                      setState(State.EMPTY);
                    } else {
                      adapter.setTracks(filtered);
                      setState(State.RESULTS);
                    }
                  });
                }

                @Override
                public void onError(Exception e) {
                  if (getActivity() == null) return;
                  getActivity().runOnUiThread(() -> {
                    setState(State.EMPTY);
                    Toast.makeText(requireContext(),
                            "Could not load suggestions", Toast.LENGTH_SHORT).show();
                  });
                }
              });
    });

    pool.shutdown();
  }

  // ── Full fetch pipeline ───────────────────────────────────────────────────

  /**
   * Builds the set of YouTube video IDs that already exist in this playlist
   * in any form (direct video ID, downloaded mediaStoreId, or imported localId).
   * Must be called off the main thread (Room queries).
   */
  private Set<String> buildExclusionSet() {
    Set<String> excluded = new HashSet<>();

    List<String> playlistIds = PlaylistManager.get(requireContext())
            .getAllPlaylists()
            .getOrDefault(playlistName, new ArrayList<>());

    Set<String> playlistIdSet = new HashSet<>(playlistIds);

    // 1. Direct video ID matches (previously added suggestions)
    excluded.addAll(playlistIdSet);

    AppDatabase db = AppDatabase.get(requireContext());

    // 2. Downloaded songs stored in playlist by mediaStoreId
    for (DownloadedSongEntity e : db.downloadedSongDao().getAll()) {
      if (playlistIdSet.contains(String.valueOf(e.mediaStoreId)) && e.videoId != null) {
        excluded.add(e.videoId);
      }
    }

    // 3. Imported songs stored in playlist by localId
    for (ImportedSongEntity e : db.importedSongDao().getAll()) {
      if (playlistIdSet.contains(String.valueOf(e.localId)) && e.videoId != null) {
        excluded.add(e.videoId);
      }
    }

    return excluded;
  }

  // ── Exclusion set ─────────────────────────────────────────────────────────

  /**
   * Removes tracks whose videoId is in the exclusion set.
   */
  private List<YtTrack> filterExisting(List<YtTrack> tracks, Set<String> excluded) {
    List<YtTrack> result = new ArrayList<>();
    if (tracks == null) return result;
    for (YtTrack t : tracks) {
      if (t.videoId != null && !excluded.contains(t.videoId)) result.add(t);
    }
    return result;
  }

  private void addSelectedToPlaylist() {
    List<YtTrack> selected = adapter.getSelectedTracks();
    if (selected.isEmpty()) return;

    PlaylistManager pm = PlaylistManager.get(requireContext());
    YtDownloadManager dlm = YtDownloadManager.get(requireContext());

    // Count how many are genuinely new downloads (not already on device)
    int toDownload = 0;
    for (YtTrack track : selected) {
      pm.addToPlaylist(playlistName, track.videoId);
      if (!dlm.isDownloaded(track.videoId)) toDownload++;
    }

    // Invalidate cache — the added songs must not appear on next open
    invalidateCache(playlistName);

    // Notify activity to refresh its list
    if (getActivity() instanceof OnSuggestionsAddedListener) {
      ((OnSuggestionsAddedListener) getActivity()).onSuggestionsAdded(selected);
    }

    // Kick off background downloads for tracks not already on device
    if (toDownload > 0) {
      Toast.makeText(requireContext(),
              "Downloading " + toDownload + " song" + (toDownload == 1 ? "" : "s") + "…",
              Toast.LENGTH_SHORT).show();

      for (YtTrack track : selected) {
        if (dlm.isDownloaded(track.videoId)) continue;

        dlm.download(track, new YtDownloadManager.DownloadCallback() {
          @Override
          public void onProgress(String message) { /* silent background */ }

          @Override
          public void onSuccess(String title) {
            // YtDownloadManager already broadcasts ACTION_LIBRARY_CHANGED,
            // which PlaylistDetailActivity listens to for the Downloads playlist.
            // Nothing extra needed here.
          }

          @Override
          public void onError(String reason) {
            // Post a toast so the user knows if a specific track failed
            if (getActivity() != null) {
              getActivity().runOnUiThread(() ->
                      Toast.makeText(requireContext(),
                              "Download failed: " + track.title,
                              Toast.LENGTH_SHORT).show());
            }
          }
        });
      }
    }

    dismiss();
  }

  // ── Add to playlist + auto-download ──────────────────────────────────────

  private enum State {LOADING, RESULTS, EMPTY}

  // ── Helpers ───────────────────────────────────────────────────────────────

  public interface OnSuggestionsAddedListener {
    void onSuggestionsAdded(List<YtTrack> addedTracks);
  }

  // ── Callback interface ────────────────────────────────────────────────────

  private static class CachedSuggestions {
    final List<YtTrack> tracks;
    final long fetchedAt;

    CachedSuggestions(List<YtTrack> tracks) {
      this.tracks = tracks;
      this.fetchedAt = System.currentTimeMillis();
    }

    boolean isExpired() {
      return System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS;
    }
  }
}