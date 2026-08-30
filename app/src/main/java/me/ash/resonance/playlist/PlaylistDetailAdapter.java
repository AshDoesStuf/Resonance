package me.ash.resonance.playlist;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import me.ash.resonance.R;
import me.ash.resonance.album.AlbumDetailActivity;
import me.ash.resonance.artist.ArtistDetailActivity;
import me.ash.resonance.queue.QueueManager;
import me.ash.resonance.song.Song;
import me.ash.resonance.songs.SongActionMenu;
import me.ash.resonance.songs.SongContext;
import me.ash.resonance.songs.SongRowBinder;

public class PlaylistDetailAdapter extends RecyclerView.Adapter<PlaylistDetailAdapter.VH> {

  private static final String LIKED = "__liked__";

  private final List<Song> songs;
  private final OnSongClick onPlay, onRemove;
  private String playlistName;
  private androidx.media3.session.MediaController controller;
  private int playingIdx = -1;

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
      if (hContext instanceof androidx.fragment.app.FragmentActivity) {
        PlaylistPickerSheet.newInstance(song.mediaId)
                .show(((androidx.fragment.app.FragmentActivity) hContext).getSupportFragmentManager(),
                        PlaylistPickerSheet.TAG);
      }
    }

    @Override
    public void onGoToArtist(MediaItem song) {
      String name = song.mediaMetadata.artist != null ? song.mediaMetadata.artist.toString() : "Unknown";
      hContext.startActivity(ArtistDetailActivity.createIntent(hContext, null, name, null));
    }

    @Override
    public void onGoToAlbum(MediaItem song) {
      String albumTitle = song.mediaMetadata.albumTitle != null ? song.mediaMetadata.albumTitle.toString() : "Unknown";
      String artistName = song.mediaMetadata.artist != null ? song.mediaMetadata.artist.toString() : "Unknown";
      String artUrl = song.mediaMetadata.artworkUri != null ? song.mediaMetadata.artworkUri.toString() : null;

      hContext.startActivity(AlbumDetailActivity.createIntent(hContext, null, albumTitle, artistName, artUrl));
    }

    @Override
    public void onShare(MediaItem song) {
      String title = song.mediaMetadata.title != null ? song.mediaMetadata.title.toString() : "Unknown";
      String artist = song.mediaMetadata.artist != null ? song.mediaMetadata.artist.toString() : "Unknown";
      String shareText = "Listening to " + title + " by " + artist;
      android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
      intent.setType("text/plain");
      intent.putExtra(android.content.Intent.EXTRA_TEXT, shareText);
      hContext.startActivity(android.content.Intent.createChooser(intent, "Share via"));
    }

    @Override
    public void onRemoveFromPlaylist(MediaItem mediaItem, String playlistId) {
      // Find the song object to pass to the activity's onRemove callback
      for (Song s : songs) {
        if (s.id.equals(mediaItem.mediaId)) {
          onRemove.on(s);
          break;
        }
      }
    }

    @Override
    public void onRemoveFromQueue(MediaItem song, int index) {}

    @Override
    public void onRemoveDownload(MediaItem song) {}
  };

  private android.content.Context hContext;

  public PlaylistDetailAdapter(List<Song> songs, OnSongClick onPlay, OnSongClick onRemove) {
    this.songs = songs;
    this.onPlay = onPlay;
    this.onRemove = onRemove;
  }

  public void setPlaylistName(String name) {
    this.playlistName = name;
  }

  public void setController(androidx.media3.session.MediaController controller) {
    this.controller = controller;
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
    this.hContext = h.itemView.getContext();
    boolean isPlaying = (position == playingIdx);

    MediaMetadata metadata = new MediaMetadata.Builder()
            .setTitle(s.title)
            .setArtist(s.artist)
            .setAlbumTitle(s.album)
            .setArtworkUri(s.albumArtUri)
            .build();
    MediaItem item = new MediaItem.Builder()
            .setUri(s.uri)
            .setMediaId(s.id)
            .setMediaMetadata(metadata)
            .build();

    SongRowBinder.Views v = new SongRowBinder.Views();
    v.ivArt = h.ivArt;
    v.ivPlaying = null;
    v.tvTitle = h.tvTitle;
    v.tvArtist = h.tvArtist;
    v.tvDuration = null;
    v.cbSelect = null;

    SongRowBinder.bind(v, item, isPlaying, false, null, null, null);

    h.itemView.setOnClickListener(vClick -> onPlay.on(s));
    h.btnOverflow.setOnClickListener(vMenu ->
            SongActionMenu.show(hContext, item, SongContext.playlist(playlistName), actionHandler));
  }

  @Override
  public int getItemCount() {
    return songs.size();
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

  public List<Song> getSongs() {
    return songs;
  }

  public interface OnSongClick {
    void on(Song song);
  }

  static class VH extends RecyclerView.ViewHolder {
    ImageView ivArt;
    TextView tvTitle, tvArtist;
    ImageButton btnOverflow;

    VH(@NonNull View v) {
      super(v);
      ivArt = v.findViewById(R.id.ivPlaylistSongArt);
      tvTitle = v.findViewById(R.id.tvPlaylistSongTitle);
      tvArtist = v.findViewById(R.id.tvPlaylistSongArtist);
      btnOverflow = v.findViewById(R.id.ivPlaylistSongOverflow);
    }
  }
}