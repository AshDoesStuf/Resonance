package me.ash.resonance.search;

import static me.ash.resonance.util.Utils.buildYtItem;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import me.ash.resonance.MusicLoader;
import me.ash.resonance.NowPlayingActivity;
import me.ash.resonance.R;
import me.ash.resonance.playlist.PlaylistManager;
import me.ash.resonance.playlist.PlaylistPickerSheet;
import me.ash.resonance.queue.QueueManager;
import me.ash.resonance.radio.RadioEngine;
import me.ash.resonance.radio.RadioSession;
import me.ash.resonance.song.Song;
import me.ash.resonance.ui.SelectionManager;
import me.ash.resonance.yt.YtMusicService;
import me.ash.resonance.yt.YtTrack;

/**
 * Full-screen search Activity.
 * <p>
 * ── Behaviour ─────────────────────────────────────────────────────────────────
 * • Typing filters local songs instantly (no network).
 * • Pressing the search key / IME "Search" action fires a YouTube Music search.
 * • Results are shown in a combined list: local matches first, then YT tracks.
 * • Each YT track row has a ⋮ menu: Play, Play Next, Add to Queue, Add to Playlist, Download.
 * • Tapping a YT row resolves the stream URL and starts playback immediately.
 * <p>
 * ── Launch ────────────────────────────────────────────────────────────────────
 * startActivity(new Intent(this, SearchActivity.class));
 * <p>
 * ── Wiring in MainActivity ────────────────────────────────────────────────────
 * findViewById(R.id.btnSearch).setOnClickListener(v ->
 * startActivity(new Intent(this, SearchActivity.class)));
 */
public class SearchActivity extends AppCompatActivity {

