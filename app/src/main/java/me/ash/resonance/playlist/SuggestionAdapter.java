package me.ash.resonance.playlist;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.ash.resonance.R;
import me.ash.resonance.yt.YtTrack;

public class SuggestionAdapter extends RecyclerView.Adapter<SuggestionAdapter.ViewHolder> {

  private final List<YtTrack> tracks = new ArrayList<>();
  private final Set<String> selectedIds = new HashSet<>();
  private final OnSelectionChangedListener selectionListener;

  public SuggestionAdapter(OnSelectionChangedListener selectionListener) {
    this.selectionListener = selectionListener;
  }

  public void setTracks(List<YtTrack> newTracks) {
    tracks.clear();
    tracks.addAll(newTracks);
    selectedIds.clear();
    notifyDataSetChanged();
  }

  // ── Public API ────────────────────────────────────────────────────────────

  public List<YtTrack> getSelectedTracks() {
    List<YtTrack> result = new ArrayList<>();
    for (YtTrack t : tracks) {
      if (selectedIds.contains(t.videoId)) result.add(t);
    }
    return result;
  }

  public int getSelectedCount() {
    return selectedIds.size();
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_suggestion, parent, false);
    return new ViewHolder(v);
  }

  // ── Adapter ───────────────────────────────────────────────────────────────

  @Override
  public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
    YtTrack track = tracks.get(position);
    boolean selected = selectedIds.contains(track.videoId);

    holder.tvTitle.setText(track.title != null ? track.title : "");
    holder.tvArtist.setText(track.artist != null ? track.artist : "");
    holder.cbSelected.setChecked(selected);
    holder.itemView.setAlpha(selected ? 1.0f : 0.75f);

    if (track.thumbnailUrl != null && !track.thumbnailUrl.isEmpty()) {
      Glide.with(holder.ivThumb.getContext())
              .load(track.thumbnailUrl)
              .placeholder(R.drawable.ic_music_note)
              .centerCrop()
              .into(holder.ivThumb);
    } else {
      holder.ivThumb.setImageResource(R.drawable.ic_music_note);
    }

    // Row click is the only thing that drives selection state.
    // The CheckBox itself is clickable=false so there's no double-fire.
    holder.itemView.setOnClickListener(v -> {
      // Use getBindingAdapterPosition() to guard against stale positions
      int pos = holder.getBindingAdapterPosition();
      if (pos == RecyclerView.NO_ID) return;

      if (selectedIds.contains(track.videoId)) {
        selectedIds.remove(track.videoId);
      } else {
        selectedIds.add(track.videoId);
      }
      notifyItemChanged(pos);

      if (selectionListener != null) {
        selectionListener.onSelectionChanged(selectedIds.size());
      }
    });
  }

  @Override
  public int getItemCount() {
    return tracks.size();
  }

  public interface OnSelectionChangedListener {
    void onSelectionChanged(int selectedCount);
  }

  // ── ViewHolder ────────────────────────────────────────────────────────────

  static class ViewHolder extends RecyclerView.ViewHolder {
    final ImageView ivThumb;
    final TextView tvTitle;
    final TextView tvArtist;
    final CheckBox cbSelected;

    ViewHolder(@NonNull View itemView) {
      super(itemView);
      ivThumb = itemView.findViewById(R.id.ivSuggThumb);
      tvTitle = itemView.findViewById(R.id.tvSuggTitle);
      tvArtist = itemView.findViewById(R.id.tvSuggArtist);
      cbSelected = itemView.findViewById(R.id.cbSuggSelected);
    }
  }
}