package me.ash.resonance.stats;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;
import java.util.Map;

import me.ash.resonance.R;
import me.ash.resonance.song.Song;

public class StatSongAdapter extends RecyclerView.Adapter<StatSongAdapter.VH> {

  private final List<String> ids;
  private final Map<String, Song> songMap;
  private final Map<String, Integer> playCounts;
  private final boolean showPlayCount; // true = "5 plays", false = duration

  public StatSongAdapter(List<String> ids, Map<String, Song> songMap, Map<String, Integer> playCounts, boolean showPlayCount) {
    this.ids = ids;
    this.songMap = songMap;
    this.playCounts = playCounts;
    this.showPlayCount = showPlayCount;
  }

  @NonNull
  @Override
  public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_stat_song, parent, false);
    return new VH(v);
  }

  @Override
  public void onBindViewHolder(@NonNull VH h, int position) {
    String id = ids.get(position);
    Song song = songMap.get(id);

    if (song == null) {
      h.title.setText("Unknown");
      h.artist.setText("");
      h.meta.setText("");
      return;
    }

    h.title.setText(song.title);
    h.artist.setText(song.artist);

    if (showPlayCount && playCounts != null) {
      Integer count = playCounts.get(id);
      h.meta.setText((count != null ? count : 0) + " plays");
    } else {
      h.meta.setText(song.duration);
    }

    if (song.albumArtUri != null) {
      Glide.with(h.albumArt.getContext())
              .load(song.albumArtUri)
              .placeholder(R.drawable.ic_music_note) // use whatever placeholder you have
              .into(h.albumArt);
    }
  }

  @Override
  public int getItemCount() {
    return ids.size();
  }

  static class VH extends RecyclerView.ViewHolder {
    ImageView albumArt;
    TextView title, artist, meta;

    VH(View v) {
      super(v);
      albumArt = v.findViewById(R.id.ivAlbumArt);
      title = v.findViewById(R.id.tvSongTitle);
      artist = v.findViewById(R.id.tvSongArtist);
      meta = v.findViewById(R.id.tvSongMeta);
    }
  }
}