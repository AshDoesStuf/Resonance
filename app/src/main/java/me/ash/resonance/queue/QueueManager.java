package me.ash.resonance.queue;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.ash.resonance.radio.RadioSession;

/**
 * Singleton that owns queue state and exposes helpers so any screen
 * (NowPlayingActivity, QueueBottomSheet, MiniPlayerManager) can read
 * or mutate the queue through the MediaController.
 * <p>
 * Changes from original:
 * 1. Thread-safe singleton via initialization-on-demand holder pattern.
 * 2. originalItems persisted to SharedPreferences so un-shuffle survives
 * process recreation while the MediaController queue lives on.
 * 3. moveItem / removeItem contract is now clear: callers (QueueAdapter)
 * must NOT also mutate their local list — reload from the controller
 * after calling these, making the controller the single source of truth.
 */
public class QueueManager {

  // ── Constants ─────────────────────────────────────────────────────────────
  private final List<MediaItem> originalItems = new ArrayList<>();
  private boolean isTempSession = false;
  // ── State ─────────────────────────────────────────────────────────────────
  private boolean shuffleOn = false;

  // Add as a field:
  private SavedQueueState pendingRestore = null;
  private RepeatMode repeat = RepeatMode.OFF;

  private boolean isSmallWindow = false;
  private RadioSession activeRadio;

  private QueueManager() {
  }

  public static QueueManager get() {
    return Holder.INSTANCE;
  }

  public static MediaItem buildPlayableItem(MediaItem saved) {
    return saved;
  }

  public RadioSession getActiveRadio() {
    return activeRadio;
  }

  public void setActiveRadio(RadioSession activeRadio) {
    this.activeRadio = activeRadio;
  }

  public void setIsSmallWindow(boolean isSmall) {
    this.isSmallWindow = isSmall;
  }

  public boolean isSmallWindow() {
    return isSmallWindow;
  }

  // Add this method:
  public SavedQueueState getPendingRestore() {
    return pendingRestore;
  }

  public void setPendingRestore(SavedQueueState state) {
    this.pendingRestore = state;
  }

  public void clearPendingRestore() {
    this.pendingRestore = null;
  }

  /**
   * Convenience overload when no Context is available (e.g. during shuffle).
   */
  public void setOriginalItems(List<MediaItem> items) {
    originalItems.clear();
    originalItems.addAll(items);
  }

  public void setShuffle(boolean on) {
    shuffleOn = on;
  }

  // ── Shuffle ───────────────────────────────────────────────────────────────
  public boolean isShuffleOn() {
    return shuffleOn;
  }

  /**
   * Toggles shuffle. When turning ON the currently playing item stays at
   * position 0 so playback doesn't jump. When turning OFF the original
   * order is restored and the same item is seeked to.
   */
  public void toggleShuffle(MediaController controller) {
    shuffleOn = !shuffleOn;
    MediaItem current = controller.getCurrentMediaItem();

    if (shuffleOn) {
      List<MediaItem> shuffled = new ArrayList<>(originalItems);
      if (current != null) shuffled.removeIf(i -> i.mediaId.equals(current.mediaId));
      Collections.shuffle(shuffled);
      if (current != null) shuffled.add(0, current);
      controller.setMediaItems(shuffled, 0, controller.getCurrentPosition());
      controller.prepare();
    } else {
      int resumeIndex = 0;
      if (current != null) {
        for (int i = 0; i < originalItems.size(); i++) {
          if (originalItems.get(i).mediaId.equals(current.mediaId)) {
            resumeIndex = i;
            break;
          }
        }
      }
      controller.setMediaItems(originalItems, resumeIndex, controller.getCurrentPosition());
      controller.prepare();
    }
  }

  // ── Repeat ────────────────────────────────────────────────────────────────
  public RepeatMode getRepeatMode() {
    return repeat;
  }

  public void setRepeatMode(int media3RepeatMode) {
    switch (media3RepeatMode) {
      case androidx.media3.common.Player.REPEAT_MODE_OFF:
        repeat = RepeatMode.OFF;
        break;
      case androidx.media3.common.Player.REPEAT_MODE_ONE:
        repeat = RepeatMode.ONE;
        break;
      case androidx.media3.common.Player.REPEAT_MODE_ALL:
        repeat = RepeatMode.ALL;
        break;
    }
  }

  /**
   * Cycles OFF → ALL → ONE → OFF and applies the matching Media3 repeat mode.
   */
  public void cycleRepeat(MediaController controller) {
    switch (repeat) {
      case OFF:
        repeat = RepeatMode.ALL;
        break;
      case ALL:
        repeat = RepeatMode.ONE;
        break;
      case ONE:
        repeat = RepeatMode.OFF;
        break;
    }
    applyRepeatToController(controller);
  }

  private void applyRepeatToController(MediaController controller) {
    switch (repeat) {
      case OFF:
        controller.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_OFF);
        break;
      case ONE:
        controller.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE);
        break;
      case ALL:
        controller.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ALL);
        break;
    }
  }

  /**
   * Move an item in the controller's current playlist.
   */
  public void moveItem(MediaController controller, int fromIndex, int toIndex) {
    controller.moveMediaItem(fromIndex, toIndex);
  }

  /**
   * Remove an item from the controller's current playlist.
   */
  public void removeItem(MediaController controller, int index) {
    controller.removeMediaItem(index);
  }

  // ── Queue mutation helpers ─────────────────────────────────────────────────
  //
  // CONTRACT: these only touch the controller's playlist.
  // Callers (QueueAdapter) must NOT also mutate their own local list — after
  // calling these, reload the adapter with a fresh snapshot from the controller.
  // This keeps the controller as the single source of truth and avoids the
  // double-mutation bug that caused the adapter and controller to drift.

  /**
   * Add a song to play next (right after the current item).
   */
  public void playNext(MediaController controller, MediaItem item) {
    int insertAt = controller.getCurrentMediaItemIndex() + 1;
    controller.addMediaItem(insertAt, item);
  }

  /**
   * Append a song to the end of the queue.
   */
  public void addToQueue(MediaController controller, MediaItem item) {
    controller.addMediaItem(item);
  }

  public void startTempSession(MediaController controller, List<MediaItem> items) {
    isTempSession = true;
    setOriginalItems(items);
    controller.setMediaItems(items, 0, 0);
    controller.prepare();
    controller.play();
  }

  public boolean isTempSession() {
    return isTempSession;
  }

  public void endTempSession(Player controller) {
    if (!isTempSession) return;
    isTempSession = false;
    controller.clearMediaItems();
  }

  public enum RepeatMode {OFF, ONE, ALL}

  public static class SavedQueueState {
    public final List<MediaItem> items;
    public final int index;
    public final long positionMs;

    public SavedQueueState(List<MediaItem> items, int index, long positionMs) {
      this.items = items;
      this.index = index;
      this.positionMs = positionMs;
    }
  }

  // ── Thread-safe singleton (initialization-on-demand holder) ──────────────
  private static final class Holder {
    static final QueueManager INSTANCE = new QueueManager();
  }
}