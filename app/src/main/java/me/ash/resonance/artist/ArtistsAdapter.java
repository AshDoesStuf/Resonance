package me.ash.resonance.artist;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import me.ash.resonance.R;

public class ArtistsAdapter extends RecyclerView.Adapter<ArtistsAdapter.VH> {

  private final Context context;
  private final List<Artist> artists = new ArrayList<>();
  private final OnArtistClickListener listener;

  public ArtistsAdapter(Context context, List<Artist> initialArtists, OnArtistClickListener listener) {
    this.context = context;
    if (initialArtists != null) {
      this.artists.addAll(initialArtists);
    }
    this.listener = listener;
  }

  @NonNull
  @Override
  public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View v = LayoutInflater.from(context).inflate(R.layout.item_artist, parent, false);
    return new VH(v);
  }

  @Override
  public void onBindViewHolder(@NonNull VH holder, int position) {
    Artist artist = artists.get(position);

    holder.tvArtistName.setText(artist.name());
    holder.tvArtistSongCount.setText(
            artist.songCount() == 1 ? "1 song" : artist.songCount() + " songs");

    // First letter avatar (fall back to "?" for unknown/blank artists)
    String name = artist.name() != null ? artist.name().trim() : "";
    holder.tvArtistInitial.setText(
            (!name.isEmpty() && !name.equals("<unknown>"))
                    ? String.valueOf(Character.toUpperCase(name.charAt(0)))
                    : "?");

    holder.itemView.setOnClickListener(v -> listener.onArtistClick(artist));
  }

  @Override
  public int getItemCount() {
    return artists.size();
  }

  public void update(List<Artist> newArtists) {
    List<Artist> oldList = new ArrayList<>(this.artists);
    List<Artist> newList = newArtists == null ? Collections.emptyList() : new ArrayList<>(newArtists);

    DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
      @Override
      public int getOldListSize() {
        return oldList.size();
      }

      @Override
      public int getNewListSize() {
        return newList.size();
      }

      @Override
      public boolean areItemsTheSame(int oldPos, int newPos) {
        return Objects.equals(oldList.get(oldPos).name(), newList.get(newPos).name());
      }

      @Override
      public boolean areContentsTheSame(int oldPos, int newPos) {
        Artist a = oldList.get(oldPos);
        Artist b = newList.get(newPos);
        return Objects.equals(a.name(), b.name()) && a.songCount() == b.songCount();
      }
    });

    this.artists.clear();
    this.artists.addAll(newList);
    diff.dispatchUpdatesTo(this);
  }

  public interface OnArtistClickListener {
    void onArtistClick(Artist artist);
  }

  static class VH extends RecyclerView.ViewHolder {
    TextView tvArtistInitial;
    TextView tvArtistName;
    TextView tvArtistSongCount;

    VH(@NonNull View itemView) {
      super(itemView);
      tvArtistInitial = itemView.findViewById(R.id.tvArtistInitial);
      tvArtistName = itemView.findViewById(R.id.tvArtistName);
      tvArtistSongCount = itemView.findViewById(R.id.tvArtistSongCount);
    }
  }
}