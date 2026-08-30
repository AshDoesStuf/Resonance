package me.ash.resonance.fragment;

import static android.content.Context.MODE_PRIVATE;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import me.ash.resonance.R;
import me.ash.resonance.playlist.M3uImporter;
import me.ash.resonance.playlist.PlaylistDetailActivity;
import me.ash.resonance.playlist.PlaylistManager;
import me.ash.resonance.playlist.PlaylistsAdapter;
import me.ash.resonance.ui.PlaylistOptionsSheet;
import me.ash.resonance.ui.ResonanceConfirmSheet;
import me.ash.resonance.ui.ResonanceInputSheet;

public class PlaylistsFragment extends Fragment {

  private static final int REQUEST_M3U = 2001;
  private static final String PREF_GRID_MODE = "grid_mode";
  private PlaylistsAdapter adapter;

  private ImageButton btnToggle;
  private RecyclerView rv;
  private boolean isGridMode = false;
  private android.content.BroadcastReceiver libraryReceiver;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_playlists, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    rv = view.findViewById(R.id.rvPlaylists);
    btnToggle = view.findViewById(R.id.btnToggleView);

    adapter = new PlaylistsAdapter(requireContext(),
            name -> startActivity(PlaylistDetailActivity.createIntent(requireContext(), name)));


    adapter.setLongClickListener(this::showPlaylistOptions);

    libraryReceiver = new android.content.BroadcastReceiver() {
      @Override
      public void onReceive(android.content.Context context, android.content.Intent intent) {
        adapter.refresh();
      }
    };
    androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(requireContext())
            .registerReceiver(libraryReceiver,
                    new android.content.IntentFilter(
                            me.ash.resonance.MusicLibraryEvent.ACTION_LIBRARY_CHANGED));

    rv.setAdapter(adapter);
    isGridMode = requireActivity().getPreferences(MODE_PRIVATE).getBoolean(PREF_GRID_MODE, false);
    applyLayoutManager();

    // Toggle list ↔ grid

    btnToggle.setOnClickListener(v -> {
      isGridMode = !isGridMode;
      requireActivity().getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_GRID_MODE, isGridMode).apply();
      applyLayoutManager();
    });

    // New playlist
    view.findViewById(R.id.btnNewPlaylist).setOnClickListener(v -> showCreateSheet());
    view.findViewById(R.id.btnImportPlaylist).setOnClickListener(v -> openFilePicker());
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

  private void applyLayoutManager() {
    btnToggle.setImageResource(
            isGridMode ? R.drawable.ic_list_view : R.drawable.ic_grid_view);

    adapter.setViewMode(isGridMode ? PlaylistsAdapter.GRID : PlaylistsAdapter.LIST);

    rv.setLayoutManager(isGridMode
            ? new GridLayoutManager(requireContext(), 2)
            : new LinearLayoutManager(requireContext()));
  }

  // ── Sheets ────────────────────────────────────────────────────────────

  private void showCreateSheet() {
    ResonanceInputSheet.newInstance(
            "New Playlist", "Playlist name", "", "Create",
            name -> {
              PlaylistManager.get(requireContext()).createPlaylist(name);
              adapter.refresh();
            }
    ).show(getChildFragmentManager(), "InputSheet");
  }

  private void showPlaylistOptions(String name) {
    PlaylistOptionsSheet.newInstance(name,
            () -> showRenameSheet(name),
            () -> showDeleteSheet(name)
    ).show(getChildFragmentManager(), "OptionsSheet");
  }

  private void showRenameSheet(String oldName) {
    ResonanceInputSheet.newInstance(
            "Rename Playlist", "Playlist name", oldName, "Rename",
            newName -> {
              if (newName.equals(oldName)) return;
              PlaylistManager.get(requireContext()).renamePlaylist(oldName, newName);
              adapter.refresh();
            }
    ).show(getChildFragmentManager(), "InputSheet");
  }

  private void showDeleteSheet(String name) {
    ResonanceConfirmSheet.newInstance(
            "Delete \"" + name + "\"?",
            "This will remove the playlist but not the songs.",
            () -> {
              PlaylistManager.get(requireContext()).deletePlaylist(name);
              adapter.refresh();
            }
    ).show(getChildFragmentManager(), ResonanceConfirmSheet.TAG);
  }

  private void openFilePicker() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    // Accept both .m3u and .m3u8
    intent.setType("*/*");
    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "audio/x-mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
            "*/*"  // fallback — some file managers don't set MIME correctly
    });
    startActivityForResult(intent, REQUEST_M3U);
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != REQUEST_M3U
            || resultCode != android.app.Activity.RESULT_OK
            || data == null || data.getData() == null) return;

    Uri fileUri = data.getData();
    // Persist read permission across process restarts
    requireContext().getContentResolver().takePersistableUriPermission(
            fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

    // Show a loading indicator
    android.widget.Toast.makeText(requireContext(),
            "Importing playlist…", android.widget.Toast.LENGTH_SHORT).show();

    M3uImporter.importFromUri(requireContext(), fileUri, new M3uImporter.ImportCallback() {
      @Override
      public void onDone(String name, int matched, int total) {
        adapter.refresh();
        String msg = "Imported \"" + name + "\" — "
                + matched + "/" + total + " songs matched";
        android.widget.Toast.makeText(requireContext(),
                msg, android.widget.Toast.LENGTH_LONG).show();
      }

      @Override
      public void onError(String message) {
        android.widget.Toast.makeText(requireContext(),
                "Import failed: " + message,
                android.widget.Toast.LENGTH_LONG).show();
      }
    });
  }

  @Override
  public void onResume() {
    super.onResume();
    if (adapter != null) adapter.refresh();
  }
}