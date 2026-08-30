package me.ash.resonance.services;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.exoplayer.ExoPlayer;

/**
 * Tracks consecutive playback/resolve errors and auto-skips up to a limit,
 * so a broken queue doesn't spin forever. Extracted from MusicService —
 * depends only on the ExoPlayer it's given.
 */
public class PlaybackErrorRecovery {

  private static final int MAX_CONSECUTIVE_ERRORS = 3;

  private final ExoPlayer player;
  private int consecutiveErrors = 0;

  public PlaybackErrorRecovery(ExoPlayer player) {
    this.player = player;
  }

  public void reset() {
    consecutiveErrors = 0;
  }

  public void handleError() {
    consecutiveErrors++;
    if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
      Log.w("MusicService", "Too many consecutive errors, stopping auto-skip.");
      player.pause();
      consecutiveErrors = 0;
      return;
    }

    new Handler(Looper.getMainLooper()).post(() -> {
      if (player.hasNextMediaItem()) {
        Log.d("MusicService", "Skipping to next after error (" + consecutiveErrors + ")");
        player.seekToNextMediaItem();
        player.prepare();
        player.play();
      } else {
        Log.d("MusicService", "No next item after error, stopping.");
        player.pause();
        consecutiveErrors = 0;
      }
    });
  }
}