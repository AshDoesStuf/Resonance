package me.ash.resonance.search;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import me.ash.resonance.R;
import me.ash.resonance.song.Song;
import me.ash.resonance.ui.SelectionManager;
import me.ash.resonance.yt.YtAlbum;
import me.ash.resonance.yt.YtArtist;
import me.ash.resonance.yt.YtDownloadManager;
import me.ash.resonance.yt.YtTrack;

/**
 * Two-source RecyclerView adapter that renders:
 * VIEW_TYPE_LOCAL  → local Song rows  (your existing library)
 * VIEW_TYPE_YT     → YouTube Music track rows
 * VIEW_TYPE_HEADER → section divider ("Local" / "YouTube Music")
 * <p>
 * Interaction is forwarded through {@link InteractionListener}.
 */
public class SearchResultAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

  // ── View types ────────────────────────────────────────────────────────────
  static final int VIEW_TYPE_HEADER = 0;
  static final int VIEW_TYPE_LOCAL = 1;
  static final int VIEW_TYPE_YT = 2;
  static final int VIEW_TYPE_ALBUM = 3;
  static final int VIEW_TYPE_ARTIST = 4;
  private static SelectionManager<String> selectionManager;
  // ── Item wrapper ──────────────────────────────────────────────────────────
  private final List<Item> items = new ArrayList<>();
  private final List<Song> localResults = new ArrayList<>();
  private final List<YtTrack> ytResults = new ArrayList<>();
  private final List<YtAlbum> albumResults = new ArrayList<>();
  private final List<YtArtist> artistResults = new ArrayList<>();

  // ── State ─────────────────────────────────────────────────────────────────
  // ── Listener ──────────────────────────────────────────────────────────────
  private final InteractionListener listener;
  private final java.util.Set<String> downloadedIds = new java.util.HashSet<>();

  public SearchResultAdapter(InteractionListener listener) {
    this.listener = listener;
  }

  @SuppressLint("NotifyDataSetChanged")
  public void setYtResults(List<YtTrack> ytTracks) {
    ytResults.clear();
    albumResults.clear();
    artistResults.clear();
    if (ytTracks != null) {
      ytResults.addAll(ytTracks);
      // Pre-fetch thumbnails for faster scrolling
      for (int i = 0; i < Math.min(ytTracks.size(), 12); i++) {
        Glide.with(me.ash.resonance.ResonanceApp.getInstance())
                .load(ytTracks.get(i).thumbnailUrl)
                .preload();
      }
    }
    rebuildItems();
  }

  @SuppressLint("NotifyDataSetChanged")
  public void setAlbumResults(List<YtAlbum> albums) {
    ytResults.clear();
    albumResults.clear();
    artistResults.clear();
    if (albums != null) {
      albumResults.addAll(albums);
      // Pre-fetch album art
      for (int i = 0; i < Math.min(albums.size(), 12); i++) {
        Glide.with(me.ash.resonance.ResonanceApp.getInstance())
                .load(albums.get(i).thumbnailUrl())
                .preload();
      }
    }
    rebuildItems();
  }

  @SuppressLint("NotifyDataSetChanged")
  public void setArtistResults(List<YtArtist> artists) {
    ytResults.clear();
    albumResults.clear();
    artistResults.clear();
    if (artists != null) {
      artistResults.addAll(artists);
      // Pre-fetch artist thumbnails
      for (int i = 0; i < Math.min(artists.size(), 12); i++) {
        Glide.with(me.ash.resonance.ResonanceApp.getInstance())
                .load(artists.get(i).thumbnailUrl())
                .preload();
      }
    }
    rebuildItems();
  }

  // ── Data setters ──────────────────────────────────────────────────────────

  @SuppressLint("NotifyDataSetChanged")
  public void setLocalResults(List<Song> songs) {
    localResults.clear();
    if (songs != null) localResults.addAll(songs);
    rebuildItems();
  }

  @SuppressLint("NotifyDataSetChanged")
  public void clear() {
    localResults.clear();
    ytResults.clear();
    albumResults.clear();
    artistResults.clear();
    rebuildItems();
  }

  @Override
  public int getItemViewType(int position) {
    return items.get(position).type;
  }

  @Override
  public int getItemCount() {
    return items.size();
  }

  // ── RecyclerView.Adapter ─────────────────────────────────────────────────

  @NonNull
  @Override
  public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    LayoutInflater inf = LayoutInflater.from(parent.getContext());
    switch (viewType) {
      case VIEW_TYPE_HEADER:
        return new HeaderVH(inf.inflate(R.layout.item_search_header, parent, false));
      case VIEW_TYPE_LOCAL:
        return new LocalVH(inf.inflate(R.layout.item_song, parent, false));
      case VIEW_TYPE_YT:
        return new YtVH(inf.inflate(R.layout.item_yt_result, parent, false));
      case VIEW_TYPE_ALBUM:
        return new AlbumVH(inf.inflate(R.layout.item_album, parent, false));
      case VIEW_TYPE_ARTIST:
      default:
        return new ArtistVH(inf.inflate(R.layout.item_artist, parent, false));
    }
  }

  @Override
  public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
    Item item = items.get(position);
    switch (item.type) {
      case VIEW_TYPE_HEADER:
        ((HeaderVH) holder).bind(item.header);
        break;
      case VIEW_TYPE_LOCAL:
        ((LocalVH) holder).bind(item.song, listener);
        break;
      case VIEW_TYPE_YT:
        ((YtVH) holder).bind(item.ytTrack, listener, downloadedIds);
        break;
      case VIEW_TYPE_ALBUM:
        ((AlbumVH) holder).bind(item.ytAlbum, listener);
        break;
      case VIEW_TYPE_ARTIST:
        ((ArtistVH) holder).bind(item.ytArtist, listener);
        break;
    }
  }

  public YtTrack findYtTrack(String videoId) {
    for (Item item : items) {
      if (item.type == VIEW_TYPE_YT && item.ytTrack != null
              && videoId.equals(item.ytTrack.videoId)) {
        return item.ytTrack;
      }
    }
    return null;
  }

  public void notifyYtTrackChanged(YtTrack track) {
    if (track == null) return;
    for (int i = 0; i < items.size(); i++) {
      Item item = items.get(i);
      if (item.type == VIEW_TYPE_YT
              && item.ytTrack != null
              && (item.ytTrack == track
              || Objects.equals(item.ytTrack.videoId, track.videoId))) {
        notifyItemChanged(i);
        return;
      }
    }
  }

  public void markAsDownloaded(String videoId) {
    downloadedIds.add(videoId);
    notifyYtTrackChanged(findYtTrack(videoId));
  }

  public void setSelectionManager(SelectionManager<String> sm) {
    selectionManager = sm;
  }

  public void updateDownloadStatuses(Context context, List<YtTrack> tracks) {
    new Thread(() -> {
      YtDownloadManager dm = YtDownloadManager.get(context);
      Set<String> downloaded = new java.util.HashSet<>();
      for (YtTrack t : tracks) {
        if (dm.isDownloaded(t.videoId)) downloaded.add(t.videoId);
      }
      new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
        Set<String> previous = new java.util.HashSet<>(downloadedIds);
        downloadedIds.clear();
        downloadedIds.addAll(downloaded);
        for (int i = 0; i < items.size(); i++) {
          Item item = items.get(i);
          if (item.type != VIEW_TYPE_YT || item.ytTrack == null) continue;
          boolean wasDownloaded = previous.contains(item.ytTrack.videoId);
          boolean isDownloaded = downloadedIds.contains(item.ytTrack.videoId);
          if (wasDownloaded != isDownloaded) {
            notifyItemChanged(i);
          }
        }
      });
    }).start();
  }

  private void rebuildItems() {
    List<Item> oldItems = new ArrayList<>(items);
    List<Item> newItems = buildItems(localResults, ytResults, albumResults, artistResults);

    DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
      @Override
      public int getOldListSize() {
        return oldItems.size();
      }

      @Override
      public int getNewListSize() {
        return newItems.size();
      }

      @Override
      public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
        Item oldItem = oldItems.get(oldItemPosition);
        Item newItem = newItems.get(newItemPosition);
        if (oldItem.type != newItem.type) return false;

        switch (oldItem.type) {
          case VIEW_TYPE_HEADER:
            return Objects.equals(oldItem.header, newItem.header);
          case VIEW_TYPE_LOCAL:
            return oldItem.song != null
                    && newItem.song != null
                    && oldItem.song.id == newItem.song.id;
          case VIEW_TYPE_YT:
            return oldItem.ytTrack != null
                    && newItem.ytTrack != null
                    && Objects.equals(oldItem.ytTrack.videoId, newItem.ytTrack.videoId);
          case VIEW_TYPE_ALBUM:
            return oldItem.ytAlbum != null
                    && newItem.ytAlbum != null
                    && Objects.equals(oldItem.ytAlbum.browseId(), newItem.ytAlbum.browseId());
          case VIEW_TYPE_ARTIST:
            return oldItem.ytArtist != null
                    && newItem.ytArtist != null
                    && Objects.equals(oldItem.ytArtist.channelId(), newItem.ytArtist.channelId());
          default:
            return false;
        }
      }

      @Override
      public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        Item oldItem = oldItems.get(oldItemPosition);
        Item newItem = newItems.get(newItemPosition);
        if (oldItem.type != newItem.type) return false;

        switch (oldItem.type) {
          case VIEW_TYPE_HEADER:
            return Objects.equals(oldItem.header, newItem.header);
          case VIEW_TYPE_LOCAL:
            return oldItem.song != null
                    && newItem.song != null
                    && Objects.equals(oldItem.song.title, newItem.song.title)
                    && Objects.equals(oldItem.song.artist, newItem.song.artist)
                    && Objects.equals(oldItem.song.duration, newItem.song.duration)
                    && Objects.equals(oldItem.song.albumArtUri, newItem.song.albumArtUri)
                    && Objects.equals(oldItem.song.uri, newItem.song.uri);
          case VIEW_TYPE_YT:
            return oldItem.ytTrack != null
                    && newItem.ytTrack != null
                    && Objects.equals(oldItem.ytTrack.title, newItem.ytTrack.title)
                    && Objects.equals(oldItem.ytTrack.artist, newItem.ytTrack.artist)
                    && Objects.equals(oldItem.ytTrack.thumbnailUrl, newItem.ytTrack.thumbnailUrl)
                    && oldItem.ytTrack.durationSeconds == newItem.ytTrack.durationSeconds
                    && oldItem.ytTrack.isDownloading == newItem.ytTrack.isDownloading;
          case VIEW_TYPE_ALBUM:
            return oldItem.ytAlbum != null
                    && newItem.ytAlbum != null
                    && Objects.equals(oldItem.ytAlbum.title(), newItem.ytAlbum.title())
                    && Objects.equals(oldItem.ytAlbum.artist(), newItem.ytAlbum.artist())
                    && Objects.equals(oldItem.ytAlbum.thumbnailUrl(), newItem.ytAlbum.thumbnailUrl());
          case VIEW_TYPE_ARTIST:
            return oldItem.ytArtist != null
                    && newItem.ytArtist != null
                    && Objects.equals(oldItem.ytArtist.name(), newItem.ytArtist.name())
                    && Objects.equals(oldItem.ytArtist.thumbnailUrl(), newItem.ytArtist.thumbnailUrl());
          default:
            return false;
        }
      }
    });

    items.clear();
    items.addAll(newItems);
    diff.dispatchUpdatesTo(this);
  }

  private List<Item> buildItems(List<Song> local, List<YtTrack> yt, List<YtAlbum> albums, List<YtArtist> artists) {
    List<Item> built = new ArrayList<>();
    List<Song> localSafe = local == null ? Collections.emptyList() : local;
    List<YtTrack> ytSafe = yt == null ? Collections.emptyList() : yt;
    List<YtAlbum> albumSafe = albums == null ? Collections.emptyList() : albums;
    List<YtArtist> artistSafe = artists == null ? Collections.emptyList() : artists;

    if (!localSafe.isEmpty()) {
      built.add(new Item("On Device"));
      for (Song song : localSafe) {
        built.add(new Item(song));
      }
    }

    if (!ytSafe.isEmpty()) {
      built.add(new Item("YouTube Music"));
      for (YtTrack track : ytSafe) {
        built.add(new Item(track));
      }
    }

    if (!albumSafe.isEmpty()) {
      built.add(new Item("Albums"));
      for (YtAlbum album : albumSafe) {
        built.add(new Item(album));
      }
    }

    if (!artistSafe.isEmpty()) {
      built.add(new Item("Artists"));
      for (YtArtist artist : artistSafe) {
        built.add(new Item(artist));
      }
    }
    return built;
  }

  public interface InteractionListener {
    // Local song actions
    void onLocalSongClick(Song song);

    void onLocalSongMore(Song song, View anchor);

    // YT song actions
    void onYtPlay(YtTrack track);

    void onYtMore(YtTrack track, View anchor);

    void onYtAlbumClick(YtAlbum album);

    void onYtArtistClick(YtArtist artist);
  }

  static class Item {
    final int type;
    final String header; // VIEW_TYPE_HEADER
    final Song song;   // VIEW_TYPE_LOCAL
    final YtTrack ytTrack; // VIEW_TYPE_YT
    final YtAlbum ytAlbum; // VIEW_TYPE_ALBUM
    final YtArtist ytArtist; // VIEW_TYPE_ARTIST

    Item(String header) {
      type = VIEW_TYPE_HEADER;
      this.header = header;
      song = null;
      ytTrack = null;
      ytAlbum = null;
      ytArtist = null;
    }

    Item(Song song) {
      type = VIEW_TYPE_LOCAL;
      this.header = null;
      this.song = song;
      ytTrack = null;
      ytAlbum = null;
      ytArtist = null;
    }

    Item(YtTrack ytTrack) {
      type = VIEW_TYPE_YT;
      this.header = null;
      song = null;
      this.ytTrack = ytTrack;
      ytAlbum = null;
      ytArtist = null;
    }

    Item(YtAlbum ytAlbum) {
      type = VIEW_TYPE_ALBUM;
      this.header = null;
      song = null;
      ytTrack = null;
      this.ytAlbum = ytAlbum;
      ytArtist = null;
    }

    Item(YtArtist ytArtist) {
      type = VIEW_TYPE_ARTIST;
      this.header = null;
      song = null;
      ytTrack = null;
      ytAlbum = null;
      this.ytArtist = ytArtist;
    }
  }

  // ── View holders ──────────────────────────────────────────────────────────

  static class HeaderVH extends RecyclerView.ViewHolder {
    final TextView tvHeader;


    HeaderVH(View v) {
      super(v);
      tvHeader = v.findViewById(R.id.tvSearchHeader);
    }

    void bind(String text) {
      tvHeader.setText(text);
    }
  }

  class LocalVH extends RecyclerView.ViewHolder {
    final TextView tvTitle, tvArtist, tvDuration;
    final ImageView ivArt;
    final ImageButton btnMore;
    final android.widget.CheckBox cbSelect;

    LocalVH(View v) {
      super(v);
      tvTitle = v.findViewById(R.id.tvSongTitle);
      tvArtist = v.findViewById(R.id.tvArtist);
      tvDuration = v.findViewById(R.id.tvDuration);
      ivArt = v.findViewById(R.id.ivAlbumArt);
      btnMore = v.findViewById(R.id.btnMore);
      cbSelect = v.findViewById(R.id.cbSelect);
    }

    void bind(Song song, InteractionListener listener) {
      tvTitle.setText(song.title);
      tvArtist.setText(song.artist);
      tvDuration.setText(song.duration);

      Glide.with(itemView)
              .load(song.albumArtUri)
              .apply(new RequestOptions()
                      .placeholder(R.drawable.ic_note_outlined)
                      .error(R.drawable.ic_note_outlined)
                      .transform(new RoundedCorners(16)))
              .into(ivArt);

      String id = String.valueOf(song.id);
      boolean sel = selectionManager != null && selectionManager.isSelected(id);
      boolean active = selectionManager != null && selectionManager.isActive();

      itemView.setBackgroundColor(sel ? 0x33c7a1a9 : 0x00000000);
      if (cbSelect != null) {
        cbSelect.setVisibility(active ? View.VISIBLE : View.GONE);
        cbSelect.setChecked(sel);
      }

      itemView.setOnClickListener(v -> {
        if (selectionManager != null && selectionManager.isActive()) {
          selectionManager.toggle(id);
          notifyItemChanged(getBindingAdapterPosition());
        } else {
          listener.onLocalSongClick(song);
        }
      });

      itemView.setOnLongClickListener(v -> {
        if (selectionManager != null) {
          selectionManager.toggle(id);
          notifyItemChanged(getBindingAdapterPosition());
          return true;
        }
        return false;
      });

      btnMore.setOnClickListener(v -> {
        if (selectionManager != null && selectionManager.isActive()) return;
        listener.onLocalSongMore(song, v);
      });
    }
  }

  class YtVH extends RecyclerView.ViewHolder {
    final TextView tvTitle, tvArtist, tvDuration;
    final ImageView ivThumb;
    final ImageButton btnMore;

    final ProgressBar pbDownload;
    final ImageView ivDownloaded;

    YtVH(View v) {
      super(v);
      tvTitle = v.findViewById(R.id.tvYtTitle);
      tvArtist = v.findViewById(R.id.tvYtArtist);
      tvDuration = v.findViewById(R.id.tvYtDuration);
      ivThumb = v.findViewById(R.id.ivYtThumb);
      btnMore = v.findViewById(R.id.btnYtMore);
      pbDownload = v.findViewById(R.id.pbDownload);
      ivDownloaded = v.findViewById(R.id.ivDownloaded);
    }

    void bind(YtTrack track, InteractionListener listener, Set<String> downloadedIds) {
      tvTitle.setText(track.title);
      tvArtist.setText(track.artist != null ? track.artist : "");
      tvDuration.setText(track.formattedDuration());

      Glide.with(itemView)
              .load(track.thumbnailUrl)
              .apply(new RequestOptions()
                      .placeholder(R.drawable.ic_note_outlined)
                      .error(R.drawable.ic_note_outlined)
                      .transform(new RoundedCorners(16)))
              .into(ivThumb);

      if (downloadedIds.contains(track.videoId)) {
        pbDownload.setVisibility(View.GONE);
        ivDownloaded.setVisibility(View.VISIBLE);
      } else if (track.isDownloading) {
        pbDownload.setVisibility(View.VISIBLE);
        ivDownloaded.setVisibility(View.GONE);
      } else {
        pbDownload.setVisibility(View.GONE);
        ivDownloaded.setVisibility(View.GONE);
      }

      boolean sel = selectionManager != null && selectionManager.isSelected(track.videoId);

      itemView.setBackgroundColor(sel ? 0x33c7a1a9 : 0x00000000);

      itemView.setOnClickListener(v -> {
        if (selectionManager != null && selectionManager.isActive()) {
          selectionManager.toggle(track.videoId);
          notifyItemChanged(getBindingAdapterPosition());
        } else {
          listener.onYtPlay(track);
        }
      });
      itemView.setOnLongClickListener(v -> {
        if (selectionManager != null) {
          selectionManager.toggle(track.videoId);
          notifyItemChanged(getBindingAdapterPosition());
          return true;
        }
        return false;
      });
      btnMore.setOnClickListener(v -> {
        if (selectionManager != null && selectionManager.isActive())
          return; // ignore during selection
        listener.onYtMore(track, v);
      });
    }
  }

  class AlbumVH extends RecyclerView.ViewHolder {
    final TextView tvTitle, tvArtist;
    final ImageView ivArt;

    AlbumVH(View v) {
      super(v);
      tvTitle = v.findViewById(R.id.tvAlbumName);
      tvArtist = v.findViewById(R.id.tvAlbumArtist);
      ivArt = v.findViewById(R.id.ivAlbumArt);
    }

    void bind(YtAlbum album, InteractionListener listener) {
      tvTitle.setText(album.title());
      tvArtist.setText(album.artist());

      Glide.with(itemView)
              .load(album.thumbnailUrl())
              .apply(new RequestOptions()
                      .placeholder(R.drawable.ic_note_outlined)
                      .error(R.drawable.ic_note_outlined)
                      .transform(new RoundedCorners(12)))
              .into(ivArt);

      itemView.setOnClickListener(v -> listener.onYtAlbumClick(album));
    }
  }

  class ArtistVH extends RecyclerView.ViewHolder {
    final TextView tvName, tvInitial;
    final ImageView ivThumb;

    ArtistVH(View v) {
      super(v);
      tvName = v.findViewById(R.id.tvArtistName);
      tvInitial = v.findViewById(R.id.tvArtistInitial);
      ivThumb = v.findViewById(R.id.ivArtistThumb);
    }

    void bind(YtArtist artist, InteractionListener listener) {
      tvName.setText(artist.name());
      if (artist.name() != null && !artist.name().isEmpty()) {
        tvInitial.setText(artist.name().substring(0, 1).toUpperCase());
      }

      if (artist.thumbnailUrl() != null && !artist.thumbnailUrl().isEmpty()) {
        ivThumb.setVisibility(View.VISIBLE);
        tvInitial.setVisibility(View.GONE);
        Glide.with(itemView)
                .load(artist.thumbnailUrl())
                .placeholder(R.drawable.ic_note_outlined)
                .error(R.drawable.ic_note_outlined)
                .circleCrop()
                .into(ivThumb);
      } else {
        ivThumb.setVisibility(View.GONE);
        tvInitial.setVisibility(View.VISIBLE);
      }

      itemView.setOnClickListener(v -> listener.onYtArtistClick(artist));
    }
  }
}
