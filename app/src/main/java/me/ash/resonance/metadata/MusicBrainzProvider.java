package me.ash.resonance.metadata;

import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import me.ash.resonance.db.SongMetadataEntity;
import me.ash.resonance.yt.YtMusicService;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

public class MusicBrainzProvider implements MetadataProvider {
    private static final String TAG = "MusicBrainzProvider";
    private static final String BASE_URL = "https://musicbrainz.org/ws/2/";
    private static final String USER_AGENT = "ResonanceMusicPlayer/1.0 ( https://github.com/ash/resonance )";
    private final Gson gson = new Gson();

    @Override
    public void enrich(String title, String artist, SongMetadataEntity entity) {
        try {
            searchRecording(title, artist, entity);
        } catch (Exception e) {
            Log.e(TAG, "Error enriching from MusicBrainz", e);
        }
    }

    private void searchRecording(String title, String artist, SongMetadataEntity entity) throws IOException {
        String query = String.format("recording:\"%s\" AND artist:\"%s\"", title, artist);
        HttpUrl url = HttpUrl.parse(BASE_URL + "recording/").newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("fmt", "json")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

        try (Response response = YtMusicService.get().http.newCall(request).execute()) {
            if (!response.isSuccessful()) return;
            String body = response.body().string();
            JsonObject json = gson.fromJson(body, JsonObject.class);
            
            if (json.has("recordings")) {
                JsonArray recordings = json.getAsJsonArray("recordings");
                if (recordings.size() > 0) {
                    JsonObject recording = recordings.get(0).getAsJsonObject();
                    
                    if (recording.has("id")) entity.mbid = recording.get("id").getAsString();
                    
                    // Artist ID
                    if (recording.has("artist-credit")) {
                        JsonArray credit = recording.getAsJsonArray("artist-credit");
                        if (credit.size() > 0) {
                            JsonObject artistRef = credit.get(0).getAsJsonObject();
                            if (artistRef.has("artist")) {
                                JsonObject actualArtist = artistRef.getAsJsonObject("artist");
                                if (actualArtist.has("id")) entity.artistMbid = actualArtist.get("id").getAsString();
                            }
                        }
                    }

                    // Release / Album info
                    if (recording.has("releases")) {
                        JsonArray releases = recording.getAsJsonArray("releases");
                        if (releases.size() > 0) {
                            JsonObject release = releases.get(0).getAsJsonObject();
                            if (release.has("id")) entity.releaseMbid = release.get("id").getAsString();
                            if (release.has("date")) entity.releaseDate = release.get("date").getAsString();
                        }
                    }

                    if (entity.providerSource == null) entity.providerSource = "musicbrainz";
                    else if (!entity.providerSource.contains("musicbrainz")) entity.providerSource += ",musicbrainz";
                }
            }
        }
    }
}
