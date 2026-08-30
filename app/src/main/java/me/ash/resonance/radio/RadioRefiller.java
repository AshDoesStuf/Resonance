package me.ash.resonance.radio;

import static me.ash.resonance.util.Utils.buildYtItem;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;

import me.ash.resonance.queue.QueueManager;
import me.ash.resonance.yt.YtTrack;

/**
 * Fetches and appends the next batch of radio tracks to the player's queue.
 * Extracted from MusicService — depends only on the ExoPlayer it's given.
 */
public class RadioRefiller {

  private final ExoPlayer player;

  public RadioRefiller(ExoPlayer player) {
    this.player = player;
  }

  public void refill() {
    RadioSession session = QueueManager.get().getActiveRadio();
    if (session == null) return;

    RadioEngine engine = new RadioEngine(session);

    engine.fetchNext(tracks -> {
      for (YtTrack t : tracks) {
        if (!session.isSeen(t.videoId)) {
          session.markSeen(t.videoId);
          MediaItem item = buildYtItem(t);
          player.addMediaItem(item);
        }
      }
    });
  }
}