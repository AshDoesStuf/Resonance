package me.ash.resonance.fragment;

import android.annotation.SuppressLint;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import me.ash.resonance.R;
import me.ash.resonance.album.Album;
import me.ash.resonance.album.AlbumDetailActivity;
import me.ash.resonance.album.AlbumsAdapter;

public class AlbumsFragment extends Fragment {
  private final List<Album> allAlbums = new ArrayList<>();
  private final List<Album> displayedAlbums = new ArrayList<>();
  private AlbumsAdapter adapter;
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
    return inflater.inflate(R.layout.fragment_albums, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    etSearch = view.findViewById(R.id.etSearch);
    btnSearchClear = view.findViewById(R.id.btnSearchClear);
    searchBar = view.findViewById(R.id.searchBar);

    RecyclerView rv = view.findViewById(R.id.rvAlbums);
    rv.setLayoutManager(new GridLayoutManager(requireContext(), 2));


    adapter = new AlbumsAdapter(requireContext(), displayedAlbums, album -> {
      // Pass null for browseId, use local name/artist/artUri for local albums
      String artUrl = album.artUri() != null ? album.artUri().toString() : null;
      startActivity(AlbumDetailActivity.createIntent(requireContext(), null, album.name(), album.artist(), artUrl));
    });
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

  private void reloadData() {
    new Thread(() -> {
      List<Album> loaded = loadAlbums();
      loaded.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
      requireActivity().runOnUiThread(() -> {
        allAlbums.clear();
        allAlbums.addAll(loaded);
        applyFilter();
      });
    }).start();
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


  private void setupHeader(View view) {
    view.findViewById(R.id.btnSearch).setOnClickListener(v -> toggleSearch());


    btnSearchClear.setOnClickListener(v -> etSearch.setText(""));

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
        btnSearchClear.setVisibility(currentQuery.isEmpty() ? View.GONE : View.VISIBLE);
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
    displayedAlbums.clear();
    if (currentQuery.isEmpty()) {
      displayedAlbums.addAll(allAlbums);
    } else {
      String q = currentQuery.toLowerCase();
      for (Album a : allAlbums) {
        if (a.name().toLowerCase().contains(q)
                || (a.artist() != null && a.artist().toLowerCase().contains(q))) {
          displayedAlbums.add(a);
        }
      }
    }
    adapter.update(displayedAlbums);
  }

  private List<Album> loadAlbums() {
    List<Album> albums = new ArrayList<>();

    String[] projection = {
            MediaStore.Audio.Albums._ID,
            MediaStore.Audio.Albums.ALBUM,
            MediaStore.Audio.Albums.ARTIST,
            MediaStore.Audio.Albums.NUMBER_OF_SONGS
    };

    try (Cursor cursor = requireContext().getContentResolver().query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            projection,
            null, null,
            MediaStore.Audio.Albums.DEFAULT_SORT_ORDER)) {

      if (cursor == null) return albums;

      int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums._ID);
      int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM);
      int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ARTIST);
      int countCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.NUMBER_OF_SONGS);

      while (cursor.moveToNext()) {
        long id = cursor.getLong(idCol);
        String name = cursor.getString(nameCol);
        String artist = cursor.getString(artistCol);
        int songCount = cursor.getInt(countCol);
        Uri artUri = Uri.parse("content://media/external/audio/albumart/" + id);

        // Skip unnamed albums
        if (name == null || name.equals("<unknown>")) continue;

        albums.add(new Album(id, name, artist, songCount, artUri));
      }
    }

    return albums;
  }
}