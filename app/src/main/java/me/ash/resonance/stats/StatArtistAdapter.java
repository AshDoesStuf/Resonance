package me.ash.resonance.stats;


import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Map;

import me.ash.resonance.R;

public class StatArtistAdapter extends RecyclerView.Adapter<StatArtistAdapter.VH> {

  private final List<Map.Entry<String, Integer>> entries;

  public StatArtistAdapter(List<Map.Entry<String, Integer>> entries) {
    this.entries = entries;
  }

  @NonNull
  @Override
  public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_stat_artist, parent, false);
    return new VH(v);
  }

  @Override
  public void onBindViewHolder(@NonNull VH h, int position) {
    Map.Entry<String, Integer> e = entries.get(position);
    h.rank.setText(String.valueOf(position + 1));
    h.name.setText(e.getKey());
    h.plays.setText(e.getValue() + " plays");
  }

  @Override
  public int getItemCount() {
    return entries.size();
  }

  static class VH extends RecyclerView.ViewHolder {
    TextView rank, name, plays;

    VH(View v) {
      super(v);
      rank = v.findViewById(R.id.tvRank);
      name = v.findViewById(R.id.tvArtistName);
      plays = v.findViewById(R.id.tvArtistPlays);
    }
  }
}