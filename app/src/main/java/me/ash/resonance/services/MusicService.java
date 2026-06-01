package me.ash.resonance.services;

import static me.ash.resonance.util.Utils.buildYtItem;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.CommandButton;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.ash.resonance.R;
import me.ash.resonance.playlist.PlaybackStatsManager;
import me.ash.resonance.queue.QueueManager;
import me.ash.resonance.queue.QueueStore;
import me.ash.resonance.radio.RadioEngine;
import me.ash.resonance.radio.RadioSession;
import me.ash.resonance.yt.StreamData;
import me.ash.resonance.yt.YtMusicService;
import me.ash.resonance.yt.YtTrack;

public class MusicService extends MediaSessionService {

  // ── Constants ─────────────────────────────────────────────────────────────
  /**
   * Max consecutive resolve errors before we stop auto-skipping.
   */
  private static final int MAX_CONSECUTIVE_ERRORS = 3;

  // ── Fields ────────────────────────────────────────────────────────────────
  // Stores YT items while their stream URL is being resolved
  private final Map<String, MediaItem> pending = new ConcurrentHashMap<>();

  // Tracks how many times in a row we've failed to resolve, to avoid
  // an infinite skip loop if the whole queue is broken.
  private int consecutiveErrors = 0;

  private ExoPlayer player;
  private MediaSession mediaSession;
  private RadioEngine radioEngine;

