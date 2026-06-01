package me.ash.resonance.fragment;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.ash.resonance.MusicLoader;
import me.ash.resonance.R;
import me.ash.resonance.artist.Artist;
import me.ash.resonance.artist.ArtistDetailActivity;
import me.ash.resonance.artist.ArtistsAdapter;
import me.ash.resonance.song.Song;

public class ArtistsFragment extends Fragment {
  private final List<Artist> allArtists = new ArrayList<>();
  private final List<Artist> displayedArtists = new ArrayList<>();
  private ArtistsAdapter adapter;
  private boolean searchOpen = false;

  private LinearLayout searchBar;
  private EditText etSearch;
  private ImageView btnSearchClear;
  private String currentQuery = "";

  private android.content.BroadcastReceiver libraryReceiver;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_artists, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    etSearch = view.findViewById(R.id.etSearch);
    btnSearchClear = view.findViewById(R.id.btnSearchClear);
    searchBar = view.findViewById(R.id.searchBar);

    RecyclerView rv = view.findViewById(R.id.rvArtists);
    rv.setLayoutManager(new LinearLayoutManager(requireContext()));

    adapter = new ArtistsAdapter(requireContext(), displayedArtists, artist -> startActivity(ArtistDetailActivity.createIntent(requireContext(), artist)));

    rv.setAdapter(adapter);

    libraryReceiver = new android.content.BroadcastReceiver() {
      @Override
      public void onReceive(android.content.Context context, android.content.Intent intent) {
        reloadData(); // your existing method that re-runs the background thread + refreshes adapter
      }
    };
    androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(requireContext())
            .registerReceiver(libraryReceiver,
                    new android.content.IntentFilter(
                            me.ash.resonance.MusicLibraryEvent.ACTION_LIBRARY_CHANGED));

    reloadData();
    setupHeader(view);
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    if (libraryReceiver != null) {
      androidx.localbroadcastmanager.content.LocalBroadcastManager
              .getInstance(requireContext())
              .unregisterReceiver(libraryReceiver);
    }
  }


  private void reloadData() {
    new Thread(() -> {
      List<Artist> loaded = buildArtistList();
      requireActivity().runOnUiThread(() -> {
        allArtists.clear();
        allArtists.addAll(loaded);
        applyFilter();
      });
    }).start();
  }

  private void setupHeader(View view) {
    view.findViewById(R.id.btnSearch).setOnClickListener(v -> toggleSearch());

    EditText etSearch = view.findViewById(R.id.etSearch);
    ImageView btnClear = view.findViewById(R.id.btnSearchClear);

    btnClear.setOnClickListener(v -> etSearch.setText(""));

    etSearch.addTextChangedListener(new android.text.TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int st, int c, int a) {
      }

      @Override
      public void afterTextChanged(android.text.Editable s) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        currentQuery = s.toString().trim();
        btnClear.setVisibility(currentQuery.isEmpty() ? View.GONE : View.VISIBLE);
        applyFilter();
      }
    });
  }

  private void toggleSearch() {
    searchOpen = !searchOpen;
    if (searchOpen) {
      searchBar.setVisibility(View.VISIBLE);
      searchBar.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
      etSearch.requestFocus();
      InputMethodManager imm = (InputMethodManager)
              requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
      imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
    } else {
      etSearch.setText("");
      searchBar.setVisibility(View.GONE);
      InputMethodManager imm = (InputMethodManager)
              requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
      imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }
  }

  @SuppressLint("NotifyDataSetChanged")
  private void applyFilter() {
    displayedArtists.clear();
    if (currentQuery.isEmpty()) {
      displayedArtists.addAll(allArtists);
    } else {
      String q = currentQuery.toLowerCase();
      for (Artist a : allArtists) {
        if (a.name.toLowerCase().contains(q)) {
          displayedArtists.add(a);
        }
      }
    }
    adapter.update(displayedArtists);
  }

  /**
   * Aggregate songs by artist name, sorted alphabetically.
   */
  private List<Artist> buildArtistList() {
    List<Song> songs = MusicLoader.loadSongs(requireContext());

    // Use LinkedHashMap so insertion order is preserved after we sort
    Map<String, Integer> countMap = new LinkedHashMap<>();

    for (Song song : songs) {
      String artist = (song.artist != null && !song.artist.equals("<unknown>"))
              ? song.artist.trim()
              : "Unknown Artist";

      countMap.put(artist, countMap.getOrDefault(artist, 0) + 1);
    }

    List<Artist> artists = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : countMap.entrySet()) {
      artists.add(new Artist(entry.getKey(), entry.getValue()));
    }

    artists.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
    return artists;
  }
}