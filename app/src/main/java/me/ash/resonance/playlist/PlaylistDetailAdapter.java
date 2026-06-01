package me.ash.resonance.playlist;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import me.ash.resonance.R;
import me.ash.resonance.song.Song;

public class PlaylistDetailAdapter extends RecyclerView.Adapter<PlaylistDetailAdapter.VH> {

  private final List<Song> songs;
  private final OnSongClick onPlay, onRemove;
  private OnDragStartListener dragListener;
  private int playingIdx = -1;

  public PlaylistDetailAdapter(List<Song> songs, OnSongClick onPlay, OnSongClick onRemove) {
    this.songs = songs;
    this.onPlay = onPlay;
    this.onRemove = onRemove;
  }

  public int getPlayingIdx() {
    return playingIdx;
  }

  public void setPlayingIdx(int index) {
    int old = playingIdx;
    playingIdx = index;
    if (old >= 0) notifyItemChanged(old);
    if (index >= 0) notifyItemChanged(index);
  }

  @NonNull
  @Override
  public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_playlist_song, parent, false);
    return new VH(v);
  }

  @SuppressLint({"NotifyDataSetChanged", "ClickableViewAccessibility"})
  @Override
  public void onBindViewHolder(@NonNull VH h, int position) {
    Song s = songs.get(position);
    h.tvTitle.setText(s.title);
    h.tvArtist.setText(s.artist);

    boolean isPlaying = (position == playingIdx);
    h.tvTitle.setTextColor(isPlaying
            ? ContextCompat.getColor(h.itemView.getContext(), R.color.accent)
            : ContextCompat.getColor(h.itemView.getContext(), R.color.text_primary));

    Glide.with(h.itemView.getContext())
            .load(s.albumArtUri)
            .apply(new RequestOptions()
                    .placeholder(R.drawable.music_note_24px)
                    .error(R.drawable.music_note_24px)
                    .transform(new RoundedCorners(16)))
            .into(h.ivArt);
    h.itemView.setOnClickListener(v -> onPlay.on(s));
    h.btnRemove.setOnClickListener(v -> onRemove.on(s));
    if (h.ivDragHandle != null && dragListener != null) {
      h.ivDragHandle.setOnTouchListener((v, event) -> {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
          dragListener.onDragStart(h);
        }
        return false;
      });
    }
  }

  @Override
  public int getItemCount() {
    return songs.size();
  }

  public void setDragListener(OnDragStartListener l) {
    this.dragListener = l;
  }

  public void updateSongs(List<Song> newSongs) {
    List<Song> oldSongs = new ArrayList<>(songs);
    List<Song> incoming = newSongs != null ? newSongs : new ArrayList<>();

    DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
      @Override
      public int getOldListSize() {
        return oldSongs.size();
      }

      @Override
      public int getNewListSize() {
        return incoming.size();
      }

      @Override
      public boolean areItemsTheSame(int oldPos, int newPos) {
        return oldSongs.get(oldPos).id == incoming.get(newPos).id;
      }

      @Override
      public boolean areContentsTheSame(int oldPos, int newPos) {
        Song a = oldSongs.get(oldPos);
        Song b = incoming.get(newPos);
        return Objects.equals(a.title, b.title)
                && Objects.equals(a.artist, b.artist)
                && Objects.equals(a.duration, b.duration)
                && Objects.equals(a.albumArtUri, b.albumArtUri);
      }
    });

    songs.clear();
    songs.addAll(incoming);
    if (playingIdx >= songs.size()) {
      playingIdx = -1;
    }
    diff.dispatchUpdatesTo(this);
  }

  public interface OnSongClick {
    void on(Song song);
  }

  public interface OnDragStartListener {
    void onDragStart(RecyclerView.ViewHolder holder);
  }

  static class VH extends RecyclerView.ViewHolder {
    ImageView ivArt;
    TextView tvTitle, tvArtist;
    ImageButton btnRemove;
    ImageView ivDragHandle;

    VH(@NonNull View v) {
      super(v);
      ivArt = v.findViewById(R.id.ivPlaylistSongArt);
      tvTitle = v.findViewById(R.id.tvPlaylistSongTitle);
      tvArtist = v.findViewById(R.id.tvPlaylistSongArtist);
      btnRemove = v.findViewById(R.id.btnRemoveFromPlaylist);
      ivDragHandle = v.findViewById(R.id.ivDragHandle);
    }
  }
}