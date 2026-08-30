package me.ash.resonance.remote;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

import me.ash.resonance.ResonanceApp;
import me.ash.resonance.playback.PlaybackSessionManager;
import me.ash.resonance.queue.QueueManager;

public class RemoteControlService extends Service {

  private RemoteControlManager remoteControlManager;

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    ResonanceApp app = (ResonanceApp) getApplication();
    app.getSharedController(controller -> {
      PlaybackSessionManager playbackManager = app.getPlaybackSessionManager();
      QueueManager queueManager = QueueManager.get();

      if (remoteControlManager == null && playbackManager != null) {
        remoteControlManager = RemoteControlManager.getInstance(app, playbackManager, queueManager);
        if (remoteControlManager != null) {
          remoteControlManager.start();
        }
      }
    });

    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    if (remoteControlManager != null) {
      remoteControlManager.stop();
    }
    super.onDestroy();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
