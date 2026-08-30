package me.ash.resonance.metadata;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.ash.resonance.db.AppDatabase;
import me.ash.resonance.db.SongMetadataDao;
import me.ash.resonance.db.SongMetadataEntity;
import me.ash.resonance.song.Song;

public class MetadataRepository {
  private static MetadataRepository instance;
  private final SongMetadataDao dao;
  private final WorkManager workManager;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  private MetadataRepository(Context context) {
    dao = AppDatabase.get(context).songMetadataDao();
    workManager = WorkManager.getInstance(context);
  }

  public static synchronized MetadataRepository get(Context context) {
    if (instance == null) {
      instance = new MetadataRepository(context);
    }
    return instance;
  }

  /**
   * Tries to get cached metadata. If not found, queues enrichment and returns null.
   */
  public void getMetadata(Song song, MetadataCallback callback) {
    executor.execute(() -> {
      SongMetadataEntity entity = dao.getById(song.id);
      if (entity != null && "COMPLETED".equals(entity.status)) {
        callback.onResult(entity);
      } else {
        if (entity == null) {
          dao.insert(new SongMetadataEntity(song.id));
        }
        scheduleEnrichment();
        callback.onResult(null);
      }
    });
  }

  public void enqueueEnrichmentFor(java.util.List<Song> songs) {
    executor.execute(() -> {
      boolean added = false;
      for (Song song : songs) {
        if (dao.getById(song.id) == null) {
          dao.insert(new SongMetadataEntity(song.id));
          added = true;
        }
      }
      if (added) {
        scheduleEnrichment();
      }
    });
  }

  public void scheduleEnrichment() {
    Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();

    OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(MetadataEnrichmentWorker.class)
            .setConstraints(constraints)
            .addTag("metadata_enrichment")
            .setInitialDelay(5, java.util.concurrent.TimeUnit.MINUTES) // Process batches every 5 mins
            .build();

    workManager.enqueueUniqueWork(
            "metadata_enrichment",
            ExistingWorkPolicy.KEEP,
            request
    );
  }

  public interface MetadataCallback {
    void onResult(SongMetadataEntity metadata);
  }
}
