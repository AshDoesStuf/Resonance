package me.ash.resonance.settings;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import me.ash.resonance.MusicLoader;
import me.ash.resonance.R;
import me.ash.resonance.ui.GlassStyle;
import me.ash.resonance.ui.GlassStyleManager;
import me.ash.resonance.ui.PlayerPosition;
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

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_settings);

    // Back button
    ImageButton btnBack = findViewById(R.id.btnSettingsBack);
    btnBack.setOnClickListener(v -> finish());

    bindImportCard();
    bindGlassStyleCard();
    bindPositionCard();
  }

  private void bindPositionCard() {
    togglePlayerPosition = findViewById(R.id.togglePlayerPosition);
    tvPositionHint = findViewById(R.id.tvPositionHint);

    PlayerPosition current = GlassStyleManager.get(this).currentPosition();
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
      GlassStyleManager.get(this).setPosition(chosen);
      tvPositionHint.setText(chosen == PlayerPosition.DOCKED ? "Docked" : "Floating");
    });
  }

  private void bindGlassStyleCard() {
    toggleGlassStyle = findViewById(R.id.toggleGlassStyle);
    tvGlassStyleHint = findViewById(R.id.tvGlassStyleHint);

    GlassStyle current = GlassStyleManager.get(this).current();
    toggleGlassStyle.check(
            current == GlassStyle.LIQUID
                    ? R.id.btnStyleLiquid
                    : R.id.btnStyleFrosted
    );
    tvGlassStyleHint.setText(current == GlassStyle.LIQUID ? "Liquid glass" : "Frosted glass");

    toggleGlassStyle.setOnCheckedChangeListener((group, checkedId) -> {
      GlassStyle chosen = (checkedId == R.id.btnStyleLiquid)
              ? GlassStyle.LIQUID
              : GlassStyle.FROSTED;
      GlassStyleManager.get(this).set(chosen);
      tvGlassStyleHint.setText(chosen == GlassStyle.LIQUID ? "Liquid glass" : "Frosted glass");
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
  }

  private void startImport() {
    importRunning = true;
    btnImport.setEnabled(false);

    pbImport.setVisibility(View.VISIBLE);
    pbImport.setProgress(0);

    tvImportProgress.setVisibility(View.VISIBLE);
    tvImportProgress.setText("Scanning…");

    tvImportStatus.setText("Looking for OuterTune files on your device…");
    rvImportResults.setVisibility(View.GONE);
    importAdapter.clear();

    OuterTuneImporter.scan(this, new OuterTuneImporter.ImportCallback() {

      @Override
      public void onProgress(int done, int total, String trackTitle) {
        runOnUiThread(() -> {
          int pct = (int) ((done / (float) total) * 100);
          pbImport.setMax(total);
          pbImport.setProgress(done);
          tvImportProgress.setText(done + " / " + total);
          tvImportStatus.setText("Fetching: " + trackTitle);
        });
      }

      @SuppressLint("SetTextI18n")
      @Override
      public void onComplete(List<OuterTuneImporter.ImportedSong> songs) {
        runOnUiThread(() -> {
          importRunning = false;
          btnImport.setEnabled(true);
          pbImport.setVisibility(View.GONE);
          tvImportProgress.setVisibility(View.GONE);

          if (songs.isEmpty()) {
            tvImportStatus.setText(
                    "No OuterTune files found.\n" +
                            "Make sure you've exported songs from OuterTune first.");
            return;
          }

          long ok = songs.stream().filter(s -> s.ytTrack != null).count();
          long failed = songs.size() - ok;

          String summary = ok + " song" + (ok == 1 ? "" : "s") + " imported";
          if (failed > 0) summary += "  ·  " + failed + " failed";
          tvImportStatus.setText(summary);

          MusicLoader.invalidate();
          importAdapter.setItems(songs);
          rvImportResults.setVisibility(View.VISIBLE);
        });
      }

      @Override
      public void onError(Exception e) {
        runOnUiThread(() -> {
          importRunning = false;
          btnImport.setEnabled(true);
          pbImport.setVisibility(View.GONE);
          tvImportProgress.setVisibility(View.GONE);
          tvImportStatus.setText("Import failed — " + e.getMessage());
          Toast.makeText(SettingsActivity.this,
                  "Import error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        });
      }
    });
  }
}