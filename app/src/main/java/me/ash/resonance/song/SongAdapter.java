package me.ash.resonance.song;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import me.ash.resonance.R;
import me.ash.resonance.playlist.PlaylistManager;
import me.ash.resonance.ui.ResonanceDialog;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {

  private final List<Song> songs = new ArrayList<>();
  private final OnSongClickListener listener;

  // Existing action callbacks
  public java.util.function.Consumer<Song> onPlayNext;
  public java.util.function.Consumer<Song> onAddToQueue;

  // New navigation callbacks
  public java.util.function.Consumer<Song> onGoToArtist;
  public java.util.function.Consumer<Song> onGoToAlbum;

  private int playingIndex = -1;

  public SongAdapter(OnSongClickListener listener) {
    this.listener = listener;
  }

  @NonNull
  @Override
  public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_song, parent, false);
    return new SongViewHolder(view);
  }

  @Override
  public void onBindViewHolder(@NonNull SongViewHolder holder, int position) {
    Song song = songs.get(position);
    holder.tvSongTitle.setText(song.title);
    holder.tvArtist.setText(song.artist);
    holder.tvDuration.setText(song.duration);

    boolean isPlaying = (position == playingIndex);
    holder.tvSongTitle.setTextColor(isPlaying
            ? ContextCompat.getColor(holder.itemView.getContext(), R.color.accent)
            : ContextCompat.getColor(holder.itemView.getContext(), R.color.text_primary));
    holder.tvArtist.setAlpha(isPlaying ? 1f : 0.6f);

    Glide.with(holder.itemView.getContext())
            .load(song.albumArtUri)
            .apply(new RequestOptions()
                    .placeholder(R.drawable.ic_note_outlined)
                    .error(R.drawable.ic_note_outlined)
                    .transform(new RoundedCorners(16)))
            .into(holder.ivAlbumArt);

    holder.itemView.setOnClickListener(v -> listener.onSongClick(song));
    holder.btnMore.setOnClickListener(v -> showSongMenu(holder.itemView.getContext(), song));
  }

  public void setPlayingIndex(int index) {
    int old = playingIndex;
    playingIndex = index;
    if (old >= 0) notifyItemChanged(old);
    if (index >= 0) notifyItemChanged(index);
  }

  private void showSongMenu(Context ctx, Song song) {
    String mediaId = String.valueOf(song.id);

    // Build options dynamically so we can conditionally include navigation items
    String[] options = {
            "Play Next",
            "Add to Queue",
            "Add to Playlist",
            "Favourite",
            "Go to Artist",
            "Go to Album"
    };

    new ResonanceDialog.Builder(ctx)
            .setTitle(song.title)
            .setItems(options, (dialog, which) -> {
              switch (which) {
                case 0: // Play Next
                  if (onPlayNext != null) onPlayNext.accept(song);
                  break;
                case 1: // Add to Queue
                  if (onAddToQueue != null) onAddToQueue.accept(song);
                  break;
                case 2: // Add to Playlist
                  if (ctx instanceof androidx.fragment.app.FragmentActivity) {
                    me.ash.resonance.playlist.PlaylistPickerSheet
                            .newInstance(mediaId)
                            .show(((androidx.fragment.app.FragmentActivity) ctx)
                                            .getSupportFragmentManager(),
                                    me.ash.resonance.playlist.PlaylistPickerSheet.TAG);
                  }
                  break;
                case 3: // Favourite toggle
                  boolean nowFav = PlaylistManager.get(ctx).toggleFavourite(mediaId);
                  Toast.makeText(ctx,
                          nowFav ? "Added to Liked Songs" : "Removed from Liked Songs",
                          Toast.LENGTH_SHORT).show();
                  break;
                case 4: // Go to Artist
                  if (onGoToArtist != null) onGoToArtist.accept(song);
                  break;
                case 5: // Go to Album
                  if (onGoToAlbum != null) onGoToAlbum.accept(song);
                  break;
              }
            })
            .show();
  }

  @Override
  public int getItemCount() {
    return songs.size();
  }

  public void update(List<Song> newSongs) {
    List<Song> oldSongs = new ArrayList<>(songs);
    List<Song> incoming = newSongs == null
            ? Collections.emptyList()
            : new ArrayList<>(newSongs);

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
      public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
        return oldSongs.get(oldItemPosition).id == incoming.get(newItemPosition).id;
      }

      @Override
      public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        Song oldSong = oldSongs.get(oldItemPosition);
        Song newSong = incoming.get(newItemPosition);
        return Objects.equals(oldSong.title, newSong.title)
                && Objects.equals(oldSong.artist, newSong.artist)
                && Objects.equals(oldSong.duration, newSong.duration)
                && Objects.equals(oldSong.uri, newSong.uri)
                && Objects.equals(oldSong.albumArtUri, newSong.albumArtUri);
      }
    });

    songs.clear();
    songs.addAll(incoming);
    if (playingIndex >= songs.size()) {
      playingIndex = -1;
    }
    diff.dispatchUpdatesTo(this);
  }

  public interface OnSongClickListener {
    void onSongClick(Song song);
  }

  public static class SongViewHolder extends RecyclerView.ViewHolder {
    TextView tvSongTitle, tvArtist, tvDuration;
    ImageView ivAlbumArt;
    ImageButton btnMore;

    public SongViewHolder(@NonNull View itemView) {
      super(itemView);
      tvSongTitle = itemView.findViewById(R.id.tvSongTitle);
      tvArtist = itemView.findViewById(R.id.tvArtist);
      tvDuration = itemView.findViewById(R.id.tvDuration);
      ivAlbumArt = itemView.findViewById(R.id.ivAlbumArt);
      btnMore = itemView.findViewById(R.id.btnMore);
    }
  }
}