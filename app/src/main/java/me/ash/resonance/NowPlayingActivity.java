package me.ash.resonance;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.google.common.util.concurrent.ListenableFuture;

import me.ash.resonance.album.Album;
import me.ash.resonance.album.AlbumDetailActivity;
import me.ash.resonance.artist.Artist;
import me.ash.resonance.artist.ArtistDetailActivity;
import me.ash.resonance.playback.PlaybackSessionManager;
import me.ash.resonance.playlist.PlaylistManager;
import me.ash.resonance.playlist.PlaylistPickerSheet;
import me.ash.resonance.queue.QueueBottomSheet;
import me.ash.resonance.queue.QueueManager;
import me.ash.resonance.services.MusicService;

public class NowPlayingActivity extends AppCompatActivity {

  private static final float ART_SCALE_PLAYING = 1f;
  private static final float ART_SCALE_PAUSED = 0.82f;
  private final Handler seekHandler = new Handler(Looper.getMainLooper());
  private MediaController controller;
  private androidx.cardview.widget.CardView cardAlbumArt;
  private TextView tvTitle, tvArtist, tvCurrentTime, tvTotalTime;

  private TextView tvBluetoothDevice;
  private ImageView ivBluetoothIcon;
  private SeekBar seekBar;
  private final Runnable seekUpdater = new Runnable() {
    @Override
    public void run() {
      if (controller != null) {
        int pos = (int) controller.getCurrentPosition();
        seekBar.setProgress(pos);
        tvCurrentTime.setText(formatTime(pos));
      }
      seekHandler.postDelayed(this, 500);
    }
  };
  private android.media.AudioDeviceCallback audioDeviceCallback;
  private Player.Listener playerListener;
  private ImageButton btnPlayPause, btnNext, btnPrevious;
  private ImageButton btnShuffle, btnRepeat, btnQueue;
  private ImageButton btnFavourite, btnAddToPlaylist;
  private ImageView ivAlbumArt;
  // Cached metadata for navigation taps
  private String currentArtistName = null;
  private long currentAlbumId = -1;