  private static final int DEBOUNCE_MS = 300; // local filter debounce
  private final Handler handler = new Handler(Looper.getMainLooper());
  private SelectionManager<String> selectionManager;
  // ── Views ─────────────────────────────────────────────────────────────────
  private EditText etSearch;
  private ImageButton btnBack, btnClear;
  private View selectionBar;
  private TextView tvSelectionCount;
  private ImageButton btnDownloadSelected;
  private ImageButton btnCancelSelected;
  private ProgressBar progressLocal, progressYt;
  private TextView tvYtStatus;
  private RecyclerView rv;
  // ── State ─────────────────────────────────────────────────────────────────
  private List<Song> allSongs = new ArrayList<>();
  private SearchResultAdapter adapter;
  private MediaController controller;
  private Runnable localDebounce;

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_search);

    bindViews();
    setupRecycler();
    connectController();
    loadLocalSongs();
    wireSearchBar();

    OnBackPressedCallback callback = new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {

        // 1. If selection mode is active → clear it first
        if (selectionManager != null && selectionManager.isActive()) {
          selectionManager.clear();
          adapter.notifyDataSetChanged();
          return;
        }

        // 2. If keyboard is open → close it instead of exiting
        View focus = getCurrentFocus();
        if (focus != null) {
          InputMethodManager imm =
                  (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
          imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
          return;
        }

        // 3. Otherwise exit activity
        finish();
      }
    };

    getOnBackPressedDispatcher().addCallback(this, callback);

    // Auto-open keyboard
    etSearch.requestFocus();
    showKeyboard(etSearch);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    controller = null; // Don't release — not ours
  }

  // ── View binding ──────────────────────────────────────────────────────────

  private void bindViews() {
    etSearch = findViewById(R.id.etSearchQuery);
    btnBack = findViewById(R.id.btnSearchBack);
    btnClear = findViewById(R.id.btnSearchClear);
    progressLocal = findViewById(R.id.progressLocal);
    progressYt = findViewById(R.id.progressYt);
    tvYtStatus = findViewById(R.id.tvYtStatus);
    rv = findViewById(R.id.rvSearchResults);
    selectionBar = findViewById(R.id.selectionBar);
    tvSelectionCount = findViewById(R.id.tvSelectionCount);

    btnDownloadSelected = findViewById(R.id.btnDownloadSelected);
    btnCancelSelected = findViewById(R.id.btnCancelSelection);

    btnCancelSelected.setOnClickListener(v -> {
      selectionManager.clear();
      adapter.notifyDataSetChanged();
    });
    btnDownloadSelected.setOnClickListener(v -> downloadSelected());

    btnBack.setOnClickListener(v -> finish());
    btnClear.setOnClickListener(v -> etSearch.setText(""));
  }

  // ── RecyclerView ──────────────────────────────────────────────────────────

  private void setupRecycler() {
    adapter = new SearchResultAdapter(new SearchResultAdapter.InteractionListener() {

      @Override
      public void onLocalSongClick(Song song) {
        playLocalSong(song);
      }

      @Override
      public void onLocalSongMore(Song song, View anchor) {
        showLocalSongMenu(song, anchor);
      }

      @Override
      public void onYtPlay(YtTrack track) {
        startRadio(track);
      }

      @Override
      public void onYtMore(YtTrack track, View anchor) {
        showYtTrackMenu(track, anchor);
      }
    });
    selectionManager = new SelectionManager<>(count -> {
      if (count == 0) {
        selectionBar.setVisibility(View.GONE);
      } else {
        selectionBar.setVisibility(View.VISIBLE);
        tvSelectionCount.setText(count + " selected");
      }
    });
    adapter.setSelectionManager(selectionManager);
    rv.setLayoutManager(new LinearLayoutManager(this));
    rv.setAdapter(adapter);
  }

  // ── Local songs ───────────────────────────────────────────────────────────

  private void loadLocalSongs() {
    // MusicLoader is fast (MediaStore cursor), run on a background thread anyway
    new Thread(() -> {
      List<Song> songs = MusicLoader.loadSongs(this);
      runOnUiThread(() -> {
        allSongs = songs;
        // Show all local songs before user types anything
        adapter.setLocalResults(allSongs);
      });
    }).start();
  }

  private void filterLocal(String query) {
    if (query.isEmpty()) {
      adapter.setLocalResults(allSongs);
      return;
    }
    String q = query.toLowerCase(Locale.ROOT);
    List<Song> filtered = new ArrayList<>();
    for (Song s : allSongs) {
      if (s.title.toLowerCase(Locale.ROOT).contains(q)
              || s.artist.toLowerCase(Locale.ROOT).contains(q)) {
        filtered.add(s);
      }
    }
    adapter.setLocalResults(filtered);
  }

  // ── YT Music search ───────────────────────────────────────────────────────

  private void searchYt(String query) {
    if (query.isEmpty()) return;

    progressYt.setVisibility(View.VISIBLE);
    tvYtStatus.setVisibility(View.GONE);

    YtMusicService.get().search(query, new YtMusicService.SearchCallback() {
      @Override
      public void onResults(List<YtTrack> tracks) {
        runOnUiThread(() -> {
          progressYt.setVisibility(View.GONE);
          if (tracks.isEmpty()) {
            tvYtStatus.setVisibility(View.VISIBLE);
            tvYtStatus.setText("No YouTube Music results");
          }
          adapter.setYtResults(tracks);
          adapter.updateDownloadStatuses(SearchActivity.this, tracks);
        });
      }

      @Override
      public void onError(Exception e) {
        runOnUiThread(() -> {
          progressYt.setVisibility(View.GONE);
          tvYtStatus.setVisibility(View.VISIBLE);
          tvYtStatus.setText("YouTube Music unavailable");
        });
      }
    });
  }

  // ── Search bar wiring ─────────────────────────────────────────────────────

  private void wireSearchBar() {
    etSearch.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int st, int c, int a) {
      }

      @Override
      public void afterTextChanged(Editable s) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        String q = s.toString().trim();
        btnClear.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);

        // Debounced local filter
        if (localDebounce != null) handler.removeCallbacks(localDebounce);
        localDebounce = () -> filterLocal(q);
        handler.postDelayed(localDebounce, DEBOUNCE_MS);
      }
    });

    // IME "Search" key → fire YT search
    etSearch.setOnEditorActionListener((v, actionId, event) -> {
      if (actionId == EditorInfo.IME_ACTION_SEARCH
              || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
        String q = etSearch.getText().toString().trim();
        if (!q.isEmpty()) {
          hideKeyboard();
          searchYt(q);
        }
        return true;
      }
      return false;
    });
  }

  // ── Playback helpers ──────────────────────────────────────────────────────

  private void playLocalSong(Song song) {
    if (controller == null) {
      toast("Player not ready");
      return;
    }

    // Build a queue from all local songs, start at tapped song
    List<MediaItem> queue = new ArrayList<>();
    int startIndex = 0;
    for (int i = 0; i < allSongs.size(); i++) {
      Song s = allSongs.get(i);
      queue.add(buildLocalItem(s));
      if (s.id == song.id) startIndex = i;
    }
    QueueManager.get().setOriginalItems(queue);
    controller.setMediaItems(queue, startIndex, 0);
    controller.prepare();
    controller.play();
    startActivity(new Intent(this, NowPlayingActivity.class));
  }

  private void streamYtTrack(YtTrack track, boolean playNext) {
    if (controller == null) {
      toast("Player not ready");
      return;
    }
    MediaItem item = buildYtItem(track);
    if (playNext) {
      QueueManager.get().playNext(controller, item);
      toast("Playing next: " + track.title);
    } else {
      List<MediaItem> queue = List.of(item);
      QueueManager.get().setOriginalItems(queue);
      controller.setMediaItems(queue, 0, 0);
      startActivity(new Intent(SearchActivity.this, NowPlayingActivity.class));
    }
  }

  private void addYtToQueue(YtTrack track) {
    if (controller == null) {
      toast("Player not ready");
      return;
    }
    MediaItem item = buildYtItem(track);
    QueueManager.get().addToQueue(controller, item);
    // keep originalItems in sync
    QueueManager.get().setOriginalItems(getControllerQueue());
    toast("Added to queue: " + track.title);
  }

  private List<MediaItem> getControllerQueue() {
    List<MediaItem> items = new ArrayList<>();
    for (int i = 0; i < controller.getMediaItemCount(); i++) {
      items.add(controller.getMediaItemAt(i));
    }
    return items;
  }

  // ── Context menus ─────────────────────────────────────────────────────────

  private void showLocalSongMenu(Song song, View anchor) {
    PopupMenu menu = new PopupMenu(this, anchor);
    menu.getMenu().add(0, 0, 0, "Play Next");
    menu.getMenu().add(0, 1, 0, "Add to Queue");
    menu.getMenu().add(0, 2, 0, "Add to Playlist");
    menu.getMenu().add(0, 3, 0, "Favourite");

    menu.setOnMenuItemClickListener(item -> {
      switch (item.getItemId()) {
        case 0:
          if (controller != null) {
            QueueManager.get().playNext(controller, buildLocalItem(song));
            toast("Playing next");
          }
          return true;
        case 1:
          if (controller != null) {
            QueueManager.get().addToQueue(controller, buildLocalItem(song));
            toast("Added to queue");
          }
          return true;
        case 2:
          PlaylistPickerSheet
                  .newInstance(String.valueOf(song.id))
                  .show(getSupportFragmentManager(), PlaylistPickerSheet.TAG);
          return true;
        case 3:
          boolean fav = PlaylistManager.get(this).toggleFavourite(String.valueOf(song.id));
          toast(fav ? "Added to Liked Songs" : "Removed from Liked Songs");
          return true;
      }
      return false;
    });
    menu.show();
  }

  private void showYtTrackMenu(YtTrack track, View anchor) {
    PopupMenu menu = new PopupMenu(this, anchor);
    menu.getMenu().add(0, 0, 0, "Play");
    menu.getMenu().add(0, 1, 0, "Play Next");
    menu.getMenu().add(0, 2, 0, "Add to Queue");
    menu.getMenu().add(0, 3, 0, "Add to Playlist");
    menu.getMenu().add(0, 4, 0, "Download");

    menu.setOnMenuItemClickListener(item -> {
      switch (item.getItemId()) {
        case 0:
        case 1:
          startRadio(track);
          return true;
        case 2:
          addYtToQueue(track);
          return true;
        case 3:
          showYtAddToPlaylist(track);
          return true;
        case 4:
          downloadYtTrack(track);
          return true;
      }
      return false;
    });
    menu.show();
  }

  private void startRadio(YtTrack seed) {
    if (controller == null) {
      // Controller not yet connected — wait for it then retry
      ((me.ash.resonance.ResonanceApp) getApplication())
              .getSharedController(ctrl -> {
                controller = ctrl;
                startRadio(seed); // retry once ready
              });
      return;
    }

    RadioSession session = new RadioSession(seed);
    QueueManager.get().setActiveRadio(session);

    RadioEngine engine = new RadioEngine(session);

    engine.fetchNext(tracks -> runOnUiThread(() -> {

      List<MediaItem> queue = new ArrayList<>();
      queue.add(buildYtItem(seed));
      session.markSeen(seed.videoId);

      for (YtTrack t : tracks) {
        queue.add(buildYtItem(t));
        session.markSeen(t.videoId);
      }

      QueueManager.get().setOriginalItems(queue);
      controller.setMediaItems(queue, 0, 0);
      controller.prepare();
      controller.play();

      startActivity(new Intent(this, NowPlayingActivity.class));

    }));  // ← close runOnUiThread and fetchNext
  }

  private void showYtAddToPlaylist(YtTrack track) {
    // We use the video ID as the mediaId so PlaylistManager can store it
    PlaylistPickerSheet
            .newInstance(track.videoId)
            .show(getSupportFragmentManager(), PlaylistPickerSheet.TAG);
  }

  // ── Download ──────────────────────────────────────────────────────────────

  /**
   * Enqueues the track for download via Android's DownloadManager.
   * The file is saved to Music/Resonance/ with the same [videoId] naming
   * convention that OuterTune uses, so OuterTuneImporter can re-import it later.
   */
  private void downloadYtTrack(YtTrack track) {
    track.isDownloading = true;
    adapter.notifyYtTrackChanged(track); // we'll add this below

    me.ash.resonance.yt.YtDownloadManager.get(this).download(track,
            new me.ash.resonance.yt.YtDownloadManager.DownloadCallback() {
              @Override
              public void onProgress(String message) {
                // spinner already visible, no need to toast every progress
              }

              @Override
              public void onSuccess(String title) {
                runOnUiThread(() -> {
                  track.isDownloading = false;
                  adapter.notifyYtTrackChanged(track);
                  toast("Downloaded: " + title);
                });
              }

              @Override
              public void onError(String reason) {
                runOnUiThread(() -> {
                  track.isDownloading = false;
                  adapter.notifyYtTrackChanged(track);
                  toast("Download failed: " + reason);
                });
              }
            });
  }

  private void downloadSelected() {
    Set<String> ids = new java.util.HashSet<>(selectionManager.getSelected());
    selectionManager.clear();
    adapter.notifyDataSetChanged();

    // Collect YtTrack objects matching selected IDs
    List<YtTrack> toDownload = new ArrayList<>();
    for (String id : ids) {
      YtTrack t = findYtTrackById(id);
      if (t != null) toDownload.add(t);
    }

    toast("Downloading " + toDownload.size() + " tracks…");
    for (YtTrack track : toDownload) {
      downloadYtTrack(track);
    }
  }

  /**
   * Walk the adapter's current YT items to find a track by videoId.
   */
  private YtTrack findYtTrackById(String videoId) {
    // Expose a lookup method on the adapter (see adapter change below)
    return adapter.findYtTrack(videoId);
  }
  // ── MediaController ───────────────────────────────────────────────────────

  private void connectController() {
    ((me.ash.resonance.ResonanceApp) getApplication())
            .getSharedController(ctrl -> controller = ctrl);
  }

  // ── MediaItem builders ────────────────────────────────────────────────────

  private MediaItem buildLocalItem(Song s) {
    return new MediaItem.Builder()
            .setUri(s.uri)
            .setMediaId(String.valueOf(s.id))
            .setMediaMetadata(new MediaMetadata.Builder()
                    .setTitle(s.title)
                    .setArtist(s.artist)
                    .setArtworkUri(s.albumArtUri)
                    .build())
            .build();
  }

  // ── Utilities ─────────────────────────────────────────────────────────────

  private void toast(String msg) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
  }

  private void showKeyboard(View v) {
    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
  }

  private void hideKeyboard() {
    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    View focus = getCurrentFocus();
    if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
  }
}