package me.ash.resonance.yt;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager;
import org.schabi.newpipe.extractor.services.youtube.YoutubeService;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

@UnstableApi
public class YtMusicService {

  // ── Innertube constants ───────────────────────────────────────────────────
  private static final String TAG = "YtMusicService";
  // OuterTune always hits the YT Music subdomain, not www.youtube.com
// Near the top where INNERTUBE_BASE_URL is defined
  private static final String INNERTUBE_BASE_URL = "https://music.youtube.com/youtubei/v1/";
  private static final String INNERTUBE_BASE_URL_YT = "https://www.youtube.com/youtubei/v1/";
  // OuterTune's User-Agent for web-based clients (latest Firefox ESR)
  private static final String USER_AGENT_WEB =
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
  // VisionOS client UA — used for both the player request and (must be matched)
  // the media/CDN request that actually streams the audio bytes.
  private static final String USER_AGENT_VISIONOS =
          "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                  + "(KHTML, like Gecko) Version/18.0 Safari/605.1.15";
  private static final String USER_AGENT_IOS =
          "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)";
  private static final String USER_AGENT_ANDROID_VR =
          "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; "
                  + "Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)";
  private static final long VISITOR_DATA_TTL_MS = 60 * 60 * 1000L; // 1 hour
  private static final long SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L; // 10 minutes
  // ── Singleton ─────────────────────────────────────────────────────────────
  // Stream URL TTL — YouTube signed URLs are valid ~6 hours; refresh after 5h55m
  private static YtMusicService instance;
  private static SimpleCache playerCache;
  // In-memory URL cache keyed by videoId.
  public final Map<String, CachedStream> streamCache = new HashMap<>();
  // In-memory search result cache keyed by "query::filterType".
  // Saves a round-trip when the user switches chips back to a prior filter,
  // or when RadioEngine/SuggestSongs repeats a common seed query.
  private final Map<String, CachedSearchResults> searchCache = new HashMap<>();
  // Same idea, but for the MUSIC_ALBUMS / MUSIC_ARTISTS filters, which return
  // PlaylistInfoItem / ChannelInfoItem rather than StreamInfoItem, so they
  // can't share searchCache's List<YtTrack> shape.
  private final Map<String, CachedAlbumResults> albumSearchCache = new HashMap<>();
  private final Map<String, CachedArtistResults> artistSearchCache = new HashMap<>();
  private final ExecutorService executor = Executors.newFixedThreadPool(6);
  private final Context context; // add field
  private final Object[] resolveLocks = new Object[32];
  public volatile String activeStreamUserAgent =
          "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)";
  public OkHttpClient http;
  private long visitorDataFetchedAt = 0;
  private volatile String visitorData = null;

  {
    for (int i = 0; i < 32; i++) resolveLocks[i] = new Object();
  }

  private YtMusicService(Context context) {
    this.context = context;
    this.http = buildHttpClient(context);
    ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    cm.registerNetworkCallback(
            new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .build(),
            new ConnectivityManager.NetworkCallback() {
              @Override
              public void onAvailable(Network network) {
                Log.d(TAG, "VPN connected — rebuilding HTTP client");
                rebuildHttpClient(context, network);
              }

              @Override
              public void onLost(Network network) {
                Log.d(TAG, "VPN lost — rebuilding HTTP client without VPN binding");
                rebuildHttpClient(context, null);
              }
            }
    );


    NewPipe.init(new OkHttpDownloader(http));
  }

  private static long extractExpireMs(String url) {
    try {
      String expire = android.net.Uri.parse(url).getQueryParameter("expire");
      if (expire != null) {
        return Long.parseLong(expire) * 1000L; // expire is unix seconds
      }
    } catch (Exception ignored) {
    }
    // fallback: 5 hours 55 minutes
    return System.currentTimeMillis() + (5 * 60 * 60 * 1000L) - (5 * 60 * 1000L);
  }

  private static OkHttpClient buildHttpClient(Context context) {
    OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(15, SECONDS)
            .readTimeout(20, SECONDS);

    android.net.Network vpn = getVpnNetwork(context); // or capture from loop
    if (vpn != null) {
      builder.socketFactory(vpn.getSocketFactory());
      builder.dns(hostname -> {
        try {
          InetAddress[] addrs = vpn.getAllByName(hostname);
          return java.util.Arrays.asList(addrs);
        } catch (Exception e) {
          // Fallback to system DNS only if VPN DNS fails completely
          return okhttp3.Dns.SYSTEM.lookup(hostname);
        }
      });
    }
    return builder.build();
  }

  public static YtMusicService get() {
    if (instance == null)
      throw new IllegalStateException("Call YtMusicService.init(context) first");
    return instance;
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /**
   * Must be called once in Application.onCreate() before anything else.
   */
  public static void init(Context context) {
    if (instance != null) return;
    instance = new YtMusicService(context.getApplicationContext());
  }

  // ── Stream resolution ─────────────────────────────────────────────────────

  /**
   * Pulls the 11-char video ID from a YouTube watch URL or youtu.be short URL.
   */
  public static String extractVideoId(String url) {
    if (url == null) return "";
    int v = url.indexOf("v=");
    if (v != -1) {
      String id = url.substring(v + 2);
      int amp = id.indexOf('&');
      return amp == -1 ? id : id.substring(0, amp);
    }
    int slash = url.lastIndexOf('/');
    if (slash != -1 && slash < url.length() - 1) {
      return url.substring(slash + 1);
    }
    return url;
  }

  private static android.net.Network getVpnNetwork(Context context) {
    android.net.ConnectivityManager cm =
            (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    if (cm == null) return null;
    for (android.net.Network net : cm.getAllNetworks()) {
      android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
      if (caps != null && caps.hasTransport(
              android.net.NetworkCapabilities.TRANSPORT_VPN)) {
        return net;
      }
    }
    return null;
  }

  /**
   * Minimal query-string parser for signatureCipher fields.
   */
  private static Map<String, String> parseQueryString(String query) {
    Map<String, String> map = new HashMap<>();
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq == -1) continue;
      try {
        String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
        String val = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        map.put(key, val);
      } catch (Exception ignored) {
      }
    }
    return map;
  }