  private float swipeStartY;
  private String currentArtistForAlbum = null; // artist name carried alongside albumId

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_now_playing);
    overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.slide_up, 0);

    // Start translated off-screen if launched in peek mode
    View rootView = findViewById(R.id.root); // your root layout id
    boolean peekMode = getIntent().getBooleanExtra("peek_mode", false);

    if (peekMode) {
      int screenHeight = getResources().getDisplayMetrics().heightPixels;
      rootView.setTranslationY(screenHeight); // start off-screen

      MiniPlayerManager.setPeekCallback(new MiniPlayerManager.PeekCallback() {
        @Override
        public void onDrag(float translationY) {
          rootView.setTranslationY(translationY);
        }

        @Override
        public void onRelease(boolean open) {
          MiniPlayerManager.setPeekCallback(null);
          if (open) {
            // Animate fully into view
            rootView.animate().translationY(0).setDuration(250).start();
          } else {
            // Animate back down and close
            rootView.animate()
                    .translationY(getResources().getDisplayMetrics().heightPixels)
                    .setDuration(200)
                    .withEndAction(() -> finish())
                    .start();
            overridePendingTransition(0, 0);
          }
        }
      });
    }

    // ── Swipe-down to close ───────────────────────────────────────────────────
    findViewById(R.id.root).setOnTouchListener(new View.OnTouchListener() {
      private static final int SWIPE_THRESHOLD = 150;
      private float startY;

      @Override
      public boolean onTouch(View v, MotionEvent event) {
        switch (event.getAction()) {
          case MotionEvent.ACTION_DOWN:
            startY = event.getRawY();
            return false; // don't consume, let children handle clicks
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
    });

    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
      int navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
      v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), navBar);
      return insets;
    });

    tvTitle = findViewById(R.id.tvNowTitle);
    tvArtist = findViewById(R.id.tvNowArtist);
    tvCurrentTime = findViewById(R.id.tvCurrentTime);
    tvTotalTime = findViewById(R.id.tvTotalTime);
    seekBar = findViewById(R.id.seekBar);
    btnPlayPause = findViewById(R.id.btnPlayPause);
    btnNext = findViewById(R.id.btnNext);
    btnPrevious = findViewById(R.id.btnPrevious);
    btnShuffle = findViewById(R.id.btnShuffle);
    btnRepeat = findViewById(R.id.btnRepeat);
    btnQueue = findViewById(R.id.btnQueue);
    btnFavourite = findViewById(R.id.btnFavourite);
    btnAddToPlaylist = findViewById(R.id.btnAddToPlaylist);
    ivAlbumArt = findViewById(R.id.ivNowAlbumArt);
    cardAlbumArt = findViewById(R.id.cardNowAlbumArt);
    tvBluetoothDevice = findViewById(R.id.tvBluetoothDevice);
    ivBluetoothIcon = findViewById(R.id.ivBluetoothIcon);

    findViewById(R.id.btnBack).setOnClickListener(v -> {
      finish();
      overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, R.anim.slide_down);
    });

    // ── Tappable artist → ArtistDetailSheet ───────────────────────────────
    tvArtist.setOnClickListener(v -> {
      if (currentArtistName == null || currentArtistName.isEmpty()) return;
      startActivity(ArtistDetailActivity.createIntent(this, new Artist(currentArtistName, 0)));
    });

    // ── Tappable album art → AlbumDetailSheet ─────────────────────────────
    ivAlbumArt.setOnClickListener(v -> {
      if (currentAlbumId < 0) return;
      android.net.Uri artUri = android.net.Uri.parse(
              "content://media/external/audio/albumart/" + currentAlbumId);
      Album album = new Album(currentAlbumId, "", currentArtistForAlbum, 0, artUri);

      startActivity(AlbumDetailActivity.createIntent(this, album));
    });

    initController();
    setupUI();
    registerAudioDeviceCallback();
  }

  private void registerAudioDeviceCallback() {
    android.media.AudioManager am =
            (android.media.AudioManager) getSystemService(AUDIO_SERVICE);

    audioDeviceCallback = new android.media.AudioDeviceCallback() {
      @Override
      public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] addedDevices) {
        updateAudioOutputUI();
      }

      @Override
      public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] removedDevices) {
        updateAudioOutputUI();
      }
    };

    am.registerAudioDeviceCallback(audioDeviceCallback, null);
  }

  private void animateArtScale(boolean playing) {
    float target = playing ? ART_SCALE_PLAYING : ART_SCALE_PAUSED;
    cardAlbumArt.animate()
            .scaleX(target)
            .scaleY(target)
            .setDuration(350)
            .setInterpolator(new android.view.animation.DecelerateInterpolator())
            .start();
  }

  @Override
  protected void onResume() {
    super.onResume();
    seekHandler.post(seekUpdater);
  }

  @Override
  protected void onPause() {
    super.onPause();
    seekHandler.removeCallbacks(seekUpdater);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    MiniPlayerManager.setPeekCallback(null);
    if (controller != null && playerListener != null) {
      controller.removeListener(playerListener); // ← clean detach before nulling
    }
    playerListener = null;
    controller = null;
    if (audioDeviceCallback != null) {
      android.media.AudioManager am =
              (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
      am.unregisterAudioDeviceCallback(audioDeviceCallback);
      audioDeviceCallback = null;
    }
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN:
        swipeStartY = event.getRawY();
        break;
      case MotionEvent.ACTION_UP:
        float dy = event.getRawY() - swipeStartY;
        float dx = Math.abs(event.getRawX() - event.getRawX()); // horizontal guard
        if (dy > 200) {
          finish();
          overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, R.anim.slide_down);
          return true; // consume so nothing else fires
        }
        break;
    }
    return super.dispatchTouchEvent(event); // pass through normally otherwise
  }

  // ── Controller ────────────────────────────────────────────────────────────
  private void initController() {
    MediaController c = ((ResonanceApp) getApplication()).getSharedController();
    if (c != null) {
      controller = c;
      onControllerReady();
    } else {
      // Fallback: build our own if app controller isn't ready
      // (shouldn't happen in normal flow)
      SessionToken token = new SessionToken(
              this, new ComponentName(this, MusicService.class));
      ListenableFuture<MediaController> future =
              new MediaController.Builder(this, token).buildAsync();
      future.addListener(() -> {
        try {
          controller = future.get();
          onControllerReady();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }, ContextCompat.getMainExecutor(this));
    }
  }

  private void onControllerReady() {
    attachListeners();
    MediaItem current = controller.getCurrentMediaItem();
    if (current != null) {
      updateSongUI(current);
    } else {
      QueueManager.SavedQueueState pending = QueueManager.get().getPendingRestore();
      if (pending != null && !pending.items.isEmpty()) {
        updateSongUI(pending.items.get(pending.index));
      }
    }
    long dur = controller.getDuration();
    if (dur > 0) {
      seekBar.setMax((int) dur);
      tvTotalTime.setText(formatTime((int) dur));
    }
    btnPlayPause.setImageResource(
            controller.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
    syncShuffleButton();
    syncRepeatButton();
    syncFavouriteButton();

    float initial = controller.isPlaying() ? ART_SCALE_PLAYING : ART_SCALE_PAUSED;
    cardAlbumArt.setScaleX(initial);
    cardAlbumArt.setScaleY(initial);
  }

  // ── Player listeners ──────────────────────────────────────────────────────
  private void attachListeners() {
    playerListener = new Player.Listener() {

      @Override
      public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
        if (isDestroyed()) return;
        if (item != null) updateSongUI(item);
        syncFavouriteButton();
      }

      @Override
      public void onIsPlayingChanged(boolean isPlaying) {
        if (isDestroyed()) return;
        btnPlayPause.setImageResource(
                isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        animateArtScale(isPlaying);
      }

      @Override
      public void onPlaybackStateChanged(int state) {
        if (isDestroyed()) return;
        long dur = controller.getDuration();
        if (dur > 0) {
          seekBar.setMax((int) dur);
          tvTotalTime.setText(formatTime((int) dur));
        }
      }

      @Override
      public void onShuffleModeEnabledChanged(boolean shuffleEnabled) {
        if (isDestroyed()) return;
        PlaybackSessionManager session =
                ((ResonanceApp) getApplication()).getPlaybackSessionManager();
        session.setShuffle(shuffleEnabled);
        session.persist(NowPlayingActivity.this);
      }

      @Override
      public void onRepeatModeChanged(int repeatMode) {
        if (isDestroyed()) return;
        PlaybackSessionManager session =
                ((ResonanceApp) getApplication()).getPlaybackSessionManager();
        session.setRepeatMode(repeatMode);
        session.persist(NowPlayingActivity.this);
        syncRepeatButton();
      }
    };
    controller.addListener(playerListener);
  }

  // ── Song UI ───────────────────────────────────────────────────────────────
  private void updateSongUI(MediaItem item) {
    CharSequence title = item.mediaMetadata.title;
    CharSequence artist = item.mediaMetadata.artist;

    tvTitle.setText(title != null ? title : "Unknown");
    tvArtist.setText(artist != null ? artist : "Unknown");

    // Cache for navigation taps
    currentArtistName = artist != null ? artist.toString() : null;

    // Derive albumId from artworkUri: "content://media/external/audio/albumart/<id>"
    if (item.mediaMetadata.artworkUri != null) {
      String path = item.mediaMetadata.artworkUri.toString();
      String idStr = path.substring(path.lastIndexOf('/') + 1);
      try {
        currentAlbumId = Long.parseLong(idStr);
        currentArtistForAlbum = currentArtistName;
      } catch (NumberFormatException e) {
        currentAlbumId = -1;
      }
    } else {
      currentAlbumId = -1;
    }

    // Subtle underline hint on artist to signal it's tappable
    tvArtist.setPaintFlags(
            tvArtist.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);

    Glide.with(this)
            .load(item.mediaMetadata.artworkUri)
            .placeholder(R.drawable.ic_note_outlined)
            .error(R.drawable.ic_note_outlined)
            .transition(DrawableTransitionOptions.withCrossFade(400))
            .into(ivAlbumArt);
  }

  // ── Button wiring ─────────────────────────────────────────────────────────
  private void setupUI() {
    PlaybackSessionManager session =
            ((ResonanceApp) getApplication())
                    .getPlaybackSessionManager();

    btnPlayPause.setOnClickListener(v -> session.playPause());
    btnNext.setOnClickListener(v -> session.next());
    btnPrevious.setOnClickListener(v -> session.previous());

    btnShuffle.setOnClickListener(v -> {
      if (controller == null) return;
      QueueManager.get().toggleShuffle(controller);
      syncShuffleButton();
    });

    btnRepeat.setOnClickListener(v -> {
      if (controller == null) return;
      QueueManager.get().cycleRepeat(controller);
      syncRepeatButton();
    });

    btnQueue.setOnClickListener(v ->
            QueueBottomSheet.newInstance()
                    .show(getSupportFragmentManager(), QueueBottomSheet.TAG));

    seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
        if (fromUser && controller != null) {
          controller.seekTo(progress);
          tvCurrentTime.setText(formatTime(progress));
        }
      }

      @Override
      public void onStartTrackingTouch(SeekBar sb) {
      }

      @Override
      public void onStopTrackingTouch(SeekBar sb) {
      }
    });

    btnFavourite.setOnClickListener(v -> toggleFavourite());

    btnAddToPlaylist.setOnClickListener(v ->
            PlaylistPickerSheet.newInstance(getCurrentSongId())
                    .show(getSupportFragmentManager(), PlaylistPickerSheet.TAG));

    updateAudioOutputUI();
  }

  /**
   * Updates the Bluetooth device display text and icon visibility
   */
  private void updateBluetoothDeviceUI(String deviceName) {
    if (tvBluetoothDevice != null && ivBluetoothIcon != null) {
      if (deviceName != null && !deviceName.isEmpty()) {
        tvBluetoothDevice.setText(deviceName);
        tvBluetoothDevice.setAlpha(0.9f);
        ivBluetoothIcon.setVisibility(View.VISIBLE);
        ivBluetoothIcon.setAlpha(0.9f);
      } else {
        tvBluetoothDevice.setText("No Audio Device");
        tvBluetoothDevice.setAlpha(0.7f);
        ivBluetoothIcon.setVisibility(View.GONE);
      }
    }
  }

  private void updateAudioOutputUI() {
    android.media.AudioManager am =
            (android.media.AudioManager) getSystemService(AUDIO_SERVICE);

    String deviceName = null;

    for (android.media.AudioDeviceInfo device : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
      int type = device.getType();
      if (type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
              || type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
              || type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
              || type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER) {
        deviceName = device.getProductName().toString();
        break;
      }
    }

    updateBluetoothDeviceUI(deviceName); // null = not bluetooth → shows "No Audio Device"
  }

  private String getCurrentSongId() {
    if (controller == null || controller.getCurrentMediaItem() == null) return null;
    return controller.getCurrentMediaItem().mediaId;
  }

  private void toggleFavourite() {
    String id = getCurrentSongId();
    if (id == null) return;
    boolean nowFav = PlaylistManager.get(this).toggleFavourite(id);
    applyFavouriteState(nowFav);
  }

  private void syncFavouriteButton() {
    String id = getCurrentSongId();
    if (id == null) return;
    applyFavouriteState(PlaylistManager.get(this).isFavourite(id));
  }

  private void applyFavouriteState(boolean isFav) {
    btnFavourite.setImageResource(isFav ? R.drawable.ic_heart : R.drawable.ic_heart_outline);
    btnFavourite.setAlpha(isFav ? 1f : 0.7f);
    btnFavourite.setColorFilter(isFav
            ? ContextCompat.getColor(this, R.color.accent_red)
            : ContextCompat.getColor(this, R.color.text_primary));
  }

  // ── Button state sync ─────────────────────────────────────────────────────
  private void syncShuffleButton() {
    boolean on = QueueManager.get().isShuffleOn();
    btnShuffle.setAlpha(on ? 1f : 0.4f);
    btnShuffle.setColorFilter(on
            ? ContextCompat.getColor(this, R.color.accent)
            : ContextCompat.getColor(this, R.color.text_primary));
  }

  private void syncRepeatButton() {
    QueueManager.RepeatMode mode = QueueManager.get().getRepeatMode();
    switch (mode) {
      case OFF:
        btnRepeat.setImageResource(R.drawable.repeat_24px);
        btnRepeat.setAlpha(0.4f);
        btnRepeat.clearColorFilter();
        break;
      case ALL:
        btnRepeat.setImageResource(R.drawable.repeat_24px);
        btnRepeat.setAlpha(1f);
        btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.accent));
        break;
      case ONE:
        btnRepeat.setImageResource(R.drawable.repeat_one_24px);
        btnRepeat.setAlpha(1f);
        btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.accent));
        break;
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  @SuppressLint("DefaultLocale")
  private String formatTime(int ms) {
    int seconds = (ms / 1000) % 60;
    int minutes = ms / 1000 / 60;
    return String.format("%d:%02d", minutes, seconds);
  }
}