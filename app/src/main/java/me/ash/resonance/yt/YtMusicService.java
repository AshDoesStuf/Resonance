package me.ash.resonance.yt;

import android.content.Context;
import android.util.Log;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.youtube.YoutubeService;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

public class YtMusicService {

  private static final String TAG = "YtMusicService";

  private static final long STREAM_URL_TTL_MS = 55 * 60 * 1000L;

  // ── Singleton ─────────────────────────────────────────────────────────────
  private static YtMusicService instance;
  private final Map<String, CachedStream> streamCache = new HashMap<>();
  private final ExecutorService executor = Executors.newCachedThreadPool();

  // ── Internals ─────────────────────────────────────────────────────────────
  private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build();
  /**
   * Cached visitorData token.  Fetched once per app session; refreshed if it
   * appears stale (causes a 403 on the player endpoint).
   */
  private final String cachedVisitorData = null;
  private final long visitorDataFetchedAt = 0L;

  private YtMusicService(Context context) {
    // NewPipe needs a Downloader. We wire it to our OkHttp instance.
    NewPipe.init(new OkHttpDownloader(http));
  }

  public static YtMusicService get() {
    if (instance == null)
      throw new IllegalStateException("Call YtMusicService.init(context) first");
    return instance;
  }

  /**
   * Must be called once before any searches/streams — typically in Application.onCreate().
   */
  public static void init(Context context) {
    if (instance != null) return;
    instance = new YtMusicService(context.getApplicationContext());
  }

  // ── Public data model ─────────────────────────────────────────────────────

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

  // ── Callbacks ─────────────────────────────────────────────────────────────

