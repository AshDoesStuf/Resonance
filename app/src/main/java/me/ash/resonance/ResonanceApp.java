package me.ash.resonance;// In ResonanceApp.java, add these fields and methods:

import android.app.Application;
import android.content.ComponentName;

import androidx.core.content.ContextCompat;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.android.material.color.DynamicColors;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

import me.ash.resonance.playback.PlaybackSessionManager;
import me.ash.resonance.playback.PlaybackSessionStore;
import me.ash.resonance.services.MusicService;
import me.ash.resonance.yt.YtMusicService;

public class ResonanceApp extends Application {

  private final List<ControllerListener> pendingListeners = new ArrayList<>();
  private ListenableFuture<MediaController> controllerFuture;
  private MediaController sharedController;
  private PlaybackSessionManager playbackSessionManager;

  @Override
  public void onCreate() {
    super.onCreate();
    DynamicColors.applyToActivitiesIfAvailable(this);
    YtMusicService.init(this);
    initSharedController();
  }

  private void initSharedController() {
    SessionToken token = new SessionToken(
            this, new ComponentName(this, MusicService.class));
    controllerFuture = new MediaController.Builder(this, token).buildAsync();
    controllerFuture.addListener(() -> {
      try {
        sharedController = controllerFuture.get();

        PlaybackSessionStore store = new PlaybackSessionStore();
        playbackSessionManager =
                new PlaybackSessionManager(sharedController, store);

        playbackSessionManager.restore(this);

        for (ControllerListener l : pendingListeners) {
          l.onControllerReady(sharedController);
        }
        pendingListeners.clear();

      } catch (Exception e) {
        e.printStackTrace();
      }
    }, ContextCompat.getMainExecutor(this));
  }

  public PlaybackSessionManager getPlaybackSessionManager() {
    return playbackSessionManager;
  }

  public MediaController getSharedController() {
    return sharedController;
  }

  // Call this only when the entire app is truly going away
  @Override
  public void onTerminate() {
    super.onTerminate();
    MediaController.releaseFuture(controllerFuture);
  }

  public void getSharedController(ControllerListener listener) {
    if (sharedController != null) {
      // Already ready — call back immediately
      listener.onControllerReady(sharedController);
    } else {
      // Queue it — will be called once the future resolves
      pendingListeners.add(listener);
    }
  }

  public interface ControllerListener {
    void onControllerReady(MediaController controller);
  }
}