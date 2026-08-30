package me.ash.resonance.settings;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.media3.session.MediaController;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.Data;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.List;

import me.ash.resonance.MusicLoader;
import me.ash.resonance.MiniPlayerManager;
import me.ash.resonance.R;
import me.ash.resonance.remote.RemoteControlService;
import me.ash.resonance.ui.PlayerPosition;
import me.ash.resonance.util.MiniPlayerPositionManager;
import me.ash.resonance.util.PlaybackSettingsManager;
import me.ash.resonance.util.NetworkUtils;
import me.ash.resonance.yt.OuterTuneImportWorker;
import me.ash.resonance.yt.OuterTuneImporter;

public class SettingsActivity extends AppCompatActivity {

  // ── Import UI refs ────────────────────────────────────────────────────────
  private View cardImport;
  private TextView tvImportStatus;
  private ProgressBar pbImport;
  private TextView tvImportProgress;
  private View btnImport;
  private RadioGroup togglePlayerPosition;
  private TextView tvPositionHint;
  private RadioGroup toggleGlassStyle;
  private TextView tvGlassStyleHint;
  private boolean importRunning = false;

  // ── Import results list ───────────────────────────────────────────────────
  private RecyclerView rvImportResults;
  private ImportResultsAdapter importAdapter;
  private MiniPlayerManager miniPlayerManager;
  private MediaController controller;

