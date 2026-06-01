package me.ash.resonance.queue;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.ash.resonance.R;
import me.ash.resonance.playlist.PlaylistManager;
import me.ash.resonance.ui.ResonanceDialog;

public class QueueBottomSheet extends BottomSheetDialogFragment {

  public static final String TAG = "QueueBottomSheet";

  private MediaController controller;
  private QueueAdapter adapter;
  private TextView tvQueueCount;

  private Player.Listener playerListener;

  public static QueueBottomSheet newInstance() {
    return new QueueBottomSheet();
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.bottom_sheet_queue, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    if (getDialog() != null && getDialog().getWindow() != null) {
      getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }

    tvQueueCount = view.findViewById(R.id.tvQueueCount);

    RecyclerView rv = view.findViewById(R.id.rvQueue);
    rv.setLayoutManager(new LinearLayoutManager(requireContext()));

    adapter = new QueueAdapter(new QueueAdapter.QueueInteractionListener() {
      @Override
      public void onItemMoved(int from, int to) {
        if (controller != null) QueueManager.get().moveItem(controller, from, to);
      }

      @Override
      public void onItemRemoved(int index) {
        if (controller != null) {
          QueueManager.get().removeItem(controller, index);
          updateCount();
        }
      }

      @Override
      public void onItemClicked(int index) {
        if (controller != null) {
          controller.seekTo(index, 0);
          controller.play();
        }
      }
    });

    rv.setAdapter(adapter);
    adapter.attachTouchHelper(rv);

    view.findViewById(R.id.btnSaveQueueAsPlaylist).setOnClickListener(v ->
            showSaveQueueSheet(false)); // false = don't dismiss after, just save

    view.findViewById(R.id.btnAppendQueueToPlaylist).setOnClickListener(v ->
            showSaveQueueSheet(true));  // true = treat as "append" label

    view.findViewById(R.id.btnTempSession).setOnClickListener(v ->
            handleTempSession());

    connectController();
  }

  private void connectController() {
    ((me.ash.resonance.ResonanceApp) requireActivity().getApplication())
            .getSharedController(ctrl -> {
              controller = ctrl;
              populateQueue();
              attachListener();
            });
  }

  private void populateQueue() {
    if (controller == null) return;

    List<MediaItem> items = new ArrayList<>();
    for (int i = 0; i < controller.getMediaItemCount(); i++) {
      items.add(controller.getMediaItemAt(i));
    }

    int current = controller.getCurrentMediaItemIndex();
    adapter.setItems(items, current);
    updateCount();

    // Scroll to currently playing item
    RecyclerView rv = requireView().findViewById(R.id.rvQueue);
    if (rv.getLayoutManager() != null) {
      rv.getLayoutManager().scrollToPosition(current);
    }
  }

