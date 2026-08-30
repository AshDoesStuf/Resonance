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
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import me.ash.resonance.R;
import me.ash.resonance.album.AlbumDetailActivity;
import me.ash.resonance.artist.ArtistDetailActivity;
import me.ash.resonance.playlist.PlaylistManager;
import me.ash.resonance.playlist.PlaylistPickerSheet;
import me.ash.resonance.queue.QueueManager;
import me.ash.resonance.songs.SongActionMenu;
import me.ash.resonance.songs.SongContext;
import me.ash.resonance.songs.SongRowBinder;
import me.ash.resonance.ui.ResonanceDialog;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {

  private final List<Song> songs = new ArrayList<>();
  private final OnSongClickListener listener;
  private final java.util.Set<String> selectedIds = new java.util.HashSet<>();
  // Existing action callbacks
  public java.util.function.Consumer<Song> onPlayNext;
  public java.util.function.Consumer<Song> onAddToQueue;
  // New navigation callbacks
  public java.util.function.Consumer<Song> onGoToArtist;
  public java.util.function.Consumer<Song> onGoToAlbum;
  private androidx.media3.session.MediaController controller;
  private boolean selectionMode = false;
  private OnSelectionChangedListener selectionListener;
  private int playingIndex = -1;
  private Context holderContext;
  private final SongActionMenu.ActionHandler actionHandler = new SongActionMenu.ActionHandler() {
    @Override
    public void onPlayNext(MediaItem song) {
      if (controller != null) QueueManager.get().playNext(controller, song);
    }

    @Override
    public void onAddToQueue(MediaItem song) {
      if (controller != null) QueueManager.get().addToQueue(controller, song);
    }

    @Override
    public void onAddToPlaylist(MediaItem song) {
      if (song.mediaId == null) return;
      Context ctx = holderContext;
      if (ctx instanceof androidx.fragment.app.FragmentActivity) {
        PlaylistPickerSheet.newInstance(song.mediaId)
                .show(((androidx.fragment.app.FragmentActivity) ctx).getSupportFragmentManager(),
                        PlaylistPickerSheet.TAG);
      }
    }

    @Override
    public void onGoToArtist(MediaItem song) {
      String artistName = song.mediaMetadata.artist != null ?
              song.mediaMetadata.artist.toString() : "Unknown";
      Context ctx = holderContext;
      ctx.startActivity(ArtistDetailActivity.createIntent(ctx, null, artistName, null));
    }

    @Override
    public void onGoToAlbum(MediaItem song) {
      String albumTitle = song.mediaMetadata.albumTitle != null ?
              song.mediaMetadata.albumTitle.toString() : "Unknown";
      String artistName = song.mediaMetadata.artist != null ?
              song.mediaMetadata.artist.toString() : "Unknown";
      String artUrl = song.mediaMetadata.artworkUri != null ?
              song.mediaMetadata.artworkUri.toString() : null;

      Context ctx = holderContext;
      ctx.startActivity(AlbumDetailActivity.createIntent(ctx, null, albumTitle, artistName, artUrl));
    }

    @Override
    public void onShare(MediaItem song) {
      String title = song.mediaMetadata.title != null ? song.mediaMetadata.title.toString() : "Unknown";
      String artist = song.mediaMetadata.artist != null ? song.mediaMetadata.artist.toString() : "Unknown";
      String shareText = "Listening to " + title + " by " + artist;

      android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
      intent.setType("text/plain");
      intent.putExtra(android.content.Intent.EXTRA_TEXT, shareText);
      Context ctx = holderContext;
      ctx.startActivity(android.content.Intent.createChooser(intent, "Share via"));
    }

    @Override
    public void onRemoveFromPlaylist(MediaItem song, String playlistId) {
      // Library context doesn't trigger this
    }

    @Override
    public void onRemoveFromQueue(MediaItem song, int index) {
      // Library context doesn't trigger this
    }

    @Override
    public void onRemoveDownload(MediaItem song) {
      // Library context doesn't trigger this
    }
  };

  public SongAdapter(OnSongClickListener listener) {
    this.listener = listener;
  }

  public void setController(androidx.media3.session.MediaController controller) {
    this.controller = controller;
  }

  public void setSelectionMode(boolean enabled) {
    this.selectionMode = enabled;
    if (!enabled) selectedIds.clear();
    notifyDataSetChanged();
  }

  public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
    this.selectionListener = listener;
  }

  public void toggleSelection(String songId) {
    if (selectedIds.contains(songId)) {
      selectedIds.remove(songId);
    } else {
      selectedIds.add(songId);
    }
    notifyDataSetChanged();
    if (selectionListener != null) selectionListener.onSelectionChanged(selectedIds.size());
  }

  public void selectAll(List<Song> allSongs) {
    for (Song s : allSongs) selectedIds.add(s.id);
    notifyDataSetChanged();
    if (selectionListener != null) selectionListener.onSelectionChanged(selectedIds.size());
  }

  public void deselectAll() {
    selectedIds.clear();
    notifyDataSetChanged();
    if (selectionListener != null) selectionListener.onSelectionChanged(selectedIds.size());
  }

  public void deselectSongs(List<Song> songList) {
    for (Song s : songList) selectedIds.remove(s.id);
    notifyDataSetChanged();
    if (selectionListener != null) selectionListener.onSelectionChanged(selectedIds.size());
  }

  public java.util.Set<String> getSelectedIds() {
    return selectedIds;
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
    boolean isPlaying = (position == playingIndex);
    this.holderContext = holder.itemView.getContext();

    MediaMetadata metadata = new MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(song.albumArtUri)
            .build();
    MediaItem item = new MediaItem.Builder()
            .setUri(song.uri)
            .setMediaId(String.valueOf(song.id))
            .setMediaMetadata(metadata)
            .build();

    SongRowBinder.Views v = new SongRowBinder.Views();
    v.ivArt = holder.ivAlbumArt;
    v.ivPlaying = null; // item_song.xml doesn't have an equalizer view
    v.tvTitle = holder.tvSongTitle;
    v.tvArtist = holder.tvArtist;
    v.tvDuration = holder.tvDuration;
    if (!selectionMode) v.cbSelect = null;
    else v.cbSelect = holder.cbSelect;

    SongRowBinder.bind(v, item, isPlaying, false, null, song.duration, selectedIds.contains(song.id));
    int accentColor = androidx.core.content.ContextCompat.getColor(holderContext, R.color.accent);
    holder.tvSongTitle.setTextColor(isPlaying ? accentColor : holder.defaultTitleColor);

    if (selectionMode) {
      holder.btnMore.setVisibility(View.GONE);
      holder.itemView.setOnClickListener(vClick -> toggleSelection(song.id));
    } else {
      holder.btnMore.setVisibility(View.VISIBLE);
      holder.itemView.setOnClickListener(vClick -> listener.onSongClick(song));
      holder.btnMore.setOnClickListener(vMenu ->
              SongActionMenu.show(holder.itemView.getContext(), item, SongContext.library(), actionHandler));
    }
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

  public interface OnSelectionChangedListener {
    void onSelectionChanged(int count);
  }

  public static class SongViewHolder extends RecyclerView.ViewHolder {
    final int defaultTitleColor;
    TextView tvSongTitle, tvArtist, tvDuration;
    ImageView ivAlbumArt;
    ImageButton btnMore;
    android.widget.CheckBox cbSelect;

    public SongViewHolder(@NonNull View itemView) {
      super(itemView);
      tvSongTitle = itemView.findViewById(R.id.tvSongTitle);
      tvArtist = itemView.findViewById(R.id.tvArtist);
      tvDuration = itemView.findViewById(R.id.tvDuration);
      ivAlbumArt = itemView.findViewById(R.id.ivAlbumArt);
      btnMore = itemView.findViewById(R.id.btnMore);
      cbSelect = itemView.findViewById(R.id.cbSelect);
      defaultTitleColor = tvSongTitle.getCurrentTextColor();
    }
  }
}