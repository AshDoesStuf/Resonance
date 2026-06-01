package me.ash.resonance;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;

import me.ash.resonance.playback.PlaybackSessionManager;
import me.ash.resonance.queue.QueueManager;
import me.ash.resonance.ui.GlassStyleManager;
import me.ash.resonance.ui.PlayerPosition;

/**
 * Changes from original:
 * - No longer builds its own MediaController. Instead it receives the
 * shared controller that the host Activity already owns.
 * This avoids creating multiple controllers (one per screen) which
 * wastes resources and can cause subtle state drift.
 * <p>
 * Usage:
 * // In your Activity, after the shared controller is ready:
 * miniPlayerManager = new MiniPlayerManager(this);
 * miniPlayerManager.init(sharedController);
 */
public class MiniPlayerManager {

  private static PeekCallback peekCallback;
  private final AppCompatActivity activity;
  private final NowPlayingActivity peekingActivity = null;
  private float dragStartY;
  private boolean isDragging = false;
  private MediaController controller;
  private FrameLayout miniPlayer;
  private ImageView miniArt;
  private TextView miniTitle, miniArtist;
  private ImageButton miniPlay, miniPrevious, miniNext, miniQueue;
  private ImageView miniBluetoothIcon;
  private Player.Listener playerListener;
  private BlurBehindView miniBlurBehind;
  private boolean userPaused = false;
  private androidx.lifecycle.Observer<PlayerPosition> positionObserver;

  private android.media.AudioDeviceCallback audioDeviceCallback;

  public MiniPlayerManager(AppCompatActivity activity) {
    this.activity = activity;
  }

  public static void setPeekCallback(PeekCallback cb) {
    peekCallback = cb;
  }

  /**
   * Bind views and attach to the provided shared MediaController.
   * Call this once the controller is ready (e.g. inside the
   * ListenableFuture listener in your Activity).
   */
  public void init(MediaController sharedController) {
    this.controller = sharedController;
    PlaybackSessionManager session =
            ((ResonanceApp) activity.getApplication())
                    .getPlaybackSessionManager();

    miniPlayer = activity.findViewById(R.id.miniPlayer);
    miniArt = activity.findViewById(R.id.miniArt);
    miniTitle = activity.findViewById(R.id.miniTitle);
    miniArtist = activity.findViewById(R.id.miniArtist);
    miniPlay = activity.findViewById(R.id.miniPlay);
    miniPrevious = activity.findViewById(R.id.miniPrevious);
    miniNext = activity.findViewById(R.id.miniNext);
    miniQueue = activity.findViewById(R.id.miniQueue);
    miniBluetoothIcon = activity.findViewById(R.id.miniBluetoothIcon);
    miniBlurBehind = activity.findViewById(R.id.miniBlurBehind);

    miniPlay.setOnClickListener(v -> session.playPause());
    miniNext.setOnClickListener(v -> session.next());
    miniPrevious.setOnClickListener(v -> session.previous());
    miniQueue.setOnClickListener(v ->
            me.ash.resonance.queue.QueueBottomSheet.newInstance()
                    .show(activity.getSupportFragmentManager(),
                            me.ash.resonance.queue.QueueBottomSheet.TAG));

    attachPlayerListener();
    attachSwipeUpGesture();
    updateBluetoothIcon();
    registerAudioDeviceCallback();

//    applyGlassmorphism();

    // Show immediately if something is already playing
    MediaItem current = controller.getCurrentMediaItem();
    if (current != null) {
      updateUI(current);
      miniPlayer.setVisibility(View.VISIBLE);
      miniPlayer.post(() -> {
        if (miniBlurBehind != null)
          miniBlurBehind.refresh();
      });
    } else {
      // Nothing loaded yet — show from saved state if available
      QueueManager.SavedQueueState pending = QueueManager.get().getPendingRestore();
      if (pending != null && !pending.items.isEmpty()) {
        MediaItem saved = pending.items.get(pending.index);
        updateUI(saved);
        miniPlayer.setVisibility(View.VISIBLE);
        miniPlayer.post(() -> {
          if (miniBlurBehind != null)
            miniBlurBehind.refresh();
        });
      }
    }

    GlassStyleManager mgr = GlassStyleManager.get(activity);
    positionObserver = pos -> applyPosition(pos);
    mgr.observePosition().observeForever(positionObserver);
    applyPosition(mgr.currentPosition());
  }

  private void registerAudioDeviceCallback() {
    android.media.AudioManager am =
            (android.media.AudioManager) activity.getSystemService(android.content.Context.AUDIO_SERVICE);

    audioDeviceCallback = new android.media.AudioDeviceCallback() {
      @Override
      public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] addedDevices) {
        updateBluetoothIcon();
      }

