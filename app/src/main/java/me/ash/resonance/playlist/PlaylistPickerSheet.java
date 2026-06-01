package me.ash.resonance.playlist;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;
import java.util.Map;

import me.ash.resonance.R;
import me.ash.resonance.ui.ResonanceDialog;

public class PlaylistPickerSheet extends BottomSheetDialogFragment {

  public static final String TAG = "PlaylistPickerSheet";
  private static final String ARG_MEDIA_ID = "media_id";

  public static PlaylistPickerSheet newInstance(String mediaId) {
    PlaylistPickerSheet sheet = new PlaylistPickerSheet();
    Bundle args = new Bundle();
    args.putString(ARG_MEDIA_ID, mediaId);
    sheet.setArguments(args);
    return sheet;
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.bottom_sheet_playlist_picker, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    if (getDialog() != null && getDialog().getWindow() != null)
      getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);

    String mediaId = getArguments() != null ? getArguments().getString(ARG_MEDIA_ID) : null;
    if (mediaId == null) {
      dismiss();
      return;
    }

    LinearLayout listContainer = view.findViewById(R.id.playlistListContainer);
    view.findViewById(R.id.btnNewPlaylist).setOnClickListener(v ->
            showCreateDialog(mediaId, listContainer));

    populateList(listContainer, mediaId);
  }

  private void populateList(LinearLayout container, String mediaId) {
    container.removeAllViews();
    PlaylistManager pm = PlaylistManager.get(requireContext());
    Map<String, List<String>> all = pm.getAllPlaylists();

    if (all.isEmpty()) {
      TextView empty = new TextView(requireContext());
      empty.setText("No playlists yet. Create one!");
      empty.setTextColor(0xFF888899);
      empty.setPadding(64, 32, 64, 32);
      container.addView(empty);
      return;
    }

    for (String name : all.keySet()) {
      View row = LayoutInflater.from(requireContext())
              .inflate(R.layout.item_playlist_row, container, false);

      TextView tvName = row.findViewById(R.id.tvPlaylistName);
      TextView tvCheck = row.findViewById(R.id.tvPlaylistCheck);
      tvName.setText(name);

      boolean already = pm.isInPlaylist(name, mediaId);
      tvCheck.setVisibility(already ? View.VISIBLE : View.GONE);

      row.setOnClickListener(v -> {
        pm.addToPlaylist(name, mediaId);
        Toast.makeText(requireContext(), "Added to " + name, Toast.LENGTH_SHORT).show();
        dismiss();
      });

      container.addView(row);
    }
  }

  private void showCreateDialog(String mediaId, LinearLayout container) {
    EditText input = new EditText(requireContext());
    input.setHint("Playlist name");

    new ResonanceDialog.Builder(requireContext())
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create", (d, w) -> {
              String name = input.getText().toString().trim();
              if (name.isEmpty()) return;
              PlaylistManager.get(requireContext()).createPlaylist(name);
              PlaylistManager.get(requireContext()).addToPlaylist(name, mediaId);
              Toast.makeText(requireContext(), "Created & added to " + name, Toast.LENGTH_SHORT).show();
              populateList(container, mediaId);
            })
            .setNegativeButton("Cancel", null)
            .show();
  }
}
