package me.ash.resonance.metadata;

import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import me.ash.resonance.db.SongMetadataEntity;
import me.ash.resonance.yt.YtMusicService;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

public class LastFmProvider implements MetadataProvider {
    private static final String TAG = "LastFmProvider";
    private static final String API_KEY = "YOUR_LASTFM_API_KEY"; // Placeholder
    private static final String BASE_URL = "https://ws.audioscrobbler.com/2.0/";
    private final Gson gson = new Gson();

    @Override
    public void enrich(String title, String artist, SongMetadataEntity entity) {
        if (API_KEY.equals("YOUR_LASTFM_API_KEY")) {
            Log.w(TAG, "Last.fm API key not set. Skipping lookup.");
            return;
        }

        try {
            fetchTrackInfo(title, artist, entity);
            fetchArtistInfo(artist, entity);
        } catch (Exception e) {
            Log.e(TAG, "Error enriching from Last.fm", e);
        }
    }

    private void fetchTrackInfo(String title, String artist, SongMetadataEntity entity) throws IOException {
        HttpUrl url = HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("method", "track.getInfo")
                .addQueryParameter("api_key", API_KEY)
                .addQueryParameter("artist", artist)
                .addQueryParameter("track", title)
                .addQueryParameter("format", "json")
                .build();

        Request request = new Request.Builder().url(url).build();
        try (Response response = YtMusicService.get().http.newCall(request).execute()) {
            if (!response.isSuccessful()) return;
            String body = response.body().string();
            JsonObject json = gson.fromJson(body, JsonObject.class);
            if (json.has("track")) {
                JsonObject track = json.getAsJsonObject("track");
                
                if (track.has("url")) entity.trackFmUrl = track.get("url").getAsString();
                if (track.has("mbid")) entity.mbid = track.get("mbid").getAsString();
                
                // Tags
                if (track.has("toptags")) {
                    JsonObject toptags = track.getAsJsonObject("toptags");
                    if (toptags.has("tag")) {
                        JsonArray tagsArray = toptags.getAsJsonArray("tag");
                        List<String> rawTags = new ArrayList<>();
                        for (JsonElement el : tagsArray) {
                            rawTags.add(el.getAsJsonObject().get("name").getAsString());
                        }
                        List<String> normalized = MetadataNormalizer.normalizeTags(rawTags);
                        if (!normalized.isEmpty()) {
                            entity.genre = normalized.get(0);
                            entity.genres = String.join(",", normalized);
                        }
                        entity.tags = String.join(",", rawTags);
                    }
                }
                
                if (entity.providerSource == null) entity.providerSource = "lastfm";
                else if (!entity.providerSource.contains("lastfm")) entity.providerSource += ",lastfm";
            }
        }
    }

    private void fetchArtistInfo(String artist, SongMetadataEntity entity) throws IOException {
        HttpUrl url = HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("method", "artist.getInfo")
                .addQueryParameter("api_key", API_KEY)
                .addQueryParameter("artist", artist)
                .addQueryParameter("format", "json")
                .build();

        Request request = new Request.Builder().url(url).build();
        try (Response response = YtMusicService.get().http.newCall(request).execute()) {
            if (!response.isSuccessful()) return;
            String body = response.body().string();
            JsonObject json = gson.fromJson(body, JsonObject.class);
            if (json.has("artist")) {
                JsonObject artistObj = json.getAsJsonObject("artist");
                
                if (artistObj.has("url")) entity.artistFmUrl = artistObj.get("url").getAsString();
                if (artistObj.has("bio")) {
                    JsonObject bio = artistObj.getAsJsonObject("bio");
                    if (bio.has("summary")) entity.artistBio = bio.get("summary").getAsString();
                }

                // Similar Artists
                if (artistObj.has("similar")) {
                    JsonObject similar = artistObj.getAsJsonObject("similar");
                    if (similar.has("artist")) {
                        JsonArray similarArray = similar.getAsJsonArray("artist");
                        List<String> similarNames = new ArrayList<>();
                        for (JsonElement el : similarArray) {
                            similarNames.add(el.getAsJsonObject().get("name").getAsString());
                        }
                        entity.similarArtists = String.join(",", similarNames);
                    }
                }
            }
        }
    }
}