  private SeekBar sbCrossfade;
  private TextView tvCrossfadeHint;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_settings);

    // Back button
    ImageButton btnBack = findViewById(R.id.btnSettingsBack);
    btnBack.setOnClickListener(v -> finish());

    miniPlayerManager = new MiniPlayerManager(this);
    ((me.ash.resonance.ResonanceApp) getApplication()).getSharedController(ctrl -> {
      controller = ctrl;
      miniPlayerManager.init(controller);
    });

    bindImportCard();
    bindPositionCard();
    bindRemoteControlCard();
    bindPlaybackCard();
  }

  private void bindPlaybackCard() {
    sbCrossfade = findViewById(R.id.sbCrossfade);
    tvCrossfadeHint = findViewById(R.id.tvCrossfadeHint);

    int current = PlaybackSettingsManager.get(this).getCrossfadeDuration();
    sbCrossfade.setProgress(current);
    updateCrossfadeHint(current);

    sbCrossfade.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        updateCrossfadeHint(progress);
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {}

      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {
        PlaybackSettingsManager.get(SettingsActivity.this).setCrossfadeDuration(seekBar.getProgress());
      }
    });
  }

  private void updateCrossfadeHint(int seconds) {
    if (seconds == 0) {
      tvCrossfadeHint.setText("Off");
    } else {
      tvCrossfadeHint.setText(seconds + " seconds");
    }
  }


  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (miniPlayerManager != null) {
      miniPlayerManager.detach();
    }
  }

  private void bindRemoteControlCard() {
    SwitchCompat switchRemote = findViewById(R.id.switchRemoteControl);
    TextView tvStatus = findViewById(R.id.tvRemoteStatus);

    boolean isRunning = isServiceRunning(RemoteControlService.class);
    switchRemote.setChecked(isRunning);
    updateRemoteStatus(tvStatus, isRunning);

    switchRemote.setOnCheckedChangeListener((buttonView, isChecked) -> {
      Intent intent = new Intent(this, RemoteControlService.class);
      if (isChecked) {
        startService(intent);
      } else {
        stopService(intent);
      }
      updateRemoteStatus(tvStatus, isChecked);
    });
  }

  private void updateRemoteStatus(TextView tvStatus, boolean isRunning) {
    if (isRunning) {
      String ip = NetworkUtils.getLocalIpAddress();
      tvStatus.setText("Running at " + ip + ":8080");
    } else {
      tvStatus.setText("Not running");
    }
  }

  private boolean isServiceRunning(Class<?> serviceClass) {
    ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
    for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
      if (serviceClass.getName().equals(service.service.getClassName())) {
        return true;
      }
    }
    return false;
  }

  private void bindPositionCard() {
    togglePlayerPosition = findViewById(R.id.togglePlayerPosition);
    tvPositionHint = findViewById(R.id.tvPositionHint);

    PlayerPosition current = MiniPlayerPositionManager.get(this).currentPosition();
    togglePlayerPosition.check(
            current == PlayerPosition.DOCKED
                    ? R.id.btnPositionDocked
                    : R.id.btnPositionFloating
    );
    tvPositionHint.setText(current == PlayerPosition.DOCKED ? "Docked" : "Floating");

    togglePlayerPosition.setOnCheckedChangeListener((group, checkedId) -> {
      PlayerPosition chosen = (checkedId == R.id.btnPositionDocked)
              ? PlayerPosition.DOCKED
              : PlayerPosition.FLOATING;
      tvPositionHint.setText(chosen == PlayerPosition.DOCKED ? "Docked" : "Floating");
      MiniPlayerPositionManager.get(this).setPosition(chosen);
    });

  }

  // ── Import card wiring ────────────────────────────────────────────────────

  private void bindImportCard() {
    cardImport = findViewById(R.id.cardImport);
    tvImportStatus = findViewById(R.id.tvImportStatus);
    pbImport = findViewById(R.id.pbImport);
    tvImportProgress = findViewById(R.id.tvImportProgress);
    btnImport = findViewById(R.id.btnImportOuterTune);
    rvImportResults = findViewById(R.id.rvImportResults);

    importAdapter = new ImportResultsAdapter();
    rvImportResults.setLayoutManager(new LinearLayoutManager(this));
    rvImportResults.setAdapter(importAdapter);
    rvImportResults.setNestedScrollingEnabled(false);

    btnImport.setOnClickListener(v -> {
      if (!importRunning) startImport();
    });

    observeImportWork();
  }

  private void observeImportWork() {
    WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData("OuterTuneImport")
            .observe(this, workInfos -> {
              if (workInfos == null || workInfos.isEmpty()) return;

              // Find the most relevant work info (the one that is running or was last completed)
              WorkInfo workInfo = workInfos.stream()
                      .filter(w -> w.getState() == WorkInfo.State.RUNNING)
                      .findFirst()
                      .orElse(workInfos.get(workInfos.size() - 1));

              updateImportUI(workInfo);
            });
  }

  @SuppressLint("SetTextI18n")
  private void updateImportUI(WorkInfo workInfo) {
    WorkInfo.State state = workInfo.getState();
    Data progressData = workInfo.getProgress();

    if (state == WorkInfo.State.RUNNING) {
      importRunning = true;
      btnImport.setEnabled(false);
      pbImport.setVisibility(View.VISIBLE);
      tvImportProgress.setVisibility(View.VISIBLE);
      rvImportResults.setVisibility(View.GONE);

      int done = progressData.getInt(OuterTuneImportWorker.KEY_PROGRESS, 0);
      int total = progressData.getInt(OuterTuneImportWorker.KEY_TOTAL, 0);
      String title = progressData.getString(OuterTuneImportWorker.KEY_TRACK_TITLE);

      if (total > 0) {
        pbImport.setMax(total);
        pbImport.setProgress(done);
        tvImportProgress.setText(done + " / " + total);
        tvImportStatus.setText("Fetching: " + (title != null ? title : "..."));
      } else {
        pbImport.setIndeterminate(true);
        tvImportProgress.setText("Scanning…");
        tvImportStatus.setText("Looking for OuterTune files on your device…");
      }
    } else if (state.isFinished()) {
      importRunning = false;
      btnImport.setEnabled(true);
      pbImport.setVisibility(View.GONE);
      tvImportProgress.setVisibility(View.GONE);

      if (state == WorkInfo.State.SUCCEEDED) {
        tvImportStatus.setText("Import complete. Check your library.");
      } else if (state == WorkInfo.State.FAILED) {
        tvImportStatus.setText("Import failed. Try again.");
      }
    }
  }

  private void startImport() {
    OuterTuneImportWorker.enqueue(this);
  }
}