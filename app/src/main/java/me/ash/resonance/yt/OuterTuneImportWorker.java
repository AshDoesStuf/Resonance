package me.ash.resonance.yt;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import me.ash.resonance.MusicLoader;
import me.ash.resonance.db.AppDatabase;
import me.ash.resonance.db.ImportedSongDao;
import me.ash.resonance.db.ImportedSongEntity;

public class OuterTuneImportWorker extends Worker {
  public static final String KEY_PROGRESS = "progress";
  public static final String KEY_TOTAL = "total";
  public static final String KEY_TRACK_TITLE = "track_title";
  private static final String TAG = "OuterTuneImportWorker";

  public OuterTuneImportWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
    super(context, workerParams);
  }

  public static void enqueue(Context context) {
    OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OuterTuneImportWorker.class)
            .addTag("OuterTuneImport")
            .build();
    WorkManager.getInstance(context).enqueue(request);
  }

  @NonNull
  @OptIn(markerClass = UnstableApi.class)
  @Override
  public Result doWork() {
    Log.d(TAG, "Starting OuterTune import background task");
    Context context = getApplicationContext();
    ImportedSongDao dao = AppDatabase.get(context).importedSongDao();

    try {
      List<OuterTuneImporter.ImportedSong> candidates = OuterTuneImporter.findCandidates(context);

      List<String> allIds = new ArrayList<>();
      for (OuterTuneImporter.ImportedSong s : candidates) allIds.add(s.videoId);

      if (allIds.isEmpty()) {
        return Result.success();
      }

      Set<String> existing = new HashSet<>(dao.findExistingVideoIds(allIds));
      List<OuterTuneImporter.ImportedSong> toFetch = new ArrayList<>();
      for (OuterTuneImporter.ImportedSong s : candidates) {
        if (!existing.contains(s.videoId)) toFetch.add(s);
      }

      if (toFetch.isEmpty()) {
        return Result.success();
      }

      int total = toFetch.size();
      AtomicInteger done = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(total);
      ExecutorService pool = Executors.newFixedThreadPool(5);

      for (OuterTuneImporter.ImportedSong song : toFetch) {
        pool.submit(() -> {
          try {
            YtTrack track = YtMusicService.get().fetchTrackBlocking(song.videoId);
            song.ytTrack = track;

            int n = done.incrementAndGet();
            updateProgress(n, total, track.title);
          } catch (Throwable t) {
            Log.e(TAG, "fetchTrack failed for " + song.videoId, t);
            int n = done.incrementAndGet();
            updateProgress(n, total, song.fileName);
          } finally {
            latch.countDown();
          }
        });
      }

      boolean finished = latch.await(15, TimeUnit.MINUTES);
      if (!finished) {
        Log.w(TAG, "Import timed out — some fetches never completed");
      }
      pool.shutdown();

      List<ImportedSongEntity> toInsert = new ArrayList<>();
      for (OuterTuneImporter.ImportedSong s : toFetch) {
        if (s.ytTrack != null) {
          toInsert.add(OuterTuneImporter.buildEntity(s));
        }
      }
      dao.insertAll(toInsert);

      MusicLoader.invalidate();
      return Result.success();

    } catch (Exception e) {
      Log.e(TAG, "Import work failed", e);
      return Result.failure();
    }
  }

  private void updateProgress(int done, int total, String trackTitle) {
    Data progress = new Data.Builder()
            .putInt(KEY_PROGRESS, done)
            .putInt(KEY_TOTAL, total)
            .putString(KEY_TRACK_TITLE, trackTitle)
            .build();
    setProgressAsync(progress);
  }
}