  private void attachListener() {
    playerListener = new Player.Listener() {
      @Override
      public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
        if (controller == null) return;
        adapter.updateCurrentIndex(controller.getCurrentMediaItemIndex());
      }

      @Override
      public void onTimelineChanged(
              androidx.media3.common.Timeline timeline, int reason) {
        if (controller == null) return;
        populateQueue();
      }
    };
    controller.addListener(playerListener);
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    if (controller != null && playerListener != null) {
      controller.removeListener(playerListener); // ← clean detach
    }
    playerListener = null;
    controller = null;
  }

  private void updateCount() {
    if (controller == null || tvQueueCount == null) return;
    int count = controller.getMediaItemCount();
    tvQueueCount.setText(count + (count == 1 ? " song" : " songs"));
  }

  private List<String> getCurrentQueueIds() {
    List<String> ids = new ArrayList<>();
    if (controller == null) return ids;
    for (int i = 0; i < controller.getMediaItemCount(); i++) {
      ids.add(controller.getMediaItemAt(i).mediaId);
    }
    return ids;
  }

  private void showSaveQueueSheet(boolean isAppend) {
    List<String> ids = getCurrentQueueIds();
    if (ids.isEmpty()) {
      Toast.makeText(requireContext(), "Queue is empty", Toast.LENGTH_SHORT).show();
      return;
    }

    PlaylistManager pm = PlaylistManager.get(requireContext());
    Map<String, List<String>> all = pm.getAllPlaylists();

    // Build a scrollable list of existing playlists + "New playlist" option
    LinearLayout layout = new LinearLayout(requireContext());
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(0, 8, 0, 8);

    ScrollView scroll = new ScrollView(requireContext());
    scroll.addView(layout);

    // "New playlist" row always at top
    android.widget.Button btnNew = new android.widget.Button(requireContext());
    btnNew.setText("+ New playlist");
    btnNew.setBackgroundResource(android.R.color.transparent);
    layout.addView(btnNew);

    // Existing playlists
    for (String name : all.keySet()) {
      android.widget.TextView row = new android.widget.TextView(requireContext());
      row.setText(name);
      row.setTextSize(16);
      row.setPadding(48, 32, 48, 32);
      row.setOnClickListener(v -> {
        pm.appendSongsToPlaylist(name, ids);
        int added = ids.size();
        Toast.makeText(requireContext(),
                added + " songs added to \"" + name + "\"",
                Toast.LENGTH_SHORT).show();
      });
      layout.addView(row);
    }

    Dialog dialog = new ResonanceDialog.Builder(requireContext())
            .setTitle(isAppend ? "Append queue to…" : "Save queue to…")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .show();

    btnNew.setOnClickListener(v -> {
      dialog.dismiss();
      showCreateAndSaveDialog(ids);
    });
  }

  private void showCreateAndSaveDialog(List<String> ids) {
    EditText input = new EditText(requireContext());
    input.setHint("Playlist name");

    new ResonanceDialog.Builder(requireContext())
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create", (d, w) -> {
              String name = input.getText().toString().trim();
              if (name.isEmpty()) return;
              PlaylistManager pm = PlaylistManager.get(requireContext());
              pm.createPlaylist(name);
              pm.appendSongsToPlaylist(name, ids);
              Toast.makeText(requireContext(),
                      "Saved " + ids.size() + " songs to \"" + name + "\"",
                      Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
  }

  private void handleTempSession() {
    if (QueueManager.get().isTempSession()) {
      // Already in a temp session — offer to end it
      new ResonanceDialog.Builder(requireContext())
              .setTitle("Temp Session Active")
              .setMessage("End the temp session? The queue will be cleared when you leave.")
              .setPositiveButton("End Session", (d, w) -> {
                QueueManager.get().endTempSession(controller);
                updateTempSessionButton();
                Toast.makeText(requireContext(),
                        "Temp session ended", Toast.LENGTH_SHORT).show();
              })
              .setNegativeButton("Keep", null)
              .show();
    } else {
      // Start a temp session with the current queue contents
      List<MediaItem> currentItems = new ArrayList<>();
      if (controller != null) {
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
          currentItems.add(controller.getMediaItemAt(i));
        }
      }
      if (currentItems.isEmpty()) {
        Toast.makeText(requireContext(),
                "Queue is empty", Toast.LENGTH_SHORT).show();
        return;
      }
      QueueManager.get().startTempSession(controller, currentItems);
      updateTempSessionButton();
      Toast.makeText(requireContext(),
              "Temp session started — queue clears when playback ends",
              Toast.LENGTH_SHORT).show();
    }
  }

  private void updateTempSessionButton() {
    if (getView() == null) return;
    boolean active = QueueManager.get().isTempSession();

    ImageView icon = getView().findViewById(R.id.ivTempSessionIcon);
    TextView label = getView().findViewById(R.id.tvTempSessionLabel);

    if (icon != null) icon.setColorFilter(active
            ? 0xFFC7A1A9   // your accent color — matches ResonanceDialog
            : 0xFFFFFFFF); // text_primary white

    if (label != null) {
      label.setText(active ? "Temp (on)" : "Temp");
      label.setTextColor(active ? 0xFFC7A1A9 : 0xFF8D8F9C);
    }
  }
}