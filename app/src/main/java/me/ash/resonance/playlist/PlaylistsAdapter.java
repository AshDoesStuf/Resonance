package me.ash.resonance.playlist;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.ash.resonance.MusicLoader;
import me.ash.resonance.R;
import me.ash.resonance.song.Song;

public class PlaylistsAdapter extends RecyclerView.Adapter<PlaylistsAdapter.VH> {

  public static final int LIST = 0;
  public static final int GRID = 1;
  private static final String LIKED = "__liked__";
  private final Context ctx;
  private final OnPlaylistClickListener listener;
  private final List<String> names = new ArrayList<>();
  private final Map<String, Integer> playlistCounts = new HashMap<>();
  private final Map<String, Song> playlistFirstSongs = new HashMap<>();
  private OnPlaylistLongClickListener longListener;
  private int viewMode = LIST;

  public PlaylistsAdapter(Context ctx, OnPlaylistClickListener listener) {
    this.ctx = ctx;
    this.listener = listener;
    refresh();
  }

  public void setLongClickListener(OnPlaylistLongClickListener l) {
    this.longListener = l;
  }

  /**
   * Switch between PlaylistsAdapter.LIST and PlaylistsAdapter.GRID
   */
  @SuppressLint("NotifyDataSetChanged")
  public void setViewMode(int mode) {
    this.viewMode = mode;
    notifyDataSetChanged();
  }

