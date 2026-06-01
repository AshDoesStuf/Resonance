package me.ash.resonance.playback;

import android.content.Context;

import androidx.media3.common.Player;

import me.ash.resonance.queue.QueueManager;

public class PlaybackSessionManager {

  private final Player player;
  private final PlaybackSessionStore store;

  private boolean restored = false;

  private boolean shuffle;
  private int repeatMode;

  public PlaybackSessionManager(Player player, PlaybackSessionStore store) {
    this.player = player;
    this.store = store;
  }

  // ── CALLED ONCE IN APPLICATION ─────────────────────────
  public void restore(Context ctx) {
    if (restored) return;

    PlaybackSessionStore.Session session = store.load(ctx);
    if (session == null) return;

    restored = true;

    shuffle = session.shuffle;
    repeatMode = session.repeat;

    player.setMediaItems(session.items, session.index, session.position);
    player.setRepeatMode(repeatMode);
    player.setShuffleModeEnabled(shuffle);

    QueueManager.get().setShuffle(shuffle);
    QueueManager.get().setRepeatMode(repeatMode);

    player.prepare();
  }

  // ── MUST CALL PERIODICALLY OR ON STATE CHANGE ──────────
  public void persist(Context ctx) {
    store.save(ctx, player, shuffle, repeatMode);
  }

  // ── CONTROL LAYER ─────────────────────────────────────
  public void playPause() {
    if (player.isPlaying()) player.pause();
    else player.play();
  }

  public void next() {
    player.seekToNext();
  }

  public void previous() {
    player.seekToPrevious();
  }

  public void setShuffle(boolean value) {
    shuffle = value;
  }

  public void setRepeatMode(int mode) {
    repeatMode = mode;
    player.setRepeatMode(mode);
  }

  public Player getPlayer() {
    return player;
  }
}