  /**
   * Search YouTube Music for songs matching {@code query}.
   * Uses the MUSIC_SONGS filter via NewPipe (search still works fine with NewPipe).
   * Callbacks are delivered on the background thread; post to main if needed.
   */
  public void search(String query, SearchCallback callback) {
    executor.submit(() -> {
      try {
        YoutubeService service = (YoutubeService) NewPipe.getService(ServiceList.YouTube.getServiceId());

        SearchExtractor extractor = service.getSearchExtractor(query, Collections.singletonList(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "");
        extractor.fetchPage();

        ListExtractor.InfoItemsPage<InfoItem> page = extractor.getInitialPage();
        List<YtTrack> tracks = new ArrayList<>();

        for (InfoItem item : page.getItems()) {
          if (item instanceof StreamInfoItem) {
            StreamInfoItem si = (StreamInfoItem) item;
            String thumb = si.getThumbnails().isEmpty() ? null : si.getThumbnails().get(si.getThumbnails().size() - 1).getUrl();
            String vid = extractVideoId(si.getUrl());
            tracks.add(new YtTrack(vid, si.getName(), si.getUploaderName(), null, thumb, si.getDuration(), si.getUrl()));
          }
        }
        callback.onResults(tracks);

      } catch (Exception e) {
        Log.e(TAG, "search failed", e);
        callback.onError(e);
      }
    });
  }

  /**
   * Resolves the best available audio stream URL for the given video ID via the
   * Innertube iOS client, which returns an {@code hlsManifestUrl} that ExoPlayer
   * can play directly with no special headers.
   *
   * <p><b>IMPORTANT:</b> URLs expire in ~6 hours. Always resolve fresh before playback;
   * never persist them.
   */
  public void resolveStreamUrl(String videoId, StreamCallback callback) {
    executor.submit(() -> {
      try {
        StreamData stream = fetchBestAudioStream(videoId);

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

  /**
   * Fetches full metadata for a single video ID via Innertube (NOT NewPipe).
   *
   * <p>This is used by {@link OuterTuneImporter} to enrich exported files whose
   * filenames embed a YT video ID.
   *
   * <p>Previously this used {@code StreamInfo.getInfo()} via NewPipe, which is broken
   * as of 2025 due to YouTube's SABR enforcement.  We now call the same Innertube
   * player endpoint we use for streaming — it returns all the metadata we need in
   * {@code videoDetails} without needing stream resolution.
   */
  public void fetchTrack(String videoId, TrackCallback callback) {
    executor.submit(() -> {
      try {
        String url = "https://www.youtube.com/watch?v=" + videoId;
        StreamInfo info = StreamInfo.getInfo(NewPipe.getService(0), url);

        String thumb = info.getThumbnails().isEmpty() ? null
                : info.getThumbnails().get(info.getThumbnails().size() - 1).getUrl();

        String rawArtist = info.getUploaderName();
        String artist = rawArtist != null ? rawArtist.replace(" - Topic", "").trim() : null;

        YtTrack track = new YtTrack(
                videoId,
                info.getName(),
                artist,
                null,
                thumb,
                info.getDuration(),
                url
        );
        callback.onTrack(track);
      } catch (Exception e) {
        callback.onError(e);
      }
    });
  }

  public void fetchRelatedTracks(
          String videoId,
          SearchCallback callback
  ) {

    executor.submit(() -> {

      try {

        StreamInfo info =
                StreamInfo.getInfo(
                        ServiceList.YouTube,
                        "https://www.youtube.com/watch?v="
                                + videoId
                );

        List<YtTrack> tracks =
                new ArrayList<>();

        for (InfoItem item :
                info.getRelatedItems()) {

          if (!(item instanceof StreamInfoItem))
            continue;

          StreamInfoItem si =
                  (StreamInfoItem) item;

          String thumb =
                  si.getThumbnails().isEmpty()
                          ? null
                          : si.getThumbnails()
                          .get(
                                  si.getThumbnails()
                                          .size() - 1
                          )
                          .getUrl();

          tracks.add(
                  new YtTrack(
                          extractVideoId(
                                  si.getUrl()
                          ),
                          si.getName(),
                          cleanArtist(
                                  si.getUploaderName()
                          ),
                          null,
                          thumb,
                          si.getDuration(),
                          si.getUrl()
                  )
          );
        }

        callback.onResults(tracks);

      } catch (Exception e) {

        callback.onError(e);

      }

    });
  }

  private String cleanArtist(
          String artist
  ) {

    if (artist == null)
      return "";

    return artist
            .replace(" - Topic", "")
            .trim();
  }

  // ── Search ────────────────────────────────────────────────────────────────

  private StreamData fetchBestAudioStream(String videoId) throws Exception {

    StreamInfo info = StreamInfo.getInfo(
            ServiceList.YouTube,
            "https://www.youtube.com/watch?v=" + videoId
    );

    List<AudioStream> audioStreams = info.getAudioStreams();

    if (audioStreams == null || audioStreams.isEmpty()) {
      return null;
    }

    AudioStream best = null;

    for (AudioStream stream : audioStreams) {

      if (stream.getUrl() == null) continue;

      if (best == null) {
        best = stream;
        continue;
      }

      if (stream.getBitrate() > best.getBitrate()) {
        best = stream;
      }
    }

    if (best == null) return null;

    return new StreamData(
            best.getContent(),
            best.getFormat().getMimeType()
    );
  }

  public interface SearchCallback {
    void onResults(List<YtTrack> tracks);

    void onError(Exception e);
  }

  public interface StreamCallback {
    void onStream(StreamData stream);

    void onError(Exception e);
  }

  // ── visitorData ───────────────────────────────────────────────────────────

  public interface TrackCallback {
    void onTrack(YtTrack track);

    void onError(Exception e);
  }

  private static class CachedStream {
    final String url;
    final long fetchedAt;

    CachedStream(String url) {
      this.url = url;
      this.fetchedAt = System.currentTimeMillis();
    }

    boolean isExpired() {
      return System.currentTimeMillis() - fetchedAt > STREAM_URL_TTL_MS;
    }
  }

  // ── OkHttp-backed Downloader for NewPipe ─────────────────────────────────

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
        String bodyStr = data != null ? new String(data, java.nio.charset.StandardCharsets.UTF_8) : "";
        String contentType = "application/json";
        if (request.headers() != null && request.headers().containsKey("Content-Type")) {
          List<String> ct = request.headers().get("Content-Type");
          if (ct != null && !ct.isEmpty()) contentType = ct.get(0);
        }
        body = okhttp3.RequestBody.create(bodyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8), okhttp3.MediaType.parse(contentType));
      }

      okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body);

      // Forward NewPipe's own headers first — they include the correct
      // X-YouTube-Client-Name / Version for whichever endpoint is being called
      if (request.headers() != null) {
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
          for (String value : entry.getValue()) {
            builder.addHeader(entry.getKey(), value);
          }
        }
      }

      // Only add browser UA for GET requests; NewPipe's POSTs set their own UA
      if (request.httpMethod().equals("GET")) {
        builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " + "AppleWebKit/537.36 (KHTML, like Gecko) " + "Chrome/124.0.0.0 Safari/537.36");
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