  /**
   * Extracts the value of the {@code n} query parameter from a URL, or null if absent.
   */
  private static String extractNParam(String url) {
    try {
      android.net.Uri uri = android.net.Uri.parse(url);
      return uri.getQueryParameter("n");
    } catch (Exception e) {
      return null;
    }
  }

  // ── Search ────────────────────────────────────────────────────────────────

  @OptIn(markerClass = UnstableApi.class)
  public static SimpleCache getPlayerCache(Context context) {
    if (playerCache == null) {
      File cacheDir = new File(context.getCacheDir(), "exoplayer");
      playerCache = new SimpleCache(
              cacheDir,
              new LeastRecentlyUsedCacheEvictor(50 * 1024 * 1024L), // 50 MB
              new StandaloneDatabaseProvider(context)
      );
    }
    return playerCache;
  }

  private synchronized void rebuildHttpClient(Context context, @Nullable Network vpn) {
    OkHttpClient.Builder b = new OkHttpClient.Builder()
            .connectTimeout(15, SECONDS)
            .readTimeout(20, SECONDS);
    if (vpn != null) {
      b.socketFactory(vpn.getSocketFactory());
      b.dns(hostname -> {
        try {
          InetAddress[] addrs = vpn.getAllByName(hostname);
          return java.util.Arrays.asList(addrs);
        } catch (Exception e) {
          // Fallback to system DNS only if VPN DNS fails completely
          return okhttp3.Dns.SYSTEM.lookup(hostname);
        }
      });
    }
    // Replace the field — existing calls in flight on the old client
    // will complete normally; new calls pick up the new client
    this.http = b.build();
    NewPipe.init(new OkHttpDownloader(this.http)); // re-init NewPipe downloader too
  }
  // ── Track metadata ────────────────────────────────────────────────────────

  /**
   * Async variant — callbacks delivered on a background thread.
   * Used by {@link YtDownloadManager}.
   */
  public void resolveStreamUrl(String videoId, StreamCallback callback) {
    executor.submit(() -> {
      try {
        StreamData stream = resolveStreamUrlBlocking(videoId);
        if (stream != null) {
          callback.onStream(stream);
        } else {
          callback.onError(new IOException("No audio stream found for " + videoId));
        }
      } catch (Exception e) {
        Log.e(TAG, "resolveStreamUrl failed for " + videoId, e);
        callback.onError(e);
      }
    });
  }

  // ── Related tracks ────────────────────────────────────────────────────────

  public StreamData resolveStreamUrlBlocking(String videoId) throws Exception {
    // Per-videoId lock so concurrent ExoPlayer open() calls don't
    // each trigger a full resolve — only the first one fetches,
    // the rest wait and get the result from a very short-lived cache
    Object lock = resolveLocks[Math.abs(videoId.hashCode()) % resolveLocks.length];

    synchronized (lock) {
      // Short-lived cache check inside the lock
      synchronized (streamCache) {
        CachedStream cached = streamCache.get(videoId);
        if (cached != null && !cached.isExpired()) {
          Log.d(TAG, "Stream cache HIT for " + videoId);
          return new StreamData(cached.url, cached.mimeType, cached.userAgent);
        }
      }

      // Resolve fresh
      Log.d(TAG, "Stream cache MISS for " + videoId + " — fetching");
      StreamData stream = fetchBestAudioStream(videoId);

      if (stream != null) {
        synchronized (streamCache) {
          streamCache.put(videoId, new CachedStream(stream.url(), stream.mimeType(), stream.userAgent()));
        }
        activeStreamUserAgent = stream.userAgent();
        Log.d(TAG, "FINAL URL for " + videoId + ": " + stream.url());
        Log.d(TAG, "FINAL UA for " + videoId + ": " + stream.userAgent());
        return stream;
      }
      return null;
    }
  }

  // ── Innertube player — core stream resolution ─────────────────────────────

  public void evictStreamCache(String videoId) {
    synchronized (streamCache) {
      streamCache.remove(videoId);
    }
    Log.d(TAG, "Stream cache evicted for " + videoId);
  }

  // ── IOS client ────────────────────────────────────────────────────────────

  /**
   * Search YouTube for tracks matching {@code query}.
   *
   * @param filterType one of {@link YoutubeSearchQueryHandlerFactory#MUSIC_SONGS},
   *                   {@link YoutubeSearchQueryHandlerFactory#MUSIC_VIDEOS}, or
   *                   {@link YoutubeSearchQueryHandlerFactory#VIDEOS}.
   *                   Defaults to {@code MUSIC_SONGS} if null or unrecognised.
   */
  public void search(String query, String filterType, SearchCallback callback) {
    // Normalise filter so the cache key is always consistent
    final String resolvedFilter;
    if (YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS.equals(filterType)
            || YoutubeSearchQueryHandlerFactory.VIDEOS.equals(filterType)) {
      resolvedFilter = filterType;
    } else {
      resolvedFilter = YoutubeSearchQueryHandlerFactory.MUSIC_SONGS;
    }

    final String cacheKey = query + "::" + resolvedFilter;

    // Check cache before hitting the network
    synchronized (searchCache) {
      CachedSearchResults cached = searchCache.get(cacheKey);
      if (cached != null && !cached.isExpired()) {
        Log.d(TAG, "Search cache HIT: " + cacheKey);
        callback.onResults(new ArrayList<>(cached.tracks)); // defensive copy
        return;
      }
    }

    executor.submit(() -> {
      try {
        Log.d(TAG, "Search cache MISS: " + cacheKey);
        YoutubeService service = (YoutubeService)
                NewPipe.getService(ServiceList.YouTube.getServiceId());

        SearchExtractor extractor = service.getSearchExtractor(
                query,
                Collections.singletonList(resolvedFilter),
                ""
        );
        extractor.fetchPage();

        ListExtractor.InfoItemsPage<InfoItem> page = extractor.getInitialPage();
        List<YtTrack> tracks = new ArrayList<>();

        for (InfoItem item : page.getItems()) {
          if (item instanceof StreamInfoItem si) {
            String thumb = si.getThumbnails().isEmpty() ? null
                    : si.getThumbnails().get(si.getThumbnails().size() - 1).getUrl();
            String vid = extractVideoId(si.getUrl());
            tracks.add(new YtTrack(vid, si.getName(), si.getUploaderName(),
                    null, thumb, si.getDuration(), si.getUrl()));
          }
        }

        // Store in cache before delivering results
        synchronized (searchCache) {
          searchCache.put(cacheKey, new CachedSearchResults(tracks));
        }

        callback.onResults(tracks);

      } catch (Exception e) {
        Log.e(TAG, "search failed", e);
        callback.onError(e);
      }
    });
  }

