package me.ash.resonance.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import me.ash.resonance.R;

/**
 * Glass-styled options sheet shown on long-press of a playlist.
 * Shows "Rename" and "Delete" rows.
 */
public class PlaylistOptionsSheet extends BottomSheetDialogFragment {

  private static final String ARG_NAME = "playlist_name";
  private OnAction onRename, onDelete;

  public static PlaylistOptionsSheet newInstance(
          String playlistName, OnAction onRename, OnAction onDelete) {
    PlaylistOptionsSheet sheet = new PlaylistOptionsSheet();
    sheet.onRename = onRename;
    sheet.onDelete = onDelete;
    Bundle b = new Bundle();
    b.putString(ARG_NAME, playlistName);
    sheet.setArguments(b);
    return sheet;
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.dialog_playlist_options, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    if (getDialog() != null && getDialog().getWindow() != null)
      getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);

    String name = getArguments() != null ? getArguments().getString(ARG_NAME, "") : "";
    ((TextView) view.findViewById(R.id.tvOptionsTitle)).setText(name.toUpperCase());

    view.findViewById(R.id.rowRename).setOnClickListener(v -> {
      dismiss();
      if (onRename != null) onRename.run();
    });

    view.findViewById(R.id.rowDelete).setOnClickListener(v -> {
      dismiss();
      if (onDelete != null) onDelete.run();
    });
  }

  public interface OnAction {
    void run();
  }
}