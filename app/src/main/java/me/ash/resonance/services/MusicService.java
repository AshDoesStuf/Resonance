package me.ash.resonance.services;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.CommandButton;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.ash.resonance.R;
import me.ash.resonance.playback.PlaybackSessionStore;
import me.ash.resonance.playlist.PlaybackStatsManager;
import me.ash.resonance.queue.QueueManager;
import me.ash.resonance.radio.RadioEngine;
import me.ash.resonance.radio.RadioRefiller;
import me.ash.resonance.radio.RadioSession;
import me.ash.resonance.remote.RemoteControlManager;
import me.ash.resonance.remote.RemoteStreamManager;
import me.ash.resonance.sharedlistening.audio.AudioCaptureProcessor;
import me.ash.resonance.sharedlistening.audio.OpusEncoderWrapper;
import me.ash.resonance.sharedlistening.discovery.NearbyDiscoveryManager;
import me.ash.resonance.sharedlistening.model.AudioFramePacket;
import me.ash.resonance.sharedlistening.session.SessionManager;
import me.ash.resonance.sharedlistening.transport.NearbyTransport;
import me.ash.resonance.sharedlistening.transport.model.Packet;
import me.ash.resonance.util.PlaybackSettingsManager;
import me.ash.resonance.yt.StreamData;
import me.ash.resonance.yt.YtMusicService;

public class MusicService extends MediaSessionService {

  // Stream url resolutions allowed per song before a refused stream is treated
  // as fatal and the track is actually skipped. googlevideo can hand out a url
  // that passes validation and then 403/410s partway through — this lets the
  // player re-resolve against a different client instead of abandoning the song.
  private static final int MAX_STREAM_REFRESHES = 5;
  // ── Constants ─────────────────────────────────────────────────────────────
  // Tracks how many times in a row we've failed to resolve, to avoid
  // an infinite skip loop if the whole queue is broken.
  private static MusicService instance;
  private final Map<String, String[]> songUrlCache = new java.util.concurrent.ConcurrentHashMap<>();
  private final Map<String, Integer> streamRefreshes = new java.util.concurrent.ConcurrentHashMap<>();
  // ── Fields ────────────────────────────────────────────────────────────────
  // Stores YT items while their stream URL is being resolved
  private final Map<String, MediaItem> pending = new ConcurrentHashMap<>();
  private final Handler crossfadeHandler = new Handler(Looper.getMainLooper());
  private boolean crossfadeActive = false;
  private RadioRefiller radioRefiller;
  private PlaybackErrorRecovery errorRecovery;
  private ExoPlayer player;
  private Player forwardingPlayer;
  private MediaSession mediaSession;
  private RadioEngine radioEngine;
  // Shared Listening
  private NearbyDiscoveryManager discoveryManager;
  private NearbyTransport transport;
  private SessionManager sessionManager;
  private AudioCaptureProcessor audioCaptureProcessor;
  private OpusEncoderWrapper opusEncoder;
  private ExoPlayer crossfadePlayer;
  private final Runnable crossfadeRunnable = new Runnable() {
    @Override
    public void run() {
      checkCrossfade();
      crossfadeHandler.postDelayed(this, 50);
    }
  };

  public static MusicService getInstance() {
    return instance;
  }

  private static long extractExpireMs(String url) {
    try {
      String expire = Uri.parse(url).getQueryParameter("expire");
      if (expire != null) return Long.parseLong(expire) * 1000L;
    } catch (Exception ignored) {
    }
    return System.currentTimeMillis() + (5 * 60 * 60 * 1000L);
  }

