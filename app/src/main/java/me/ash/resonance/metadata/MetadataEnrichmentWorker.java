package me.ash.resonance.metadata;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.List;

import me.ash.resonance.db.AppDatabase;
import me.ash.resonance.db.DownloadedSongEntity;
import me.ash.resonance.db.ImportedSongEntity;
import me.ash.resonance.db.RemoteSongEntity;
import me.ash.resonance.db.SongMetadataDao;
import me.ash.resonance.db.SongMetadataEntity;

public class MetadataEnrichmentWorker extends Worker {
  private static final String TAG = "MetadataEnrichmentWorker";
  private static final int BATCH_SIZE = 5; // Smaller batch for real API calls
  private static final int MAX_RETRIES = 3;

  private final List<MetadataProvider> providers = new ArrayList<>();

  public MetadataEnrichmentWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
    super(context, workerParams);
    providers.add(new LastFmProvider());
    providers.add(new MusicBrainzProvider());
  }

  @NonNull
  @Override
  public Result doWork() {
    Log.d(TAG, "Starting metadata enrichment batch");
    AppDatabase db = AppDatabase.get(getApplicationContext());
    SongMetadataDao dao = db.songMetadataDao();
    List<SongMetadataEntity> pending = dao.getPending(MAX_RETRIES, BATCH_SIZE);

    if (pending.isEmpty()) {
      return Result.success();
    }

    boolean hasFailures = false;
    for (SongMetadataEntity entity : pending) {
      try {
        enrichTrack(db, entity);
        entity.status = "COMPLETED";
        entity.lastUpdated = System.currentTimeMillis();
        dao.update(entity);
        // Respect API limits
        Thread.sleep(2000);
      } catch (Exception e) {
        Log.e(TAG, "Failed to enrich track: " + entity.songId, e);
        entity.retryCount++;
        entity.lastRetryTimestamp = System.currentTimeMillis();
        if (entity.retryCount >= MAX_RETRIES) {
          entity.status = "FAILED";
        }
        dao.update(entity);
        hasFailures = true;
      }
    }

    // If there are still pending tracks, we should reschedule
    if (dao.getPendingCount() > 0) {
      MetadataRepository.get(getApplicationContext()).scheduleEnrichment();
    }

    return hasFailures ? Result.retry() : Result.success();
  }

  private void enrichTrack(AppDatabase db, SongMetadataEntity entity) {
    String title = null;
    String artist = null;

    // Try to find the track details in our DB
    DownloadedSongEntity downloaded = db.downloadedSongDao().getByVideoId(entity.songId);
    if (downloaded != null) {
      title = downloaded.title;
      artist = downloaded.artist;
    } else {
      ImportedSongEntity imported = db.importedSongDao().getByVideoId(entity.songId);
      if (imported != null) {
        title = imported.title;
        artist = imported.artist;
      } else {
        RemoteSongEntity remote = db.remoteSongDao().getByVideoId(entity.songId);
        if (remote != null) {
          title = remote.title;
          artist = remote.artist;
        }
      }
    }

    if (title == null || artist == null) {
      Log.w(TAG, "Could not find title/artist for songId: " + entity.songId);
      return;
    }

    for (MetadataProvider provider : providers) {
      provider.enrich(title, artist, entity);
    }
  }
}
