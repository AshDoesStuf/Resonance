package me.ash.resonance.queue;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.ash.resonance.R;
import me.ash.resonance.album.AlbumDetailActivity;
import me.ash.resonance.artist.ArtistDetailActivity;
import me.ash.resonance.playlist.PlaylistManager;
import me.ash.resonance.playlist.PlaylistPickerSheet;
import me.ash.resonance.songs.SongActionMenu;
import me.ash.resonance.songs.SongContext;
import me.ash.resonance.ui.ResonanceDialog;

public class QueueActivity extends AppCompatActivity {

  private MediaController controller;
  private QueueAdapter adapter;
  private TextView tvQueueCount;
  private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();

  private final Player.Listener playerListener = new Player.Listener() {
    @Override
    public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
      updateCount();
    }

    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
      updateCount();
    }

    @Override
    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
      updateCount();
    }
  };

  private final SongActionMenu.ActionHandler actionHandler = new SongActionMenu.ActionHandler() {
    @Override
    public void onPlayNext(MediaItem song) {
      if (controller != null) QueueManager.get().playNext(controller, song);
    }

    @Override
    public void onAddToQueue(MediaItem song) {
      if (controller != null) QueueManager.get().addToQueue(controller, song);
    }

    @Override
    public void onRemoveFromQueue(MediaItem song, int index) {
      if (controller != null) {
        QueueManager.get().removeItem(controller, index);
        updateCount();
      }
    }

    @Override
    public void onAddToPlaylist(MediaItem song) {
      PlaylistPickerSheet.newInstance(song.mediaId)
              .show(getSupportFragmentManager(), PlaylistPickerSheet.TAG);
    }

    @Override
    public void onGoToArtist(MediaItem song) {
      String artistName = song.mediaMetadata.artist != null ?
              song.mediaMetadata.artist.toString() : "Unknown";
      startActivity(ArtistDetailActivity.createIntent(QueueActivity.this, null, artistName, null));
    }

    @Override
    public void onGoToAlbum(MediaItem song) {
      String albumTitle = song.mediaMetadata.albumTitle != null ?
              song.mediaMetadata.albumTitle.toString() : "Unknown";
      String artistName = song.mediaMetadata.artist != null ?
              song.mediaMetadata.artist.toString() : "Unknown";
      String artUrl = song.mediaMetadata.artworkUri != null ?
              song.mediaMetadata.artworkUri.toString() : null;

      startActivity(AlbumDetailActivity.createIntent(QueueActivity.this, null, albumTitle, artistName, artUrl));
    }

    @Override
    public void onShare(MediaItem song) {
      String title = song.mediaMetadata.title != null ? song.mediaMetadata.title.toString() : "Unknown";
      String artist = song.mediaMetadata.artist != null ? song.mediaMetadata.artist.toString() : "Unknown";
      String shareText = "Listening to " + title + " by " + artist;

      Intent intent = new Intent(Intent.ACTION_SEND);
      intent.setType("text/plain");
      intent.putExtra(Intent.EXTRA_TEXT, shareText);
      startActivity(Intent.createChooser(intent, "Share via"));
    }

    @Override
    public void onRemoveFromPlaylist(MediaItem song, String playlistId) {}

    @Override
    public void onRemoveDownload(MediaItem song) {}
  };

  public static void start(Context context) {
    Intent intent = new Intent(context, QueueActivity.class);
    context.startActivity(intent);
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    setContentView(R.layout.activity_queue);

    View root = findViewById(R.id.queueRootLayout);
    ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
      Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
      v.setPadding(0, systemBars.top, 0, 0);
      return WindowInsetsCompat.CONSUMED;
    });

    findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    tvQueueCount = findViewById(R.id.tvQueueCount);

    RecyclerView rv = findViewById(R.id.rvQueue);
    rv.setLayoutManager(new LinearLayoutManager(this));

    adapter = new QueueAdapter(new QueueAdapter.QueueInteractionListener() {
      @Override
      public void onItemMoved(int from, int to) {
        if (controller != null) QueueManager.get().moveItem(controller, from, to);
      }

      @Override
      public void onItemRemoved(int index) {
        if (controller != null) {
          QueueManager.get().removeItem(controller, index);
          updateCount();
        }
      }

      @Override
      public void onItemClicked(int index) {
        if (controller != null) {
          controller.seekTo(index, 0);
          controller.play();
        }
      }
    }, actionHandler);
    rv.setAdapter(adapter);

    findViewById(R.id.btnClearQueue).setOnClickListener(v -> {
      if (controller != null) {
        controller.clearMediaItems();
        updateCount();
        finish();
      }
    });

    findViewById(R.id.btnSaveQueueAsPlaylist).setOnClickListener(v -> showSavePlaylistDialog());
    findViewById(R.id.btnAppendQueueToPlaylist).setOnClickListener(v -> showAppendToPlaylistPicker());

    setupTempSessionButton();

    QueueManager.get().getControllerAsync(this, c -> {
      this.controller = c;
      adapter.attachTouchHelper(rv);
      updateCount();
      controller.addListener(playerListener);
    });
  }

  @Override
  protected void onDestroy() {
    if (controller != null) {
      controller.removeListener(playerListener);
    }
    updateExecutor.shutdownNow();
    super.onDestroy();
  }

  private void updateCount() {
    if (controller == null) return;

    final Timeline timeline = controller.getCurrentTimeline();
    if (timeline.isEmpty()) {
      tvQueueCount.setText("0 songs");
      adapter.setItems(new ArrayList<>(), -1);
      return;
    }

    final boolean shuffled = controller.getShuffleModeEnabled();
    final int currentMediaItemIndex = controller.getCurrentMediaItemIndex();

    updateExecutor.execute(() -> {
      List<MediaItem> ordered = new ArrayList<>();
      Timeline.Window window = new Timeline.Window();
      int currentPos = -1;

      int nextIdx = timeline.getFirstWindowIndex(shuffled);
      int i = 0;
      while (nextIdx != C.INDEX_UNSET) {
        ordered.add(timeline.getWindow(nextIdx, window).mediaItem);
        if (nextIdx == currentMediaItemIndex) currentPos = i;
        nextIdx = timeline.getNextWindowIndex(nextIdx, Player.REPEAT_MODE_OFF, shuffled);
        i++;
        
        // Safety break for extremely large timelines to avoid infinite loop
        if (i > 5000) break;
      }

      final int finalCurrentPos = currentPos;
      final int totalSongs = ordered.size();
      
      runOnUiThread(() -> {
        if (isDestroyed()) return;
        tvQueueCount.setText(String.format(java.util.Locale.getDefault(), "%d songs", totalSongs));
        adapter.setShuffleEnabled(shuffled);
        adapter.setItems(ordered, finalCurrentPos);
      });
    });
  }

  private void showSavePlaylistDialog() {
    if (controller == null) return;
    EditText input = new EditText(this);
    input.setHint("Playlist Name");

    new ResonanceDialog.Builder(this)
            .setTitle("Save Queue")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
              String name = input.getText().toString().trim();
              if (name.isEmpty()) return;

              List<String> ids = new ArrayList<>();
              for (int i = 0; i < controller.getMediaItemCount(); i++) {
                ids.add(controller.getMediaItemAt(i).mediaId);
              }
              PlaylistManager.get(this).saveQueueAsPlaylist(name, ids);
              Toast.makeText(this, "Saved as " + name, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
  }

  private void showAppendToPlaylistPicker() {
    if (controller == null) return;
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < controller.getMediaItemCount(); i++) {
      ids.add(controller.getMediaItemAt(i).mediaId);
    }
    if (ids.isEmpty()) {
      Toast.makeText(this, "Queue is empty", Toast.LENGTH_SHORT).show();
      return;
    }

    PlaylistManager pm = PlaylistManager.get(this);
    Map<String, List<String>> all = pm.getAllPlaylists();

    if (all.isEmpty()) {
      Toast.makeText(this, "No playlists found", Toast.LENGTH_SHORT).show();
      return;
    }

    String[] names = all.keySet().toArray(new String[0]);
    new ResonanceDialog.Builder(this)
            .setTitle("Append Queue to...")
            .setItems(names, (d, which) -> {
              String name = names[which];
              pm.appendSongsToPlaylist(name, ids);
              int added = ids.size();
              Toast.makeText(this, "Added " + added + " songs to " + name, Toast.LENGTH_SHORT).show();
            })
            .show();
  }

  private void setupTempSessionButton() {
    View btn = findViewById(R.id.btnTempSession);
    ImageView icon = findViewById(R.id.ivTempSessionIcon);
    TextView label = findViewById(R.id.tvTempSessionLabel);

    updateTempSessionUI(icon, label);

    btn.setOnClickListener(v -> {
      boolean active = QueueManager.get().isTempSessionActive();
      if (active) {
        QueueManager.get().stopTempSession();
        Toast.makeText(this, "Temp session ended", Toast.LENGTH_SHORT).show();
      } else {
        if (controller == null) return;
        List<MediaItem> currentItems = new ArrayList<>();
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
          currentItems.add(controller.getMediaItemAt(i));
        }
        QueueManager.get().startTempSession(controller, currentItems);
        Toast.makeText(this, "Temp session started", Toast.LENGTH_SHORT).show();
      }
      updateTempSessionUI(icon, label);
    });
  }

  private void updateTempSessionUI(ImageView icon, TextView label) {
    boolean active = QueueManager.get().isTempSessionActive();
    if (active) {
      icon.setImageResource(R.drawable.ic_timer);
      icon.setColorFilter(getColor(R.color.accent_red));
      label.setText("End Temp");
      label.setTextColor(getColor(R.color.accent_red));
    } else {
      icon.setImageResource(R.drawable.ic_timer);
      icon.setColorFilter(getColor(R.color.text_primary));
      label.setText("Temp");
      label.setTextColor(getColor(R.color.text_muted));
    }
  }
}