  @OptIn(markerClass = UnstableApi.class)
  @Override
  public void onCreate() {
    super.onCreate();

    // ── Notification channel (required on API 26+) ────────────────────
    NotificationChannel channel = new NotificationChannel(
            "resonance_playback",
            "Music Playback",
            NotificationManager.IMPORTANCE_LOW  // LOW = no sound, persistent
    );
    channel.setDescription("Resonance playback controls");
    channel.setShowBadge(false);
    getSystemService(NotificationManager.class).createNotificationChannel(channel);

    // ── Notification provider ─────────────────────────────────────────
    DefaultMediaNotificationProvider notificationProvider =
            new DefaultMediaNotificationProvider.Builder(this)
                    .setChannelId("resonance_playback")
                    .setChannelName(R.string.app_name)
                    .setNotificationId(1001)
                    .build();
    setMediaNotificationProvider(notificationProvider);

    // ── HTTP data source (your existing setup) ────────────────────────
    DefaultHttpDataSource.Factory httpFactory =
            new DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0")
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(Map.of(
                            "Referer", "https://www.youtube.com/",
                            "Origin", "https://www.youtube.com"
                    ));

    DefaultDataSource.Factory dataSourceFactory =
            new DefaultDataSource.Factory(this, httpFactory);

    // ── ExoPlayer ─────────────────────────────────────────────────────
    player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(                          // request audio focus
                    new AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                    true                                  // handle audio focus
            )
            .setHandleAudioBecomingNoisy(true)            // pause on headphone unplug

            .build();

    player.addListener(new Player.Listener() {
      @Override
      public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
        if (item == null) return;
        Log.d("EXO", "TRANSITION = " + item.mediaId);
        consecutiveErrors = 0;

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
          PlaybackStatsManager.get(getApplicationContext()).recordPlay(item.mediaId);
        }

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
          if (QueueManager.get().getActiveRadio() != null) {
            QueueManager.get().getActiveRadio().markSkipped(item.mediaId);
          }
        }
        // Save position whenever track changes
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
          int index = player.getCurrentMediaItemIndex();
          int size = player.getMediaItemCount();

          RadioSession session = QueueManager.get().getActiveRadio();

          if (session != null && size - index <= 5) {
            refillRadio();
          }
        }
        saveQueueState();
      }

      @Override
      public void onPlaybackStateChanged(int state) {
        Log.d("EXO", "STATE = " + state);
        if (state == Player.STATE_ENDED) {
          // Auto-clear if this was a temp session
          if (QueueManager.get().isTempSession()) {
            QueueManager.get().endTempSession(player);
          }
        }
        if (state == Player.STATE_READY && !player.isPlaying()) {
          saveQueueState();
        }
      }

      @Override
      public void onPlayerError(PlaybackException error) {
        Log.e("EXO", "ERROR", error);
        Log.e("EXO", "ERROR TYPE = " + error.getErrorCodeName());
        Log.e("EXO", "CAUSE = ", error.getCause());
        handlePlaybackError();
      }
    });

    // ── MediaSession ──────────────────────────────────────────────────
    Intent openAppIntent = getPackageManager()
            .getLaunchIntentForPackage(getPackageName());
    openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

    PendingIntent openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );

    mediaSession = new MediaSession.Builder(this, player)
            .setCallback(new ResolverCallback())
            .setSessionActivity(openAppPendingIntent)
            .build();
    mediaSession.setMediaButtonPreferences(ImmutableList.of(
            new CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                    .setDisplayName("Previous")
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build(),
            new CommandButton.Builder(CommandButton.ICON_PLAY)
                    .setDisplayName("Play/Pause")
                    .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                    .build(),
            new CommandButton.Builder(CommandButton.ICON_NEXT)
                    .setDisplayName("Next")
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .build()
    ));

    QueueStore store = new QueueStore(this);
    QueueManager.SavedQueueState saved = store.restoreCurrentQueue(this);
    if (saved != null && !saved.items.isEmpty()) {
      QueueManager.get().setOriginalItems(saved.items); // restore originals from saved queue
      QueueManager.get().setPendingRestore(saved);
    }
  }

  @Override
  public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
    return mediaSession;
  }

  @Override
  public void onTaskRemoved(Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    if (QueueManager.get().isTempSession()) {
      QueueManager.get().endTempSession(player);
    }
    saveQueueState();
  }

  @Override
  public void onDestroy() {
    mediaSession.release();
    player.release();
    super.onDestroy();
  }

  // ── Error recovery ────────────────────────────────────────────────────────

  /**
   * Called when ExoPlayer reports a playback error OR when a stream URL
   * could not be resolved. Skips to the next track up to
   * MAX_CONSECUTIVE_ERRORS times; after that it stops trying so we don't
   * spin through an entire broken queue.
   */
  private void handlePlaybackError() {
    if (!player.isPlaying()) return;
    consecutiveErrors++;
    if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
      Log.w("MusicService", "Too many consecutive errors, stopping auto-skip.");
      player.pause();
      consecutiveErrors = 0;
      return;
    }

    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
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

  private void saveQueueState() {
    if (player == null || player.getMediaItemCount() == 0) return;
    if (QueueManager.get().isSmallWindow()) {
      Log.d("MusicService", "Skipping persist — small window queue");
      return;
    }
    new QueueStore(this).saveCurrentQueue(this, player);
  }

  private void refillRadio() {

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

  // ── Stream resolution ─────────────────────────────────────────────────────
  private void resolveAsync(String videoId) {
    Log.d("MusicService", "resolveAsync called for " + videoId);
    YtMusicService.get().resolveStreamUrl(videoId, new YtMusicService.StreamCallback() {

      @Override
      public void onStream(StreamData stream) {
        pending.remove(videoId);
        Log.d("MusicService", "STREAM URL = " + stream.url);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
          for (int i = 0; i < player.getMediaItemCount(); i++) {
            MediaItem current = player.getMediaItemAt(i);
            if (videoId.equals(current.mediaId)) {
              MediaItem finalItem = current.buildUpon()
                      .setUri(stream.url)
                      .setMimeType(stream.mimeType)
                      .setRequestMetadata(
                              new MediaItem.RequestMetadata.Builder()
                                      .setMediaUri(Uri.parse("ytmusic://" + videoId))
                                      .build()
                      )
                      .build();
              player.replaceMediaItem(i, finalItem);

              if (i == player.getCurrentMediaItemIndex()) {
                player.seekTo(i, 0);
                player.prepare();
                player.play();
              }
              break;
            }
          }
        });
      }

      @Override
      public void onError(Exception e) {
        Log.e("MusicService", "YT resolve failed for " + videoId, e);
        pending.remove(videoId);

        // If this was the currently playing item, skip rather than stall
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
          for (int i = 0; i < player.getMediaItemCount(); i++) {
            if (videoId.equals(player.getMediaItemAt(i).mediaId)
                    && i == player.getCurrentMediaItemIndex()) {
              Log.d("MusicService", "Resolve failed for current item, skipping.");
              handlePlaybackError();
              break;
            }
          }
        });
      }
    });
  }

  // ── MediaSession callback ─────────────────────────────────────────────────
  private class ResolverCallback implements MediaSession.Callback {

    @OptIn(markerClass = UnstableApi.class)
    @NonNull
    @Override
    public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
            MediaSession mediaSession,
            MediaSession.ControllerInfo controller
    ) {
      // If we have a pending restore, return it
      QueueManager.SavedQueueState pending = QueueManager.get().getPendingRestore();
      if (pending != null && !pending.items.isEmpty()) {
        QueueManager.get().clearPendingRestore();
        List<MediaItem> items = new ArrayList<>();
        for (MediaItem item : pending.items) {
          items.add(QueueManager.buildPlayableItem(item));
        }
        return Futures.immediateFuture(
                new MediaSession.MediaItemsWithStartPosition(
                        items, pending.index, pending.positionMs
                )
        );
      }
      // Nothing to resume
      return Futures.immediateFailedFuture(
              new UnsupportedOperationException("No items to resume")
      );
    }

    @NonNull
    @Override
    public ListenableFuture<List<MediaItem>> onAddMediaItems(
            MediaSession session,
            MediaSession.ControllerInfo controller,
            List<MediaItem> items
    ) {
      List<MediaItem> out = new ArrayList<>();

      for (MediaItem item : items) {
        Uri uri = item.localConfiguration != null
                ? item.localConfiguration.uri
                : null;

        if (uri == null) {
          continue;
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
          out.add(item);
          continue;
        }

        switch (scheme) {
          case "ytmusic": {

            String videoId;

            if (uri.getHost() != null) {
              // ytmusic://VIDEO_ID
              videoId = uri.getHost();
            } else {
              // ytmusic://track/VIDEO_ID
              List<String> segments = uri.getPathSegments();
              videoId = segments.isEmpty() ? null : segments.get(segments.size() - 1);
            }

            if (videoId == null || videoId.isEmpty()) {
              Log.e("MusicService", "Invalid YT URI: " + uri);
              break;
            }

            MediaItem placeholder = item.buildUpon()
                    .setMediaId(videoId)
                    .build();

            pending.put(videoId, placeholder);
            out.add(placeholder);

            Log.d("MusicService", "Resolving stream for " + videoId);

            resolveAsync(videoId);
            break;
          }
          case "file":
          case "content":
          default:
            out.add(item);
            break;
        }
      }

      return Futures.immediateFuture(out);
    }
  }
}