      @Override
      public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] removedDevices) {
        updateBluetoothIcon();
      }
    };

    // null handler = callbacks delivered on the main thread
    am.registerAudioDeviceCallback(audioDeviceCallback, null);
  }

  private void applyPosition(PlayerPosition pos) {
    if (miniPlayer == null) return;

    ViewGroup.MarginLayoutParams lp =
            (ViewGroup.MarginLayoutParams) miniPlayer.getLayoutParams();

    int dp16 = (int) (16 * activity.getResources().getDisplayMetrics().density);

    if (pos == PlayerPosition.DOCKED) {
      // Stretch edge-to-edge, sit flush at bottom, keep pill shape via background
      lp.leftMargin = 0;
      lp.rightMargin = 0;
      lp.bottomMargin = 0;
      miniPlayer.setLayoutParams(lp);
      miniPlayer.setElevation(0f);
    } else {
      // Floating — restore original margins
      lp.leftMargin = dp16;
      lp.rightMargin = dp16;
      lp.bottomMargin = dp16;
      miniPlayer.setLayoutParams(lp);
      miniPlayer.setElevation(0f);
    }
  }

  private void attachPlayerListener() {
    playerListener = new Player.Listener() {
      @Override
      public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
        if (activity.isDestroyed()) return;
        if (item != null) {
          updateUI(item);
          miniPlayer.setVisibility(View.VISIBLE);
          if (miniBlurBehind != null) miniBlurBehind.refresh();
        }
      }

      @Override
      public void onIsPlayingChanged(boolean isPlaying) {
        if (activity.isDestroyed()) return;

        if (!isPlaying && userPaused) {
          // prevent accidental auto-resume logic elsewhere
          userPaused = false;
        }

        miniPlay.setImageResource(isPlaying
                ? R.drawable.ic_pause
                : R.drawable.ic_play);
      }

      @Override
      public void onPlaybackStateChanged(int state) {
        if (activity.isDestroyed()) return;
        if (state == Player.STATE_READY && controller.getCurrentMediaItem() != null) {
          MediaItem item = controller.getCurrentMediaItem();
          if (item != null) {
            updateUI(item);
            miniPlayer.setVisibility(View.VISIBLE);
            miniPlayer.post(() -> {
              if (miniBlurBehind != null)
                miniBlurBehind.refresh();
            });
          }
        }
      }

      @Override
      public void onTimelineChanged(androidx.media3.common.Timeline timeline, int reason) {
        if (activity.isDestroyed()) return;
        MediaItem item = controller.getCurrentMediaItem();
        if (item != null && miniPlayer.getVisibility() != View.VISIBLE) {
          updateUI(item);
          miniPlayer.setVisibility(View.VISIBLE);
          miniPlayer.post(() -> {
            if (miniBlurBehind != null)
              miniBlurBehind.refresh();
          });
        }
      }
    };
    controller.addListener(playerListener);
  }

  @SuppressLint("ClickableViewAccessibility")
  private void attachSwipeUpGesture() {
    final float SWIPE_THRESHOLD = 100f;
    final float VELOCITY_THRESHOLD = 100f;
    final int SCREEN_HEIGHT = activity.getResources().getDisplayMetrics().heightPixels;

    miniPlayer.setOnTouchListener((v, event) -> {
      switch (event.getAction()) {

        case MotionEvent.ACTION_DOWN:
          dragStartY = event.getRawY();
          isDragging = false;
          return true;

        case MotionEvent.ACTION_MOVE:
          float movedY = dragStartY - event.getRawY(); // positive = up
          if (movedY > 10 && !isDragging) {
            isDragging = true;
            // Launch NowPlaying underneath, fully translated off-screen
            Intent intent = new Intent(activity, NowPlayingActivity.class);
            intent.putExtra("peek_mode", true);
            activity.startActivity(intent);
            activity.overridePendingTransition(0, 0); // no default animation
          }
          if (isDragging && peekCallback != null) {
            // Map drag distance to translation: full drag = screen height → 0
            float progress = Math.min(movedY / SCREEN_HEIGHT, 1f);
            float translation = SCREEN_HEIGHT * (1f - progress);
            peekCallback.onDrag(translation);
          }
          return true;

        case MotionEvent.ACTION_UP:
          float deltaY = dragStartY - event.getRawY();
          float velocity = deltaY / (event.getEventTime() - event.getDownTime()) * 1000;

          if (isDragging) {
            boolean open = deltaY > SWIPE_THRESHOLD && velocity > VELOCITY_THRESHOLD;
            if (peekCallback != null) peekCallback.onRelease(open);
            isDragging = false;
            return true;
          }

          // Tap → open normally
          if (Math.abs(deltaY) < 10) {
            activity.startActivity(new Intent(activity, NowPlayingActivity.class));
          }
          return true;
      }
      return false;
    });
  }

  private void updateUI(MediaItem item) {
    CharSequence title = item.mediaMetadata.title;
    CharSequence artist = item.mediaMetadata.artist;

    miniTitle.setText(title != null ? title : "Unknown");
    miniArtist.setText(artist != null ? artist : "Unknown");

    Glide.with(activity)
            .load(item.mediaMetadata.artworkUri)
            .apply(new RequestOptions()
                    .placeholder(R.drawable.ic_note_outlined)
                    .error(R.drawable.ic_note_outlined)
                    .transform(new RoundedCorners(32)))
            .transition(DrawableTransitionOptions.withCrossFade(300))
            .into(miniArt);

    miniPlay.setImageResource(controller.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
  }

  private void updateBluetoothIcon() {
    if (miniBluetoothIcon == null) return;

    android.media.AudioManager am =
            (android.media.AudioManager) activity.getSystemService(android.content.Context.AUDIO_SERVICE);

    boolean isBluetooth = false;

    for (android.media.AudioDeviceInfo device :
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
      int type = device.getType();
      if (type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
              || type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
              || type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
              || type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER) {
        isBluetooth = true;
        break;
      }
    }

    miniBluetoothIcon.setVisibility(isBluetooth ? View.VISIBLE : View.GONE);
  }

  /**
   * Call from Activity.onDestroy() — but do NOT release the controller here
   * since it's shared and owned by the Activity. The Activity should release it.
   */
  public void detach() {
    if (controller != null && playerListener != null) {
      controller.removeListener(playerListener);
    }
    playerListener = null;
    controller = null;

    if (audioDeviceCallback != null) {
      android.media.AudioManager am =
              (android.media.AudioManager) activity.getSystemService(android.content.Context.AUDIO_SERVICE);
      am.unregisterAudioDeviceCallback(audioDeviceCallback);
      audioDeviceCallback = null;
    }
  }

  public interface PeekCallback {
    void onDrag(float translationY);

    void onRelease(boolean open);
  }
}