  // Evicts all search cache entries for a given query (all filter variants).
  // Call this if you ever need to force-refresh results (not currently needed
  // but useful if you add a pull-to-refresh later).
  public void evictSearchCache(String query) {
    synchronized (searchCache) {
      searchCache.entrySet().removeIf(e -> e.getKey().startsWith(query + "::"));
    }
    Log.d(TAG, "Search cache evicted for query: " + query);
  }

  // Returns true if a fresh (non-expired) cache entry exists for this query+filter pair.
  // Used by SearchActivity to suppress the progress spinner on instant cache hits.
  public boolean isSearchCached(String query, String filterType) {
    final String resolvedFilter;
    if (YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS.equals(filterType)
            || YoutubeSearchQueryHandlerFactory.VIDEOS.equals(filterType)) {
      resolvedFilter = filterType;
    } else {
      resolvedFilter = YoutubeSearchQueryHandlerFactory.MUSIC_SONGS;
    }
    synchronized (searchCache) {
      CachedSearchResults cached = searchCache.get(query + "::" + resolvedFilter);
      return cached != null && !cached.isExpired();
    }
  }


  /**
   * Search YouTube Music for albums matching {@code query}.
   *
   * <p>Uses the {@link YoutubeSearchQueryHandlerFactory#MUSIC_ALBUMS} filter, which
   * returns {@link PlaylistInfoItem}s (each album is modelled as a playlist) rather
   * than the {@link StreamInfoItem}s that {@link #search} deals with.
   */
  public void searchAlbums(String query, AlbumCallback callback) {
    final String cacheKey = query + "::" + YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS;

    synchronized (albumSearchCache) {
      CachedAlbumResults cached = albumSearchCache.get(cacheKey);
      if (cached != null && !cached.isExpired()) {
        Log.d(TAG, "Album search cache HIT: " + cacheKey);
        callback.onResults(new ArrayList<>(cached.albums));
        return;
      }
    }

    executor.submit(() -> {
      try {
        Log.d(TAG, "Album search cache MISS: " + cacheKey);
        YoutubeService service = (YoutubeService)
                NewPipe.getService(ServiceList.YouTube.getServiceId());

        SearchExtractor extractor = service.getSearchExtractor(
                query,
                Collections.singletonList(YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS),
                ""
        );
        extractor.fetchPage();

        ListExtractor.InfoItemsPage<InfoItem> page = extractor.getInitialPage();
        List<YtAlbum> albums = new ArrayList<>();

        for (InfoItem item : page.getItems()) {
          if (item instanceof PlaylistInfoItem pi) {
            String thumb = pi.getThumbnails().isEmpty() ? null
                    : pi.getThumbnails().get(pi.getThumbnails().size() - 1).getUrl();
            albums.add(new YtAlbum(
                    pi.getUrl(),
                    pi.getName(),
                    cleanArtist(pi.getUploaderName()),
                    thumb,
                    pi.getUrl()
            ));
          }
        }

        synchronized (albumSearchCache) {
          albumSearchCache.put(cacheKey, new CachedAlbumResults(albums));
        }

        callback.onResults(albums);

      } catch (Exception e) {
        Log.e(TAG, "searchAlbums failed", e);
        callback.onError(e);
      }
    });
  }

  /**
   * Search YouTube Music for artists matching {@code query}.
   *
   * <p>Uses the {@link YoutubeSearchQueryHandlerFactory#MUSIC_ARTISTS} filter, which
   * returns {@link ChannelInfoItem}s rather than the {@link StreamInfoItem}s that
   * {@link #search} deals with.
   */
  public void searchArtists(String query, ArtistCallback callback) {
    final String cacheKey = query + "::" + YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS;

    synchronized (artistSearchCache) {
      CachedArtistResults cached = artistSearchCache.get(cacheKey);
      if (cached != null && !cached.isExpired()) {
        Log.d(TAG, "Artist search cache HIT: " + cacheKey);
        callback.onResults(new ArrayList<>(cached.artists));
        return;
      }
    }

    executor.submit(() -> {
      try {
        Log.d(TAG, "Artist search cache MISS: " + cacheKey);
        YoutubeService service = (YoutubeService)
                NewPipe.getService(ServiceList.YouTube.getServiceId());

        SearchExtractor extractor = service.getSearchExtractor(
                query,
                Collections.singletonList(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS),
                ""
        );
        extractor.fetchPage();

        ListExtractor.InfoItemsPage<InfoItem> page = extractor.getInitialPage();
        List<YtArtist> artists = new ArrayList<>();

        for (InfoItem item : page.getItems()) {
          if (item instanceof ChannelInfoItem ci) {
            String thumb = ci.getThumbnails().isEmpty() ? null
                    : ci.getThumbnails().get(ci.getThumbnails().size() - 1).getUrl();
            artists.add(new YtArtist(
                    ci.getUrl(),
                    cleanArtist(ci.getName()),
                    thumb,
                    ci.getUrl()
            ));
          }
        }

        synchronized (artistSearchCache) {
          artistSearchCache.put(cacheKey, new CachedArtistResults(artists));
        }

        callback.onResults(artists);

      } catch (Exception e) {
        Log.e(TAG, "searchArtists failed", e);
        callback.onError(e);
      }
    });
  }