  @OptIn(markerClass = UnstableApi.class)
  @Override
  public void onCreate() {
    super.onCreate();
    instance = this;

    // ── Notification channel (required on API 26+) ────────────────────
    NotificationChannel channel = new NotificationChannel(
            "resonance_playback",
            "Music Playback",
            NotificationManager.IMPORTANCE_LOW
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

    // ── Data source stack (mirrors OuterTune exactly) ─────────────────
    // OkHttpDataSource at the bottom, no custom YtDataSource needed.
    // Range chunking is handled by subrange() in the resolver, not a custom DS.
    DataSource.Factory httpFactory = new OkHttpDataSource.Factory(YtMusicService.get().http);

    SimpleCache playerCache = YtMusicService.getPlayerCache(this);

    CacheDataSource.Factory cacheFactory = new CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(
                    new DefaultDataSource.Factory(this, httpFactory)
            )
            .setUpstreamPriority(C.PRIORITY_PLAYBACK)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

    ResolvingDataSource.Factory resolvingFactory = getResolvingFactory(cacheFactory);

    // ── Shared Listening Initialization ──────────────────────────────
    discoveryManager = new NearbyDiscoveryManager(this);
    transport = new NearbyTransport(this, discoveryManager);
    audioCaptureProcessor = new AudioCaptureProcessor();
    opusEncoder = new OpusEncoderWrapper();

    // ── ExoPlayer ─────────────────────────────────────────────────────
    player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(resolvingFactory))
            .setRenderersFactory(new androidx.media3.exoplayer.DefaultRenderersFactory(this) {
              private androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
                      android.content.Context context,
                      boolean enableFloatOutput,
                      boolean enableAudioTrackPlaybackParams,
                      boolean enableOffload
              ) {
                return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                        .setAudioProcessors(new androidx.media3.common.audio.AudioProcessor[]{audioCaptureProcessor})
                        .build();
              }
            })
            .setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                    true
            )
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                            15_000,
                            50_000,
                            1_500,
                            5_000
                    )
                    .build())
            .build();

    radioRefiller = new RadioRefiller(player);
    errorRecovery = new PlaybackErrorRecovery(player);

    crossfadePlayer = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(resolvingFactory))
            .setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                    true
            )
            .setHandleAudioBecomingNoisy(false)
            .build();


    forwardingPlayer = new ForwardingPlayer(player) {
      @Override
      public void play() {
        RemoteControlManager rcm = RemoteControlManager.getInstance();
        if (rcm != null) {
          RemoteStreamManager rsm = rcm.getRemoteStreamManager();
          if (rsm != null && rsm.isEnabled()) {
            rsm.onRemotePlayPause(true);
            return;
          }
        }
        super.play();
      }

      @Override
      public void pause() {
        RemoteControlManager rcm = RemoteControlManager.getInstance();
        if (rcm != null) {
          RemoteStreamManager rsm = rcm.getRemoteStreamManager();
          if (rsm != null && rsm.isEnabled()) {
            rsm.onRemotePlayPause(false);
            return;
          }
        }
        super.pause();
      }

      @Override
      public boolean isPlaying() {
        RemoteControlManager rcm = RemoteControlManager.getInstance();
        if (rcm != null) {
          RemoteStreamManager rsm = rcm.getRemoteStreamManager();
          if (rsm != null && rsm.isEnabled()) {
            return rsm.isLogicalPlaying();
          }
        }
        return super.isPlaying();
      }

      @Override
      public int getPlaybackState() {
        RemoteControlManager rcm = RemoteControlManager.getInstance();
        if (rcm != null) {
          RemoteStreamManager rsm = rcm.getRemoteStreamManager();
          if (rsm != null && rsm.isEnabled()) {
            return Player.STATE_READY;
          }
        }
        return super.getPlaybackState();
      }

      @Override
      public long getCurrentPosition() {
        RemoteControlManager rcm = RemoteControlManager.getInstance();
        if (rcm != null) {
          RemoteStreamManager rsm = rcm.getRemoteStreamManager();
          if (rsm != null && rsm.isEnabled()) {
            return rsm.getLastReportedPositionMs();
          }
        }
        return super.getCurrentPosition();
      }

      @Override
      public long getContentPosition() {
        RemoteControlManager rcm = RemoteControlManager.getInstance();
        if (rcm != null) {
          RemoteStreamManager rsm = rcm.getRemoteStreamManager();
          if (rsm != null && rsm.isEnabled()) {
            return rsm.getLastReportedPositionMs();
          }
        }
        return super.getContentPosition();
      }
    };

    sessionManager = new SessionManager(this, discoveryManager, transport, forwardingPlayer);

    crossfadeHandler.post(crossfadeRunnable);

    // Hook up audio streaming
    audioCaptureProcessor.setListener((data, sampleRate, channelCount) -> {
      if (sessionManager.isSessionActive()) {
        opusEncoder.encode(data, sampleRate, channelCount);
      }
    });

    opusEncoder.setCallback(data -> {
      if (sessionManager.isSessionActive()) {
        sessionManager.getListeners().forEach((id, listener) -> {
          transport.sendPacket(id, new Packet(Packet.TYPE_AUDIO_FRAME, new AudioFramePacket(data)));
        });
      }
    });

    player.addListener(new Player.Listener() {
      @Override
      public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
        if (item == null) return;
        Log.d("EXO", "TRANSITION = " + item.mediaId + " reason=" + reason);

        RemoteControlManager rcm = RemoteControlManager.getInstance();
        if (rcm != null) {
          RemoteStreamManager rsm = rcm.getRemoteStreamManager();
          if (rsm != null) {
            rsm.handleTrackChange(item);
          }
        }

        errorRecovery.reset();
        // A track that made it here started playing again; give it a full
        // refresh budget next time it comes around rather than carrying
        // over attempts from a previous failure.
        streamRefreshes.remove(item.mediaId);

        PlaybackStatsManager.get(getApplicationContext()).recordPlay(item.mediaId);

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
          if (QueueManager.get().getActiveRadio() != null) {
            QueueManager.get().getActiveRadio().markSkipped(item.mediaId);
          }
        }
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
          int index = player.getCurrentMediaItemIndex();
          int size = player.getMediaItemCount();
          RadioSession session = QueueManager.get().getActiveRadio();
          if (session != null && size - index <= 5) {
            radioRefiller.refill();
          }
        }
        saveQueueState();
        sessionManager.broadcastMetadata();
        sessionManager.broadcastPlaybackState();
      }

      @Override
      public void onIsPlayingChanged(boolean isPlaying) {
        sessionManager.broadcastPlaybackState();
      }

      @Override
      public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
          sessionManager.broadcastSeek(newPosition.positionMs);
        }
      }

      @Override
      public void onPlaybackStateChanged(int state) {
        Log.d("EXO", "STATE = " + state);
        if (state == Player.STATE_ENDED) {
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

        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
          MediaItem current = player.getCurrentMediaItem();
          if (current != null) {
            String videoId = current.mediaId;
            if (videoId != null && !videoId.isEmpty()) {
              Log.d("MusicService", "Evicting stream cache for " + videoId);
              YtMusicService.get().evictStreamCache(videoId);
              // Also evict the in-memory URL cache so next resolve is fresh
              songUrlCache.remove(videoId);

              // googlevideo can hand out a url that passes validation and then
              // 403/410s partway through the track. That's a bad url from this
              // particular client, not necessarily a dead song — re-resolve
              // (which will fall through to the next client in the chain)
              // before giving up on it entirely.
              Throwable cause = error.getCause();
              Integer httpStatus = null;
              if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException httpEx) {
                httpStatus = httpEx.responseCode;
              }
              if (httpStatus != null && (httpStatus == 403 || httpStatus == 410)) {
                int attempts = streamRefreshes.getOrDefault(videoId, 0);
                if (attempts < MAX_STREAM_REFRESHES) {
                  streamRefreshes.put(videoId, attempts + 1);
                  Log.w("MusicService", "Stream for " + videoId + " refused (HTTP "
                          + httpStatus + "), re-resolving (" + (attempts + 1) + "/"
                          + MAX_STREAM_REFRESHES + ")");
                  player.prepare();
                  player.play();
                  return;
                } else {
                  Log.w("MusicService", "Stream for " + videoId
                          + " still refused after " + attempts + " refreshes, giving up");
                }
              }
            }
          }
        }
        errorRecovery.handleError();
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

    mediaSession = new MediaSession.Builder(this, forwardingPlayer)
            .setCallback(new ResolverCallback(pending))
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

    PlaybackSessionStore.Session session = new PlaybackSessionStore().load(this);
    if (session != null && !session.items().isEmpty()) {
      QueueManager.get().setOriginalItems(session.items());
      QueueManager.get().setPendingRestore(new QueueManager.SavedQueueState(
              session.items(), session.index(), session.position()
      ));
    }
  }

  private void checkCrossfade() {
    if (player == null || !player.getPlayWhenReady()) return;

    int durationSec = PlaybackSettingsManager.get(this).getCrossfadeDuration();
    if (durationSec <= 0) {
      endCrossfadeIfActive();
      if (player.getVolume() < 1.0f) player.setVolume(1.0f);
      return;
    }

    long durationMs = player.getDuration();
    long positionMs = player.getCurrentPosition();
    if (durationMs <= 0 || durationMs == C.TIME_UNSET) return;

    long crossfadeMs = durationSec * 1000L;
    if (durationMs < crossfadeMs * 2) {
      endCrossfadeIfActive();
      if (player.getVolume() < 1.0f) player.setVolume(1.0f);
      return;
    }

    long remainingMs = durationMs - positionMs;

    if (remainingMs < crossfadeMs) {
      if (!crossfadeActive) startCrossfade();
      if (crossfadeActive) {
        float progress = 1f - ((float) remainingMs / crossfadeMs);
        player.setVolume(Math.max(0f, 1f - progress));
        crossfadePlayer.setVolume(Math.min(1f, progress));
        if (remainingMs <= 150) finishCrossfade();
      }
    } else {
      if (player.getVolume() < 1.0f) player.setVolume(1.0f);
    }
  }

  @OptIn(markerClass = UnstableApi.class)
  private void startCrossfade() {
    int nextIndex = player.getCurrentMediaItemIndex() + 1;
    if (nextIndex >= player.getMediaItemCount())
      return; // last track in queue, nothing to fade into

    MediaItem next = player.getMediaItemAt(nextIndex);

    player.setPauseAtEndOfMediaItems(true); // stop player auto-advancing into B on its own

    crossfadePlayer.setMediaItem(next);
    crossfadePlayer.setVolume(0f);
    crossfadePlayer.prepare();
    crossfadePlayer.setPlayWhenReady(true);

    crossfadeActive = true;
  }

  @OptIn(markerClass = UnstableApi.class)
  private void finishCrossfade() {
    if (!crossfadeActive) return;

    long handoffPos = crossfadePlayer.getCurrentPosition();

    player.seekToNextMediaItem();
    player.seekTo(handoffPos);
    player.setVolume(1f);
    player.setPauseAtEndOfMediaItems(false);
    player.play();

    crossfadePlayer.pause();
    crossfadePlayer.stop();
    crossfadePlayer.clearMediaItems();
    crossfadePlayer.setVolume(1f);

    crossfadeActive = false;
  }

  @OptIn(markerClass = UnstableApi.class)
  private void endCrossfadeIfActive() {
    if (!crossfadeActive) return;
    crossfadePlayer.pause();
    crossfadePlayer.stop();
    crossfadePlayer.clearMediaItems();
    crossfadePlayer.setVolume(1f);
    player.setPauseAtEndOfMediaItems(false);
    crossfadeActive = false;
  }

  public SessionManager getSessionManager() {
    return sessionManager;
  }

  public Player getPlayer() {
    return forwardingPlayer;
  }

  public Player getRawPlayer() {
    return player;
  }

  @Nullable
  public StreamData getSongUrlCache(String videoId) {
    String[] cached = songUrlCache.get(videoId);
    if (cached == null) return null;
    try {
      if (System.currentTimeMillis() > Long.parseLong(cached[1])) {
        return null;
      }
      return new StreamData(cached[0], "audio/mpeg", cached[2]);
    } catch (Exception e) {
      return null;
    }
  }


  @OptIn(markerClass = UnstableApi.class)
  private ResolvingDataSource.Factory getResolvingFactory(DataSource.Factory upstreamFactory) {
    return new ResolvingDataSource.Factory(upstreamFactory, dataSpec -> {
      Uri uri = dataSpec.uri;
      if (!"ytmusic".equals(uri.getScheme())) return dataSpec;

      String videoId = uri.getAuthority();
      if (videoId == null || videoId.isEmpty()) return dataSpec;


      // ── 1. In-memory URL cache — fastest path ─────────────────────────
      // Always check this first. Even for cached bytes, CacheDataSource
      // needs a real HTTPS URI for its upstream — it can't hold ytmusic://.
      String[] cachedUrl = songUrlCache.get(videoId);
      if (cachedUrl != null && System.currentTimeMillis() < Long.parseLong(cachedUrl[1])) {
        Log.d("MusicService", "RESOLVER: url cache hit for " + videoId
                + " pos=" + dataSpec.position);
        Map<String, String> headers = new HashMap<>(dataSpec.httpRequestHeaders);
        if (cachedUrl.length > 2 && cachedUrl[2] != null && !cachedUrl[2].isEmpty()) {
          headers.put("User-Agent", cachedUrl[2]);
        }
        // No subrange here — dataSpec.position is already correct.
        // CacheDataSource will serve from cache where available and
        // use the real URI for any upstream gap-fills.
        return dataSpec.buildUpon()
                .setUri(Uri.parse(cachedUrl[0]))
                .setKey(videoId)
                .setHttpRequestHeaders(headers)
                .build();
      }

      // ── 2. Fresh resolve from YouTube ─────────────────────────────────
      try {
        StreamData stream = YtMusicService.get().resolveStreamUrlBlocking(videoId);
        if (stream == null || stream.url() == null || stream.url().isEmpty())
          throw new IOException("No stream for " + videoId);

        Log.d("MusicService", "RESOLVER: fresh fetch for " + videoId);
        Log.d("STREAM", "mime=" + stream.mimeType());

        long expireMs = extractExpireMs(stream.url());
        String ua = stream.userAgent() != null ? stream.userAgent() : "";
        songUrlCache.put(videoId, new String[]{stream.url(), String.valueOf(expireMs), ua});

        Map<String, String> headers = new HashMap<>(dataSpec.httpRequestHeaders);
        if (!ua.isEmpty()) {
          headers.put("User-Agent", ua);
        }


        // subrange(uriPositionOffset, CHUNK_LENGTH) mirrors OuterTune exactly.
        return dataSpec.buildUpon()
                .setUri(Uri.parse(stream.url()))
                .setKey(videoId)
                .setHttpRequestHeaders(headers)
                .build();

      } catch (Exception e) {
        throw new IOException("Resolve failed for " + videoId + ": " + e.getMessage(), e);
      }
    });
  }

  @Override
  public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
    return mediaSession;
  }

  @OptIn(markerClass = UnstableApi.class)
  @Override
  public void onUpdateNotification(MediaSession session, boolean startInForeground) {
    try {
      super.onUpdateNotification(session, startInForeground);
    } catch (Exception e) {
      // Catching ForegroundServiceStartNotAllowedException on Android 12+
      Log.e("MusicService", "Failed to start foreground service: " + e.getMessage());
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    try {
      return super.onStartCommand(intent, flags, startId);
    } catch (Exception e) {
      Log.e("MusicService", "Error in onStartCommand", e);
      return START_NOT_STICKY;
    }
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
    crossfadeHandler.removeCallbacks(crossfadeRunnable);
    saveQueueState();
    mediaSession.release();
    player.release();
    crossfadePlayer.release();
    super.onDestroy();
  }


  private void saveQueueState() {
    if (player == null || player.getMediaItemCount() == 0) return;
    if (QueueManager.get().isSmallWindow()) {
      Log.d("MusicService", "Skipping persist — small window queue");
      return;
    }
    new PlaybackSessionStore().save(this, player, QueueManager.get().isShuffleOn(), player.getRepeatMode());
  }
}