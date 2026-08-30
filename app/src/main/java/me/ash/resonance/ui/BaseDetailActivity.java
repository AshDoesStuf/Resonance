package me.ash.resonance.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.LayoutRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import me.ash.resonance.MiniPlayerManager;
import me.ash.resonance.R;
import me.ash.resonance.ResonanceApp;
import me.ash.resonance.playlist.PlaylistDetailAdapter;
import me.ash.resonance.queue.QueueManager;
import me.ash.resonance.song.Song;

public abstract class BaseDetailActivity extends AppCompatActivity {

  protected MediaController controller;
  protected List<Song> currentSongs;
  protected MiniPlayerManager miniPlayerManager;

  // ── subclasses ─────────────────────────────────────

  @LayoutRes
  protected abstract int getLayoutRes();

  protected abstract void bindHeader(View root);

  protected abstract List<Song> loadSongs();

  protected void onRemoveSong(Song song) {
    // optional override
  }

  // ── lifecycle ──────────────────────────────────────

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(getLayoutRes());

    View root = findViewById(R.id.root);
    overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.slide_up, 0);

    // ── Swipe-down to close ───────────────────────────────────────────────────
    View.OnTouchListener swipeListener = new View.OnTouchListener() {
      private static final int SWIPE_THRESHOLD = 150;
      private float startY;

      @SuppressLint("ClickableViewAccessibility")
      @Override
      public boolean onTouch(View v, MotionEvent event) {
        switch (event.getAction()) {
          case MotionEvent.ACTION_DOWN:
            startY = event.getRawY();
            return true; // must return true to receive subsequent MOVE/UP events
          case MotionEvent.ACTION_UP:
            float dy = event.getRawY() - startY;
            if (dy > SWIPE_THRESHOLD) {
              finish();
              overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, R.anim.slide_down);
              return true;
            }
            return false;
        }
        return false;
      }
    };

    root.setOnTouchListener(swipeListener);
    View header = findViewById(R.id.headerContainer);
    if (header != null) {
      header.setOnTouchListener(swipeListener);
    }

    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
      int navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
      v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), navBar);
      return insets;
    });

    findViewById(R.id.btnBack).setOnClickListener(v -> {
      finish();
      overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, R.anim.slide_down);
    });

    miniPlayerManager = new MiniPlayerManager(this);

    RecyclerView rv = findViewById(R.id.rvDetailSongs);

    bindHeader(root);

    rv.setLayoutManager(new LinearLayoutManager(this));

    new Thread(() -> {
      List<Song> songs = loadSongs();

      runOnUiThread(() -> {
        if (isFinishing() || isDestroyed()) return;
        onSongsLoaded(root, rv, songs);
      });
    }).start();

    connectController();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (miniPlayerManager != null) {
      miniPlayerManager.detach();
    }
    controller = null;
  }

  // ── default binding ────────────────────────────────

  protected void onSongsLoaded(View root, RecyclerView rv, List<Song> songs) {
    this.currentSongs = songs;
    rv.setAdapter(new PlaylistDetailAdapter(songs, song -> playSong(songs, song), this::onRemoveSong));

    View btnShuffle = findViewById(R.id.btnShufflePlaylist);
    if (btnShuffle != null) {
      btnShuffle.setOnClickListener(v -> {
        if (controller == null || currentSongs == null || currentSongs.isEmpty()) return;
        List<MediaItem> items = buildMediaItems(currentSongs);
        List<MediaItem> shuffled = new ArrayList<>(items);
        java.util.Collections.shuffle(shuffled);
        QueueManager.get().setOriginalItems(items);
        controller.setMediaItems(shuffled, 0, 0);
        controller.prepare();
        controller.play();
      });
    }
  }

  // ── playback ───────────────────────────────────────

  protected void playSong(List<Song> songs, Song song) {
    if (controller == null) return;

    List<MediaItem> items = buildMediaItems(songs);
    int idx = songs.indexOf(song);

    QueueManager.get().setQueue(controller, items, idx);
  }

  protected List<MediaItem> buildMediaItems(List<Song> songs) {
    List<MediaItem> items = new ArrayList<>();

    for (Song s : songs) {
      items.add(new MediaItem.Builder().setUri(s.uri).setMediaId(String.valueOf(s.id)).setMediaMetadata(new MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setArtworkUri(s.albumArtUri).build()).build());
    }

    return items;
  }

  // ── controller ─────────────────────────────────────

  protected void connectController() {
    ((ResonanceApp) getApplication()).getSharedController(ctrl -> {
      controller = ctrl;
      if (miniPlayerManager != null) {
        miniPlayerManager.init(controller);
      }
    });
  }
}