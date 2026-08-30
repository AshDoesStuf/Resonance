package me.ash.resonance.song;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import me.ash.resonance.MiniPlayerManager;
import me.ash.resonance.MusicLoader;
import me.ash.resonance.R;
import me.ash.resonance.ui.AlphabetSidebarHelper;

public class SongSelectionActivity extends AppCompatActivity {

  public static final String EXTRA_SELECTED_IDS = "selected_ids";

  private List<Song> allSongs = new ArrayList<>();
  private List<Song> displayedSongs = new ArrayList<>();

  private MiniPlayerManager miniPlayerManager;
  private MediaController controller;
  private RecyclerView recyclerView;
  private SongAdapter adapter;
  private AlphabetSidebarHelper sidebarHelper;

  private CheckBox cbSelectAll;
  private TextView tvSelectionCount;
  private ImageButton btnDone;
  private LinearLayout searchBar;
  private EditText etSearch;
  private ImageView btnSearchClear;

  private boolean searchOpen = false;
  private String currentQuery = "";

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_song_selection);

    miniPlayerManager = new MiniPlayerManager(this);
    ((me.ash.resonance.ResonanceApp) getApplication()).getSharedController(ctrl -> {
      controller = ctrl;
      miniPlayerManager.init(controller);
    });

    bindViews();
    setupRecycler();
    setupListeners();

    if (savedInstanceState != null) {
      searchOpen = savedInstanceState.getBoolean("search_open", false);
      currentQuery = savedInstanceState.getString("query", "");
      ArrayList<String> savedSelected = savedInstanceState.getStringArrayList("selected_ids");
      if (savedSelected != null) {
        for (String id : savedSelected) adapter.toggleSelection(id);
      }

      if (searchOpen) {
        searchBar.setVisibility(View.VISIBLE);
        etSearch.setText(currentQuery);
      }
    }

    loadSongs();
  }

  @Override
  protected void onSaveInstanceState(android.os.Bundle outState) {
    super.onSaveInstanceState(outState);
    Set<String> selected = adapter.getSelectedIds();
    ArrayList<String> selectedStr = new ArrayList<>();
    for (String id : selected) selectedStr.add(String.valueOf(id));
    outState.putStringArrayList("selected_ids", selectedStr);
    outState.putBoolean("search_open", searchOpen);
    outState.putString("query", currentQuery);
  }

  private void bindViews() {
    recyclerView = findViewById(R.id.rvSongs);
    cbSelectAll = findViewById(R.id.cbSelectAll);
    tvSelectionCount = findViewById(R.id.tvSelectionCount);
    btnDone = findViewById(R.id.btnDone);
    searchBar = findViewById(R.id.searchBar);
    etSearch = findViewById(R.id.etSearch);
    btnSearchClear = findViewById(R.id.btnSearchClear);
  }

  private void setupRecycler() {
    recyclerView.setLayoutManager(new LinearLayoutManager(this));
    adapter = new SongAdapter(song -> {
    }); // Click is handled by adapter in selection mode
    adapter.setSelectionMode(true);
    adapter.setOnSelectionChangedListener(count -> {
      tvSelectionCount.setText(count + " selected");
      if (count > 0) {
        btnDone.setImageTintList(
                ColorStateList.valueOf(
                        getColor(R.color.accent)
                )
        );
      } else btnDone.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_disabled)));

      // Re-check visible selection state for Select All checkbox
      int visibleSelectedCount = 0;
      Set<String> selectedIds = adapter.getSelectedIds();
      for (Song s : displayedSongs) {
        if (selectedIds.contains(s.id)) visibleSelectedCount++;
      }
      cbSelectAll.setChecked(!displayedSongs.isEmpty() && visibleSelectedCount == displayedSongs.size());
    });
    recyclerView.setAdapter(adapter);

    sidebarHelper = new AlphabetSidebarHelper(
            findViewById(R.id.alphaSidebar), recyclerView, displayedSongs);

    recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
        sidebarHelper.updateFromScroll();
      }
    });
  }

  private void setupListeners() {
    cbSelectAll.setOnClickListener(v -> {
      if (cbSelectAll.isChecked()) {
        adapter.selectAll(displayedSongs);
      } else {
        adapter.deselectSongs(displayedSongs);
      }
    });

    findViewById(R.id.btnSearch).setOnClickListener(v -> toggleSearch());

    btnSearchClear.setOnClickListener(v -> etSearch.setText(""));

    etSearch.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        currentQuery = s.toString().trim();
        btnSearchClear.setVisibility(currentQuery.isEmpty() ? View.GONE : View.VISIBLE);
        applyFilter();
      }

      @Override
      public void afterTextChanged(Editable s) {
      }
    });

    btnDone.setOnClickListener(v -> {
      Set<String> selectedIds = adapter.getSelectedIds();
      ArrayList<String> resultIds = new ArrayList<>();
      for (String id : selectedIds) resultIds.add(String.valueOf(id));

      Intent data = new Intent();
      data.putStringArrayListExtra(EXTRA_SELECTED_IDS, resultIds);
      setResult(RESULT_OK, data);
      finish();
    });
  }

  private void loadSongs() {
    new Thread(() -> {
      List<Song> loaded = MusicLoader.loadSongs(this);
      runOnUiThread(() -> {
        if (loaded != null) {
          allSongs.clear();
          allSongs.addAll(loaded);
          // Default sort by name for selection
          allSongs.sort((a, b) -> {
            String ta = a.title != null ? a.title : "";
            String tb = b.title != null ? b.title : "";
            return ta.compareToIgnoreCase(tb);
          });

          displayedSongs.clear();
          displayedSongs.addAll(allSongs);
          adapter.update(displayedSongs);
          sidebarHelper.setup();
        }
      });
    }).start();
  }

  private void toggleSearch() {
    searchOpen = !searchOpen;
    if (searchOpen) {
      searchBar.setVisibility(View.VISIBLE);
      etSearch.requestFocus();
      InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
      if (imm != null) imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
    } else {
      etSearch.setText("");
      searchBar.setVisibility(View.GONE);
      InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
      if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }
  }

  private void applyFilter() {
    displayedSongs.clear();
    if (currentQuery.isEmpty()) {
      displayedSongs.addAll(allSongs);
    } else {
      String q = currentQuery.toLowerCase();
      for (Song s : allSongs) {
        String title = s.title != null ? s.title.toLowerCase() : "";
        String artist = s.artist != null ? s.artist.toLowerCase() : "";
        if (title.contains(q) || artist.contains(q)) {
          displayedSongs.add(s);
        }
      }
    }
    adapter.update(displayedSongs);
    sidebarHelper.setup();

    // Update Select All state after filter
    int selectedCount = 0;
    Set<String> selectedIds = adapter.getSelectedIds();
    for (Song s : displayedSongs) {
      if (selectedIds.contains(s.id)) selectedCount++;
    }
    cbSelectAll.setChecked(!displayedSongs.isEmpty() && selectedCount == displayedSongs.size());
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (miniPlayerManager != null) {
      miniPlayerManager.detach();
    }
  }
}
