package me.ash.resonance.fragment;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import me.ash.resonance.MusicLoader;
import me.ash.resonance.R;
import me.ash.resonance.queue.QueueManager;
import me.ash.resonance.song.Song;
import me.ash.resonance.song.SongAdapter;

public class SongsFragment extends Fragment {

  // ── Data ──────────────────────────────────────────────────────────────────
  private final List<Song> allSongs = new ArrayList<>();      // master list, never filtered
  private final List<Song> displayedSongs = new ArrayList<>();
  private SortMode sortMode = SortMode.NAME;
  // ── Views ─────────────────────────────────────────────────────────────────
  private RecyclerView recyclerView;
  private LinearLayout searchBar;
  private EditText etSearch;
  private ImageView btnSearchClear;
  private TextView tvSortMode;
  private List<MediaItem> mediaItems;

  private SongAdapter songAdapter;
  private me.ash.resonance.ui.AlphabetSidebarHelper sidebarHelper;
  // ── Playback ──────────────────────────────────────────────────────────────
  private MediaController controller;
  private int currentlyPlayingIndex = -1;
  // ── Search state ──────────────────────────────────────────────────────────
  private boolean searchOpen = false;
  private String currentQuery = "";

  private android.content.BroadcastReceiver libraryReceiver;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_songs, container, false);
  }

  @SuppressLint("NotifyDataSetChanged")
  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    bindViews(view);
    setupRecycler(view);
    initController();
    libraryReceiver = new android.content.BroadcastReceiver() {
      @Override
      public void onReceive(android.content.Context context, android.content.Intent intent) {
        reloadSongs(); // your existing method that re-runs the background thread + refreshes adapter
      }
    };
    androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(requireContext())
            .registerReceiver(libraryReceiver,
                    new android.content.IntentFilter(
                            me.ash.resonance.MusicLibraryEvent.ACTION_LIBRARY_CHANGED));

    new Thread(() -> {
      List<Song> loaded = MusicLoader.loadSongs(requireContext());

      requireActivity().runOnUiThread(() -> {
        allSongs.clear();

        if (loaded != null) {
          allSongs.addAll(loaded);
        }

        displayedSongs.clear();
        displayedSongs.addAll(allSongs);

        applySortToDisplayed();

        songAdapter.update(displayedSongs);

        mediaItems = buildMediaItems(allSongs);
        QueueManager.get().setOriginalItems(mediaItems);

        sidebarHelper = new me.ash.resonance.ui.AlphabetSidebarHelper(
                view.findViewById(R.id.alphaSidebar), recyclerView, displayedSongs);
        sidebarHelper.setup();
        setupHeader(view);

        tryApplyPlaylist();

        if (controller != null && controller.getCurrentMediaItem() != null) {
          String id = controller.getCurrentMediaItem().mediaId;
          for (int i = 0; i < displayedSongs.size(); i++) {
            if (String.valueOf(displayedSongs.get(i).id).equals(id)) {
              currentlyPlayingIndex = i;
              songAdapter.setPlayingIndex(i);
              break;
            }
          }
        }
      });
    }).start();
  }

  private void reloadSongs() {
    new Thread(() -> {
      MusicLoader.invalidate();
      List<Song> loaded = MusicLoader.loadSongs(requireContext());
      requireActivity().runOnUiThread(() -> {
        allSongs.clear();
        if (loaded != null) allSongs.addAll(loaded);
        applyFilter();
        mediaItems = buildMediaItems(allSongs);
        QueueManager.get().setOriginalItems(mediaItems);
      });
    }).start();
  }

  // ── View binding ──────────────────────────────────────────────────────────
  private void bindViews(View view) {
    tvSortMode = view.findViewById(R.id.tvSortMode);
    searchBar = view.findViewById(R.id.searchBar);
    etSearch = view.findViewById(R.id.etSearch);
    btnSearchClear = view.findViewById(R.id.btnSearchClear);
  }

  // ── Header wiring ─────────────────────────────────────────────────────────
  @SuppressLint("NotifyDataSetChanged")
  private void setupHeader(View view) {

    // Tap sort label → cycle sort mode
    tvSortMode.setOnClickListener(v -> cycleSortMode());

    // Long-press sort label → pick from dialog
    tvSortMode.setOnLongClickListener(v -> {
      showSortPicker();
      return true;
    });

    // ⋮ button → same sort picker
    view.findViewById(R.id.btnSortMore).setOnClickListener(v -> showSortPicker());

    // Search toggle
    view.findViewById(R.id.btnSearch).setOnClickListener(v -> toggleSearch());

    // Clear search
    btnSearchClear.setOnClickListener(v -> {
      etSearch.setText("");
      btnSearchClear.setVisibility(View.GONE);
    });

    // Live filter as user types
    etSearch.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int st, int c, int a) {
      }

      @Override
      public void afterTextChanged(Editable s) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        currentQuery = s.toString().trim();
        btnSearchClear.setVisibility(currentQuery.isEmpty() ? View.GONE : View.VISIBLE);
        applyFilter();
      }
    });

    view.findViewById(R.id.btnPlay).setOnClickListener(v -> {
      if (!isControllerReady() || controller == null) return;
      List<MediaItem> queue = buildMediaItems(displayedSongs);
      QueueManager.get().setQueue(controller, queue, 0);
      currentlyPlayingIndex = 0;
      if (recyclerView.getAdapter() != null)
        recyclerView.getAdapter().notifyDataSetChanged();
    });
    // Shuffle play — randomises displayedSongs and plays from position 0
    view.findViewById(R.id.btnShuffle).setOnClickListener(v -> {
      if (!isControllerReady() || controller == null || displayedSongs.isEmpty()) return;
      List<MediaItem> items = buildMediaItems(displayedSongs);
      int randomIdx = new java.util.Random().nextInt(items.size());

      if (!QueueManager.get().isShuffleOn()) {
        QueueManager.get().toggleShuffle(controller);
      }
      QueueManager.get().setQueue(controller, items, randomIdx);

      currentlyPlayingIndex = -1;
      if (recyclerView.getAdapter() != null)
        recyclerView.getAdapter().notifyDataSetChanged();
    });
  }

  // ── Sort ──────────────────────────────────────────────────────────────────
  private void cycleSortMode() {
    switch (sortMode) {
      case NAME:
        sortMode = SortMode.ARTIST;
        break;
      case ARTIST:
        sortMode = SortMode.DURATION;
        break;
      case DURATION:
        sortMode = SortMode.NAME;
        break;
    }
    updateSortLabel();
    applyFilter(); // re-filter + re-sort
  }

  private void showSortPicker() {
    String[] options = {"Name", "Artist", "Duration"};
    int checked = sortMode.ordinal();
    new me.ash.resonance.ui.ResonanceDialog.Builder(requireContext())
            .setTitle("Sort by")
            .setSingleChoiceItems(options, checked, (dialog, which) -> {
              sortMode = SortMode.values()[which];
              updateSortLabel();
              applyFilter();
              dialog.dismiss();
            })
            .show();
  }

  private void updateSortLabel() {
    switch (sortMode) {
      case NAME:
        tvSortMode.setText("Name");
        break;
      case ARTIST:
        tvSortMode.setText("Artist");
        break;
      case DURATION:
        tvSortMode.setText("Duration");
        break;
    }
  }

  /**
   * Sort the displayedSongs list in-place according to current sortMode.
   */
  private void applySortToDisplayed() {
    switch (sortMode) {
      case NAME:
        displayedSongs.sort((a, b) -> {
          String ta = a.title != null ? a.title : "";
          String tb = b.title != null ? b.title : "";
          return ta.compareToIgnoreCase(tb);
        });
        break;
      case ARTIST:
        displayedSongs.sort((a, b) -> {
          String aa = a.artist != null ? a.artist : "";
          String ab = b.artist != null ? b.artist : "";
          int cmp = aa.compareToIgnoreCase(ab);
          String ta = a.title != null ? a.title : "";
          String tb = b.title != null ? b.title : "";
          return cmp != 0 ? cmp : ta.compareToIgnoreCase(tb);
        });
        break;
      case DURATION:
        displayedSongs.sort((a, b) ->
                parseDurationSecs(a.duration) - parseDurationSecs(b.duration));
        break;
    }
    if (sidebarHelper != null) sidebarHelper.setup();
  }

  private int parseDurationSecs(String duration) {
    if (duration == null) return 0;
    String[] parts = duration.split(":");
    try {
      if (parts.length == 2) return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    } catch (NumberFormatException ignored) {
    }
    return 0;
  }

  // ── Search ────────────────────────────────────────────────────────────────
  private void toggleSearch() {
    searchOpen = !searchOpen;
    if (searchOpen) {
      searchBar.setVisibility(View.VISIBLE);
      searchBar.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
      etSearch.requestFocus();
      InputMethodManager imm = (InputMethodManager)
              requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
      imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
    } else {
      etSearch.setText("");
      searchBar.setVisibility(View.GONE);
      InputMethodManager imm = (InputMethodManager)
              requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
      imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }
  }

  /**
   * Filter allSongs by currentQuery, then sort result, then refresh adapter.
   */
  private void applyFilter() {
    displayedSongs.clear();
    if (currentQuery.isEmpty()) {
      displayedSongs.addAll(allSongs);
    } else {
      String q = currentQuery.toLowerCase();
      for (Song s : allSongs) {
        String title = s.title != null ? s.title.toLowerCase() : "";
        String artist = s.artist != null ? s.artist.toLowerCase() : "";
        if (title.contains(q) || artist.contains(q)) {
          displayedSongs.add(s);
        }
      }
    }
    applySortToDisplayed();
    songAdapter.update(displayedSongs);  // ← was missing, only notifyDataSetChanged was called
    currentlyPlayingIndex = -1;          // reset highlight — index no longer valid after filter
    songAdapter.setPlayingIndex(-1);

    if (controller != null && controller.getCurrentMediaItem() != null) {
      String id = controller.getCurrentMediaItem().mediaId;
      for (int i = 0; i < displayedSongs.size(); i++) {
        if (String.valueOf(displayedSongs.get(i).id).equals(id)) {
          currentlyPlayingIndex = i;
          songAdapter.setPlayingIndex(i);
          break;
        }
      }
    }

    if (sidebarHelper != null) sidebarHelper.setup();
  }

  // ── RecyclerView ──────────────────────────────────────────────────────────
  @SuppressLint("NotifyDataSetChanged")
  private void setupRecycler(View view) {
    recyclerView = view.findViewById(R.id.rvSongs);
    recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

    final float density = requireContext().getResources().getDisplayMetrics().density;
    final int insetPx = (int) (76 * density);
    final int accentColor = ContextCompat.getColor(requireContext(), R.color.accent);
    final int dividerColor = ContextCompat.getColor(requireContext(), R.color.divider);

    final Paint dividerPaint = new Paint();
    dividerPaint.setColor(dividerColor);
    dividerPaint.setStrokeWidth(2f);

    final Paint accentPaint = new Paint();
    accentPaint.setColor(accentColor);
    accentPaint.setStrokeWidth(3f);
    accentPaint.setStrokeCap(Paint.Cap.ROUND);

    recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
      @Override
      public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent,
                             @NonNull RecyclerView.State state) {
        int right = parent.getWidth();
        for (int i = 0; i < parent.getChildCount(); i++) {
          View child = parent.getChildAt(i);
          int pos = parent.getChildAdapterPosition(child);
          if (pos < displayedSongs.size() - 1) {
            float y = child.getBottom();
            c.drawLine(insetPx, y, right, y, dividerPaint);
          }
          if (pos == currentlyPlayingIndex) {
            float top = child.getTop() + child.getHeight() * 0.25f;
            float bottom = child.getBottom() - child.getHeight() * 0.25f;
            c.drawLine(0, top, 0, bottom, accentPaint);
          }
        }
      }

      @Override
      public void getItemOffsets(@NonNull Rect outRect, @NonNull View v,
                                 @NonNull RecyclerView parent, @NonNull RecyclerView.State s) {
        outRect.bottom = 1;
      }
    });

    songAdapter = new SongAdapter(song -> {
      if (!isControllerReady()) return;
      int index = indexInDisplayed(song);
      if (index < 0) return;

      List<MediaItem> currentQueue = buildMediaItems(displayedSongs);
      QueueManager.get().setQueue(controller, currentQueue, index);

      currentlyPlayingIndex = index;
      if (recyclerView.getAdapter() != null)
        recyclerView.getAdapter().notifyDataSetChanged();
    });

    recyclerView.setAdapter(songAdapter);

    sidebarHelper = new me.ash.resonance.ui.AlphabetSidebarHelper(
            view.findViewById(R.id.alphaSidebar), recyclerView, displayedSongs);
    sidebarHelper.setup();

    recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
        super.onScrolled(recyclerView, dx, dy);
        if (sidebarHelper != null) sidebarHelper.updateFromScroll();
      }
    });
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    if (libraryReceiver != null) {
      androidx.localbroadcastmanager.content.LocalBroadcastManager
              .getInstance(requireContext())
              .unregisterReceiver(libraryReceiver);
    }
  }

  private int indexInDisplayed(Song song) {
    for (int i = 0; i < displayedSongs.size(); i++) {
      if (displayedSongs.get(i).id == song.id) return i;
    }
    return -1;
  }

  // ── Controller ────────────────────────────────────────────────────────────

  private void initController() {
    ((me.ash.resonance.ResonanceApp) requireActivity().getApplication())
            .getSharedController(ctrl -> {
              if (ctrl == null) return;
              controller = ctrl;
              songAdapter.setController(ctrl);
              controller.addListener(new Player.Listener() {
                @Override
                public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
                  if (item == null || recyclerView == null) return;
                  String id = item.mediaId;
                  for (int i = 0; i < displayedSongs.size(); i++) {
                    if (String.valueOf(displayedSongs.get(i).id).equals(id)) {
                      currentlyPlayingIndex = i;
                      break;
                    }
                  }
                  songAdapter.setPlayingIndex(currentlyPlayingIndex);
                  if (recyclerView.getAdapter() != null)
                    recyclerView.getAdapter().notifyDataSetChanged();
                }
              });
              tryApplyPlaylist();
            });
  }

  private void tryApplyPlaylist() {
    if (!isControllerReady() || controller == null || mediaItems == null) return;

    // Check if we have a pending restore (app restart scenario)
    QueueManager.SavedQueueState pendingRestore = QueueManager.get().getPendingRestore();
    if (pendingRestore != null) {
      // Don't override — let the playback system restore it
      return;
    }

    // Only set the queue if nothing is currently loaded/playing.
    // This prevents the fragment reload from wiping out the current song.
    if (controller.getMediaItemCount() == 0) {
      controller.setMediaItems(mediaItems);
      controller.prepare();
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  private List<MediaItem> buildMediaItems(List<Song> songs) {
    List<MediaItem> items = new ArrayList<>();
    for (Song s : songs) {
//      Log.d("SONG", "URI = " + s.uri + " | scheme=" + s.uri.getScheme());
      MediaMetadata metadata = new MediaMetadata.Builder()
              .setTitle(s.title)
              .setArtist(s.artist)
              .setArtworkUri(s.albumArtUri)
              .build();
      items.add(new MediaItem.Builder()
              .setUri(s.uri)
              .setMediaId(String.valueOf(s.id))
              .setMediaMetadata(metadata)
              .build());
    }
    return items;
  }

  private boolean isControllerReady() {
    return controller != null;
  }

  // ── Sort ──────────────────────────────────────────────────────────────────
  private enum SortMode {NAME, ARTIST, DURATION}
}