  // Evicts all cached album/artist results for a given query — same rationale
  // as evictSearchCache, kept separate since they live in their own maps.
  public void evictAlbumSearchCache(String query) {
    synchronized (albumSearchCache) {
      albumSearchCache.remove(query + "::" + YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS);
    }
  }

  public void evictArtistSearchCache(String query) {
    synchronized (artistSearchCache) {
      artistSearchCache.remove(query + "::" + YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS);
    }
  }

  // ── WEB_REMIX client ──────────────────────────────────────────────────────

  /**
   * Fetches metadata for a single video ID via NewPipe.
   * Used by OuterTuneImporter to enrich exported files.
   *
   * <p>Private/age-restricted videos will fail here with an exception from
   * NewPipe — the caller (OuterTuneImporter) already handles this gracefully
   * via {@code song.metadataError}.
   */
  public void fetchTrack(String videoId, TrackCallback callback) {
    executor.submit(() -> {
      try {
        String url = "https://www.youtube.com/watch?v=" + videoId;
        StreamInfo info = StreamInfo.getInfo(NewPipe.getService(0), url);

        String thumb = info.getThumbnails().isEmpty() ? null
                : info.getThumbnails().get(info.getThumbnails().size() - 1).getUrl();

        String artist = cleanArtist(info.getUploaderName());

        YtTrack track = new YtTrack(videoId, info.getName(), artist,
                null, thumb, info.getDuration(), url);
        callback.onTrack(track);

      } catch (Exception e) {
        // Likely private/age-restricted — OuterTuneImporter logs and skips
        Log.w(TAG, "fetchTrack failed for " + videoId + " (private/restricted?): " + e.getMessage());
        callback.onError(e);
      }
    });
  }

  /**
   * Blocking variant — call from your own thread pool to avoid double-dispatch.
   */
  public YtTrack fetchTrackBlocking(String videoId) throws Exception {
    String url = "https://www.youtube.com/watch?v=" + videoId;
    StreamInfo info = StreamInfo.getInfo(NewPipe.getService(0), url);

    String thumb = info.getThumbnails().isEmpty() ? null
            : info.getThumbnails().get(info.getThumbnails().size() - 1).getUrl();

    return new YtTrack(videoId, info.getName(), cleanArtist(info.getUploaderName()),
            null, thumb, info.getDuration(), url);
  }

  // ── Shared player request executor ────────────────────────────────────────

  public void fetchRelatedTracks(String videoId, SearchCallback callback) {
    executor.submit(() -> {
      try {
        StreamInfo info = StreamInfo.getInfo(
                ServiceList.YouTube,
                "https://www.youtube.com/watch?v=" + videoId
        );

        List<YtTrack> tracks = new ArrayList<>();
        for (InfoItem item : info.getRelatedItems()) {
          if (!(item instanceof StreamInfoItem si)) continue;
          String thumb = si.getThumbnails().isEmpty() ? null
                  : si.getThumbnails().get(si.getThumbnails().size() - 1).getUrl();
          tracks.add(new YtTrack(
                  extractVideoId(si.getUrl()),
                  si.getName(),
                  cleanArtist(si.getUploaderName()),
                  null, thumb, si.getDuration(), si.getUrl()
          ));
        }
        callback.onResults(tracks);

      } catch (Exception e) {
        Log.e(TAG, "fetchRelatedTracks failed for " + videoId, e);
        callback.onError(e);
      }
    });
  }

  public StreamData fetchBestAudioStream(String videoId) throws Exception {
    StreamData result;

    // VISIONOS is currently the least-flagged client — not yet subject to the
    // throttling/expiry YouTube has been rolling out against IOS and ANDROID_VR.
    // Try it first.
    Log.d(TAG, "Resolving " + videoId + " — trying VISIONOS");
    result = tryVisionOs(videoId);
    if (result != null) return result;

    // IOS now degrades ~30-60s into playback (likely n-param/URL-expiry drift
    // specific to that client) — kept as a fallback, not the primary path.
    Log.d(TAG, "VISIONOS failed for " + videoId + " — trying IOS");
    result = tryIos(videoId);
    if (result != null) return result;

    Log.d(TAG, "IOS failed for " + videoId + " — trying WEB_REMIX");
    result = tryWebRemix(videoId);
    if (result != null) return result;

    Log.d(TAG, "WEB_REMIX failed for " + videoId + " — trying ANDROID_VR");
    result = tryAndroidVr(videoId);
    if (result != null) return result;

    Log.e(TAG, "All clients failed for " + videoId);
    return null;
  }

  /**
   * Uses the IOS client (clientId=5, version=20.10.4).
   */
  private StreamData tryIos(String videoId) throws Exception {
    org.json.JSONObject clientCtx = new org.json.JSONObject()
            .put("clientName", "IOS")
            .put("clientVersion", "20.10.4")
            .put("osName", "iPhone")
            .put("osVersion", "18.3.2.22D82")
            .put("deviceMake", "Apple")
            .put("deviceModel", "iPhone16,2")
            .put("hl", "en")
            .put("gl", "US");

    org.json.JSONObject body = new org.json.JSONObject()
            .put("videoId", videoId)
            .put("context", new org.json.JSONObject().put("client", clientCtx))
            .put("contentCheckOk", true)
            .put("racyCheckOk", true);

    okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(
            body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            okhttp3.MediaType.parse("application/json; charset=utf-8")
    );

    okhttp3.Request request = new okhttp3.Request.Builder()
            .url(INNERTUBE_BASE_URL_YT + "player?prettyPrint=false")
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT_IOS)
            .header("X-YouTube-Client-Name", "5")
            .header("X-YouTube-Client-Version", "20.10.4")
            .header("X-Goog-Api-Format-Version", "1")
            .build();