  @SuppressLint("NotifyDataSetChanged")
  public void refresh() {
    new Thread(() -> {
      List<String> newNames = new ArrayList<>();
      newNames.add(LIKED);
      newNames.add(PlaylistManager.SMART_MOST_PLAYED);
      newNames.add(PlaylistManager.SMART_RECENTLY_PLAYED);
      newNames.add(PlaylistManager.SMART_RECENTLY_ADDED);
      newNames.add(PlaylistManager.SMART_DOWNLOADS);

      Map<String, Integer> newCounts = new HashMap<>();
      Map<String, Song> newFirstSongs = new HashMap<>();

      // Fetch the playlists map exactly once
      Map<String, List<String>> allPlaylists = PlaylistManager.get(ctx).getAllPlaylists();
      newNames.addAll(allPlaylists.keySet());

      // Cache liked count
      int likedCount = PlaylistManager.get(ctx).getFavouriteIds().size();
      newCounts.put(LIKED, likedCount);

      // Get all songs once for quick artwork/existence lookup
      List<Song> allSongs = MusicLoader.loadSongs(ctx);
      Map<String, Song> songMap = new HashMap<>();
      for (Song s : allSongs) {
        songMap.put(String.valueOf(s.id), s);
      }

      // Cache song counts and first-item references for named playlists
      for (Map.Entry<String, List<String>> entry : allPlaylists.entrySet()) {
        String name = entry.getKey();
        List<String> ids = entry.getValue();
        newCounts.put(name, ids.size());
        if (!ids.isEmpty()) {
          Song firstSong = songMap.get(ids.get(0));
          if (firstSong != null) {
            newFirstSongs.put(name, firstSong);
          }
        }
      }

      new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
        names.clear();
        names.addAll(newNames);
        playlistCounts.clear();
        playlistCounts.putAll(newCounts);
        playlistFirstSongs.clear();
        playlistFirstSongs.putAll(newFirstSongs);
        notifyDataSetChanged();
      });
    }).start();
  }

  @Override
  public int getItemViewType(int position) {
    return viewMode; // LIST=0, GRID=1 — used as viewType
  }

  @NonNull
  @Override
  public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    int layout = viewType == GRID ? R.layout.item_playlist_grid : R.layout.item_playlist;
    View v = LayoutInflater.from(ctx).inflate(layout, parent, false);
    return new VH(v);
  }

  @SuppressLint("SetTextI18n")
  @Override
  public void onBindViewHolder(@NonNull VH h, int position) {
    String name = names.get(position);
    boolean isLiked = LIKED.equals(name);
    boolean isSmart = name.startsWith("__") && name.endsWith("__");

    h.ivIcon.setImageTintList(null);
    h.ivIcon.setColorFilter(null);
    h.ivIcon.setImageDrawable(null);
    Glide.with(h.ivIcon).clear(h.ivIcon);

    // TAG the view with current playlist name to prevent stale updates
    h.ivIcon.setTag(name);

//    android.util.Log.d("PlaylistsAdapter", "onBindViewHolder: position=" + position + ", name=" + name);


    if (isLiked) {
      h.tvName.setText("Liked Songs");
      Integer countObj = playlistCounts.get(LIKED);
      int count = countObj != null ? countObj : 0;
      h.tvCount.setText(count + (count == 1 ? " song" : " songs"));
      Glide.with(h.ivIcon).clear(h.ivIcon);
      h.ivIcon.setImageResource(R.drawable.ic_heart);
    } else if (PlaylistManager.SMART_MOST_PLAYED.equals(name)) {
      h.tvName.setText("Most Played");
      h.tvCount.setText("Top 30 songs");

      Glide.with(h.ivIcon).clear(h.ivIcon);
      h.ivIcon.setImageResource(R.drawable.ic_bar_chart);
    } else if (PlaylistManager.SMART_RECENTLY_PLAYED.equals(name)) {
      h.tvName.setText("Recently Played");
      h.tvCount.setText("Last 30 played");

      Glide.with(h.ivIcon).clear(h.ivIcon);
      h.ivIcon.setImageResource(R.drawable.ic_history);
    } else if (PlaylistManager.SMART_RECENTLY_ADDED.equals(name)) {
      h.tvName.setText("Recently Added");
      h.tvCount.setText("Last 30 added");

      Glide.with(h.ivIcon).clear(h.ivIcon);
      h.ivIcon.setImageResource(R.drawable.ic_new_releases);
    } else if (PlaylistManager.SMART_DOWNLOADS.equals(name)) {
      h.tvName.setText("Downloads");
      h.tvCount.setText("Downloaded songs");
      Glide.with(h.ivIcon).clear(h.ivIcon);
      h.ivIcon.setImageResource(R.drawable.ic_download_done);
    } else {
      h.tvName.setText(name);
      Integer countObj = playlistCounts.get(name);
      int count = countObj != null ? countObj : 0;
      h.tvCount.setText(count + (count == 1 ? " song" : " songs"));

      Song firstSong = playlistFirstSongs.get(name);
      loadMosaicArt(h.ivIcon, firstSong, name);
    }

    h.itemView.setOnClickListener(v -> listener.onClick(name));

    if (!isSmart && !isLiked && longListener != null) {
      h.itemView.setOnLongClickListener(v -> {
        longListener.onLongClick(name);
        return true;
      });
    } else {
      h.itemView.setOnLongClickListener(null);
    }
  }

  private void loadMosaicArt(ImageView view, Song song, String playlistName) {
    if (song == null) {
      view.setImageResource(R.drawable.ic_music_note);
      return;
    }

    Glide.with(view)
            .load(song.albumArtUri != null ? song.albumArtUri : song)
            .placeholder(R.drawable.ic_music_note)
            .error(R.drawable.ic_music_note)
            .centerCrop()
            .into(view);
  }

  @Override
  public int getItemCount() {
    return names.size();
  }

  public interface OnPlaylistClickListener {
    void onClick(String playlistName);
  }

  public interface OnPlaylistLongClickListener {
    void onLongClick(String playlistName);
  }

  static class VH extends RecyclerView.ViewHolder {
    ImageView ivIcon;
    TextView tvName, tvCount;

    VH(@NonNull View v) {
      super(v);
      ivIcon = v.findViewById(R.id.ivPlaylistIcon);
      tvName = v.findViewById(R.id.tvPlaylistItemName);
      tvCount = v.findViewById(R.id.tvPlaylistItemCount);
    }
  }
}