    // IOS returns plain url fields — needsDeobfuscation=false
    return executePlayerRequest(request, videoId, "IOS", false);
  }

  /**
   * Uses the VISIONOS client (clientId=101, version=0.1).
   *
   * <p>Newer, less-flagged client. Like IOS, it returns plain {@code url}
   * fields — no signatureCipher deobfuscation needed.
   */
  private StreamData tryVisionOs(String videoId) throws Exception {
    // Attach visitorData the same way ANDROID_VR does — several Innertube
    // clients treat its absence as a signal to withhold streamingData
    // (returned as playabilityStatus=UNPLAYABLE with no other explanation).
    String vd = getOrFetchVisitorData();

    org.json.JSONObject clientCtx = new org.json.JSONObject()
            .put("clientName", "VISIONOS")
            .put("clientVersion", "0.1")
            .put("osName", "visionOS")
            .put("osVersion", "1.3.21O771")
            .put("deviceMake", "Apple")
            .put("deviceModel", "RealityDevice14,1")
            .put("hl", "en")
            .put("gl", "US");
    if (vd != null) clientCtx.put("visitorData", vd);

    org.json.JSONObject body = new org.json.JSONObject()
            .put("videoId", videoId)
            .put("context", new org.json.JSONObject().put("client", clientCtx))
            .put("contentCheckOk", true)
            .put("racyCheckOk", true);

    okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(
            body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            okhttp3.MediaType.parse("application/json; charset=utf-8")
    );

    okhttp3.Request request = new okhttp3.Request.Builder()
            .url(INNERTUBE_BASE_URL_YT + "player?prettyPrint=false")
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT_VISIONOS)
            .header("X-YouTube-Client-Name", "101")
            .header("X-YouTube-Client-Version", "0.1")
            .header("X-Goog-Api-Format-Version", "1")
            .build();

    // VISIONOS returns plain url fields — needsDeobfuscation=false, same as IOS
    return executePlayerRequest(request, videoId, "VISIONOS", false);
  }

  private StreamData tryAndroidVr(String videoId) throws Exception {
    String vd = getOrFetchVisitorData();

    org.json.JSONObject clientCtx = new org.json.JSONObject()
            .put("clientName", "ANDROID_VR")
            .put("clientVersion", "1.61.48")
            .put("deviceMake", "Oculus")
            .put("deviceModel", "Quest 3")
            .put("osName", "Android")
            .put("osVersion", "12")
            .put("androidSdkVersion", 31)
            .put("hl", "en")
            .put("gl", "US");

    if (vd != null) clientCtx.put("visitorData", vd);

    org.json.JSONObject body = new org.json.JSONObject()
            .put("videoId", videoId)
            .put("context", new org.json.JSONObject().put("client", clientCtx))
            .put("contentCheckOk", true)
            .put("racyCheckOk", true);

    okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(
            body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            okhttp3.MediaType.parse("application/json; charset=utf-8")
    );

    okhttp3.Request request = new okhttp3.Request.Builder()
            .url(INNERTUBE_BASE_URL_YT + "player?prettyPrint=false")
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT_ANDROID_VR)
            .header("X-YouTube-Client-Name", "28")
            .header("X-YouTube-Client-Version", "1.61.48")
            .header("X-Goog-Api-Format-Version", "1")
            .build();

    return executePlayerRequest(request, videoId, "ANDROID_VR", false);
  }

  /**
   * Uses the WEB_REMIX client (clientId=67, clientName="WEB_REMIX").
   *
   * <p>Requires:
   * <ul>
   *   <li>A {@code signatureTimestamp} obtained from the JS player via
   *       {@link YoutubeJavaScriptPlayerManager#getSignatureTimestamp(String)}.
   *       This is the field OuterTune sends in {@code playbackContext}.</li>
   *   <li>A {@code visitorData} token fetched once from
   *       {@code music.youtube.com/sw.js_data}.</li>
   * </ul>
   *
   * <p>Because WEB_REMIX returns cipher-signed URLs, we also run them through
   * {@link YoutubeJavaScriptPlayerManager} to deobfuscate the signature and
   * strip the throttling parameter — exactly what OuterTune's NewPipe.kt does.
   */
  private StreamData tryWebRemix(String videoId) throws Exception {
    // 1. Get signatureTimestamp from JS player
    int signatureTimestamp;
    try {
      signatureTimestamp = YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId);
    } catch (Exception e) {
      Log.w(TAG, "WEB_REMIX: could not get signatureTimestamp for " + videoId + ": " + e.getMessage());
      return null;
    }

    // 2. Ensure we have visitorData (fetch once, reuse)
    String vd = getOrFetchVisitorData();

    // 3. Build context
    org.json.JSONObject clientCtx = new org.json.JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", "1.20250310.01.00")
            .put("hl", "en")
            .put("gl", "US");
    if (vd != null) clientCtx.put("visitorData", vd);

    // 4. playbackContext carries the signatureTimestamp
    org.json.JSONObject playbackContext = new org.json.JSONObject()
            .put("contentPlaybackContext", new org.json.JSONObject()
                    .put("signatureTimestamp", signatureTimestamp));

    org.json.JSONObject body = new org.json.JSONObject()
            .put("videoId", videoId)
            .put("context", new org.json.JSONObject().put("client", clientCtx))
            .put("playbackContext", playbackContext);

    okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(
            body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            okhttp3.MediaType.parse("application/json; charset=utf-8")
    );

    okhttp3.Request request = new okhttp3.Request.Builder()
            .url(INNERTUBE_BASE_URL + "player?prettyPrint=false")
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT_WEB)
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", "1.20250310.01.00")
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("Origin", "https://music.youtube.com")
            .build();

    // WEB_REMIX may return cipher-signed URLs — pass videoId for deobfuscation
    return executePlayerRequest(request, videoId, "WEB_REMIX", true);
  }

  public void fetchAlbumTracks(String browseId, SearchCallback callback) {
    executor.submit(() -> {
      try {
        YoutubeService service = (YoutubeService)
                NewPipe.getService(ServiceList.YouTube.getServiceId());

        // YT Music albums are playlists. NewPipe's PlaylistExtractor handles them perfectly.
        PlaylistExtractor extractor = service.getPlaylistExtractor(browseId);
        extractor.fetchPage();

        List<YtTrack> tracks = new ArrayList<>();
        ListExtractor.InfoItemsPage<StreamInfoItem> page = extractor.getInitialPage();

        for (StreamInfoItem si : page.getItems()) {
          String thumb = si.getThumbnails().isEmpty() ? null
                  : si.getThumbnails().get(si.getThumbnails().size() - 1).getUrl();
          String vid = extractVideoId(si.getUrl());
          tracks.add(new YtTrack(vid, si.getName(), cleanArtist(si.getUploaderName()),
                  null, thumb, si.getDuration(), si.getUrl()));
        }

        callback.onResults(tracks);

      } catch (Exception e) {
        Log.e(TAG, "fetchAlbumTracks failed for " + browseId, e);
        callback.onError(e);
      }
    });
  }

  // ── visitorData fetch (used by WEB_REMIX) ─────────────────────────────────

  /**
   * Executes a POST to the Innertube /player endpoint and extracts the best
   * audio-only stream.
   *
   * @param needsDeobfuscation if true, runs URLs through
   *                           {@link YoutubeJavaScriptPlayerManager} to handle signatureCipher and
   *                           n-parameter throttling (required for WEB_REMIX, not for IOS).
   */
  private StreamData executePlayerRequest(okhttp3.Request request, String videoId,
                                          String clientLabel, boolean needsDeobfuscation)
          throws Exception {

    try (okhttp3.Response response = http.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        Log.w(TAG, clientLabel + " HTTP " + response.code() + " for " + videoId);
        return null;
      }

      org.json.JSONObject json = new org.json.JSONObject(response.body().string());

      // Check playability
      org.json.JSONObject status = json.optJSONObject("playabilityStatus");
      if (status != null) {
        String s = status.optString("status", "");
        if ("LOGIN_REQUIRED".equals(s) || "UNPLAYABLE".equals(s) || "ERROR".equals(s)) {
          String reason = status.optString("reason", "(no reason given)");
          Log.w(TAG, clientLabel + " got playabilityStatus=" + s
                  + " reason=\"" + reason + "\" for " + videoId);
          return null;
        }
      }

      org.json.JSONObject streamingData = json.optJSONObject("streamingData");
      if (streamingData == null) {
        Log.w(TAG, clientLabel + " no streamingData for " + videoId);
        return null;
      }

      // 1. Best audio-only adaptive format.
      org.json.JSONArray formats = streamingData.optJSONArray("adaptiveFormats");
      if (formats == null) {
        Log.w(TAG, clientLabel + " no adaptiveFormats for " + videoId);
        return null;
      }

      org.json.JSONObject winnerFmt = null;
      long bestScore = -1;
      int winnerBitrate = -1;
      String winnerMime = null;
      String winnerUserAgent = null;

      boolean isIos = clientLabel.equals("IOS");
      boolean isVisionOs = clientLabel.equals("VISIONOS");
      boolean isAndroidVr = clientLabel.equals("ANDROID_VR");

      for (int i = 0; i < formats.length(); i++) {
        org.json.JSONObject fmt = formats.getJSONObject(i);
        String mimeType = fmt.optString("mimeType", "");

        boolean isAudio = !fmt.has("width")
                && (mimeType.startsWith("audio/mp4") || mimeType.startsWith("audio/webm"))
                && (fmt.has("url") || fmt.has("signatureCipher"));

        if (!isAudio) continue;

        int bitrate = fmt.optInt("bitrate", 0);
        boolean isWebm = mimeType.startsWith("audio/webm");

        // The IOS and VISIONOS client UAs are tied to Apple devices which never
        // serve webm, so YouTube's CDN returns 403 if you request a webm URL
        // with one of those UAs.
        if ((isIos || isVisionOs) && isWebm) continue;

        // Optimisation 2: Prefer Opus/WebM streams over AAC/MP4
        // Add a tiebreaker bonus so Opus wins when bitrates are otherwise close.
        long score = bitrate + (isWebm ? 10240 : 0);

        if (score > bestScore) {
          bestScore = score;
          winnerBitrate = bitrate;
          winnerFmt = fmt;
          winnerMime = isWebm ? "audio/webm" : "audio/mp4";
          // Each client's media/CDN request must use that exact client's UA —
          // a mismatch between the player-resolve call's UA and the actual
          // stream request's UA is what causes CDN 403s. No catch-all "else":
          // every client label handled here must be named explicitly, so a
          // newly added client can't silently inherit the wrong UA.
          if (isIos) {
            winnerUserAgent = USER_AGENT_IOS;
          } else if (isVisionOs) {
            winnerUserAgent = USER_AGENT_VISIONOS;
          } else if (isAndroidVr) {
            winnerUserAgent = USER_AGENT_ANDROID_VR;
          } else {
            // WEB_REMIX (and any other web-based client) uses the web UA.
            winnerUserAgent = USER_AGENT_WEB;
          }
        }
      }

      if (winnerFmt == null) {
        // 2. HLS manifest fallback — ExoPlayer handles natively, no deobfuscation needed
        String hlsUrl = streamingData.optString("hlsManifestUrl", null);
        if (hlsUrl != null && !hlsUrl.isEmpty()) {
          Log.d(TAG, clientLabel + " → HLS fallback for " + videoId);
          return new StreamData(hlsUrl, "application/x-mpegURL", "");
        }
        Log.w(TAG, clientLabel + " found no usable audio format for " + videoId);
        return null;
      }

      String resolvedUrl = resolveFormatUrl(winnerFmt, videoId, needsDeobfuscation);
      if (resolvedUrl == null) {
        Log.w(TAG, clientLabel + " URL resolution failed for winning format, videoId=" + videoId);
        return null;
      }

      Log.d(TAG, clientLabel + " -> " + winnerMime + " bitrate=" + winnerBitrate + " for " + videoId);
      return new StreamData(resolvedUrl, winnerMime, winnerUserAgent);
    }
  }

  // ── Utilities ─────────────────────────────────────────────────────────────

  /**
   * Resolves the playback URL from a format object.
   *
   * <p>Two separate deobfuscation steps, each independently controlled:
   * <ol>
   *   <li><b>Signature cipher</b> ({@code needsSigDeobfuscation}) — only needed for
   *       WEB_REMIX which returns {@code signatureCipher} fields instead of plain
   *       {@code url} fields. IOS always returns plain URLs so this is false for IOS.</li>
   *   <li><b>n-parameter throttling</b> — always run for every client, including IOS.
   *       YouTube embeds a throttling token in the {@code n} query parameter of every
   *       stream URL. If it is not deobfuscated the CDN returns 403. If NewPipe\'s JS
   *       parser is currently broken, we log a warning and return the un-deobfuscated
   *       URL rather than discarding it — a throttled URL may still work and is always
   *       better than silence.</li>
   * </ol>
   */
  private String resolveFormatUrl(org.json.JSONObject fmt, String videoId,
                                  boolean needsSigDeobfuscation) {
    try {
      String url;

      if (fmt.has("url")) {
        url = fmt.getString("url");
      } else if (fmt.has("signatureCipher")) {
        // Parse the cipher query string: s=<sig>&sp=<param>&url=<url>
        String cipher = fmt.getString("signatureCipher");
        Map<String, String> params = parseQueryString(cipher);

        String obfuscatedSig = params.get("s");
        String sigParam = params.get("sp");
        String rawUrl = params.get("url");

        if (obfuscatedSig == null || sigParam == null || rawUrl == null) {
          Log.w(TAG, "Could not parse signatureCipher for " + videoId);
          return null;
        }

        if (needsSigDeobfuscation) {
          String deobfuscated = YoutubeJavaScriptPlayerManager
                  .deobfuscateSignature(videoId, obfuscatedSig);
          url = rawUrl + "&" + sigParam + "=" +
                  java.net.URLEncoder.encode(deobfuscated, StandardCharsets.UTF_8);
        } else {
          // Shouldn\'t happen for IOS, but handle gracefully
          url = rawUrl + "&" + sigParam + "=" +
                  java.net.URLEncoder.encode(obfuscatedSig, StandardCharsets.UTF_8);
        }

      } else {
        return null;
      }

      try {
        Log.d(TAG, "Pre-deobfuscation URL for " + videoId + ": " + url);
        String beforeN = extractNParam(url);
        url = YoutubeJavaScriptPlayerManager
                .getUrlWithThrottlingParameterDeobfuscated(videoId, url);
        String afterN = extractNParam(url);
        Log.d(TAG, "Post-deobfuscation URL for " + videoId + ": " + url);
        if (beforeN == null) {
          Log.d(TAG, "No n-param in URL for " + videoId + " (IOS client — expected)");
        } else if (java.util.Objects.equals(beforeN, afterN)) {
          Log.e(TAG, "N-PARAM NOT DEOBFUSCATED for " + videoId + " — this will 403! n=" + beforeN);
        } else {
          Log.d(TAG, "n-param OK: " + beforeN + " → " + afterN);
        }
      } catch (Exception e) {
        Log.w(TAG, "n-param deobfuscation failed for " + videoId
                + " — discarding URL to avoid throttled 403");
        return null; // Don't serve a URL that will 403 after 30s
      }

      // Optimisation 1: Bypass YouTube CDN throttling with a range parameter
      long contentLength = fmt.optLong("contentLength", 10000000L);
      if (url != null && !url.contains("&range=")) {
        url += "&range=0-" + contentLength;
      }

      return url;

    } catch (Exception e) {
      Log.w(TAG, "resolveFormatUrl failed for " + videoId + ": " + e.getMessage());
      return null;
    }
  }

  /**
   * Returns a cached visitorData token, fetching it from
   * {@code music.youtube.com/sw.js_data} if not yet obtained.
   *
   * <p>OuterTune calls {@code getSwJsData()} once and stores the result.
   * The token is embedded in the sw.js_data response as the first element
   * of a nested array: {@code data[0][2]}.
   *
   * <p>Returns null if the fetch fails — WEB_REMIX will still attempt to
   * play without it (it just reduces the chance of success slightly).
   */
  private String getOrFetchVisitorData() {
    if (visitorData != null && System.currentTimeMillis() - visitorDataFetchedAt < VISITOR_DATA_TTL_MS) {
      return visitorData;
    }
    visitorData = null;

    try {
      okhttp3.Request request = new okhttp3.Request.Builder()
              .url("https://music.youtube.com/sw.js_data")
              .header("User-Agent", USER_AGENT_WEB)
              .header("Referer", "https://music.youtube.com/")
              .get()
              .build();

      try (okhttp3.Response response = http.newCall(request).execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          Log.w(TAG, "sw.js_data fetch failed: HTTP " + response.code());
          return null;
        }

        String body = response.body().string();

        // Strip the ")]}'\n" XSSI prefix — find the first '['
        int jsonStart = body.indexOf('[');
        if (jsonStart == -1) {
          Log.w(TAG, "sw.js_data: no JSON found");
          return null;
        }

        // OuterTune: jsonArray[0].jsonArray[2].jsonArray
        //            .first { it matches Regex("^Cg[t|s]") }
        // visitorData looks like "CgtXxxx..." or "CgsXxxx..."
        org.json.JSONArray outer = new org.json.JSONArray(body.substring(jsonStart));
        org.json.JSONArray level1 = outer.optJSONArray(0);
        if (level1 == null) return null;
        org.json.JSONArray level2 = level1.optJSONArray(2);
        if (level2 == null) return null;

        // Search through level2 for the visitorData token
        for (int i = 0; i < level2.length(); i++) {
          String candidate = level2.optString(i, null);
          if (candidate != null && (candidate.startsWith("Cgt") || candidate.startsWith("Cgs"))) {
            visitorData = candidate;
            visitorDataFetchedAt = System.currentTimeMillis();
            Log.d(TAG, "visitorData fetched: " + candidate.substring(0, Math.min(20, candidate.length())) + "…");
            return visitorData;
          }
        }

        Log.w(TAG, "sw.js_data: no visitorData token found in [0][2] array");
      }
    } catch (Exception e) {
      Log.w(TAG, "getOrFetchVisitorData failed: " + e.getMessage());
    }
    return null;
  }

  private String cleanArtist(String artist) {
    if (artist == null) return "";
    return artist.replace(" - Topic", "").trim();
  }

  // ── Callbacks ─────────────────────────────────────────────────────────────

  public interface SearchCallback {
    void onResults(List<YtTrack> tracks);

    void onError(Exception e);
  }

  public interface AlbumCallback {
    void onResults(List<YtAlbum> albums);

    void onError(Exception e);
  }

  public interface ArtistCallback {
    void onResults(List<YtArtist> artists);

    void onError(Exception e);
  }

  public interface StreamCallback {
    void onStream(StreamData stream);

    void onError(Exception e);
  }

  public interface TrackCallback {
    void onTrack(YtTrack track);

    void onError(Exception e);
  }

  // ── Internal cache entry ──────────────────────────────────────────────────

  public static class CachedStream {
    final String url;
    final String mimeType;
    final String userAgent;
    final long expiresAtMs;      // from &expire= param (~6 hours)
    final long issuedAtMs;       // System.currentTimeMillis() when URL was fetched

    public CachedStream(String url, String mimeType, String userAgent) {
      this.url = url;
      this.mimeType = mimeType;
      this.userAgent = userAgent;
      this.expiresAtMs = extractExpireMs(url);
      this.issuedAtMs = System.currentTimeMillis();
    }

    boolean isExpired() {
      return System.currentTimeMillis() > expiresAtMs - (5 * 60 * 1000L);
    }
  }

  // ── Search result cache entry ─────────────────────────────────────────────

  private static class CachedSearchResults {
    final List<YtTrack> tracks;
    final long fetchedAt;

    CachedSearchResults(List<YtTrack> tracks) {
      this.tracks = tracks;
      this.fetchedAt = System.currentTimeMillis();
    }

    boolean isExpired() {
      return System.currentTimeMillis() - fetchedAt > SEARCH_CACHE_TTL_MS;
    }
  }

  // ── Album search result ───────────────────────────────────────────────────
  private static class CachedAlbumResults {
    final List<YtAlbum> albums;
    final long fetchedAt;

    CachedAlbumResults(List<YtAlbum> albums) {
      this.albums = albums;
      this.fetchedAt = System.currentTimeMillis();
    }

    boolean isExpired() {
      return System.currentTimeMillis() - fetchedAt > SEARCH_CACHE_TTL_MS;
    }
  }

  // ── Artist search result ──────────────────────────────────────────────────
  private static class CachedArtistResults {
    final List<YtArtist> artists;
    final long fetchedAt;

    CachedArtistResults(List<YtArtist> artists) {
      this.artists = artists;
      this.fetchedAt = System.currentTimeMillis();
    }

    boolean isExpired() {
      return System.currentTimeMillis() - fetchedAt > SEARCH_CACHE_TTL_MS;
    }
  }

  // ── OkHttp-backed Downloader for NewPipe ──────────────────────────────────

  private static class OkHttpDownloader extends Downloader {

    private final OkHttpClient client;

    OkHttpDownloader(OkHttpClient client) {
      this.client = client;
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
      okhttp3.RequestBody body = null;
      if (!request.httpMethod().equals("GET") && !request.httpMethod().equals("HEAD")) {
        byte[] data = request.dataToSend();
        String bodyStr = data != null
                ? new String(data, java.nio.charset.StandardCharsets.UTF_8) : "";
        String contentType = "application/json";
        if (request.headers() != null && request.headers().containsKey("Content-Type")) {
          List<String> ct = request.headers().get("Content-Type");
          if (ct != null && !ct.isEmpty()) contentType = ct.get(0);
        }
        body = okhttp3.RequestBody.create(
                bodyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                okhttp3.MediaType.parse(contentType));
      }

      okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
              .url(request.url())
              .method(request.httpMethod(), body);

      if (request.headers() != null) {
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
          for (String value : entry.getValue()) {
            builder.addHeader(entry.getKey(), value);
          }
        }
      }

      if (request.httpMethod().equals("GET")) {
        builder.header("User-Agent", USER_AGENT_WEB);
        builder.header("Accept-Language", "en-US,en;q=0.9");
      }

      try (okhttp3.Response resp = client.newCall(builder.build()).execute()) {
        if (resp.code() == 429) throw new ReCaptchaException("Rate limited", request.url());
        String respBody = resp.body() != null ? resp.body().string() : "";
        Map<String, List<String>> headers = new HashMap<>();
        for (String name : resp.headers().names()) {
          headers.put(name, resp.headers(name));
        }
        return new Response(resp.code(), resp.message(), headers, respBody, request.url());
      }
    }
  }
}