package me.ash.resonance.album;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import me.ash.resonance.R;

public class AlbumsAdapter extends RecyclerView.Adapter<AlbumsAdapter.VH> {

  private final Context context;
  private final List<Album> albums = new ArrayList<>();
  private final OnAlbumClickListener listener;

  public AlbumsAdapter(Context context, List<Album> initialAlbums, OnAlbumClickListener listener) {
    this.context = context;
    if (initialAlbums != null) {
      this.albums.addAll(initialAlbums);
    }
    this.listener = listener;
  }

  @NonNull
  @Override
  public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View v = LayoutInflater.from(context).inflate(R.layout.item_album, parent, false);
    return new VH(v);
  }

  @Override
  public void onBindViewHolder(@NonNull VH holder, int position) {
    Album album = albums.get(position);

    holder.tvAlbumName.setText(album.name);
    holder.tvAlbumArtist.setText(album.artist);

    Glide.with(context)
            .load(album.artUri)
            .placeholder(R.drawable.ic_note_outlined)
            .error(R.drawable.ic_note_outlined)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(holder.ivAlbumArt);

    holder.itemView.setOnClickListener(v -> listener.onAlbumClick(album));
  }

  @Override
  public int getItemCount() {
    return albums.size();
  }

  public void update(List<Album> newAlbums) {
    List<Album> oldList = new ArrayList<>(this.albums);
    List<Album> newList = newAlbums == null ? Collections.emptyList() : new ArrayList<>(newAlbums);

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
        return oldList.get(oldPos).id == newList.get(newPos).id;
      }

      @Override
      public boolean areContentsTheSame(int oldPos, int newPos) {
        Album a = oldList.get(oldPos);
        Album b = newList.get(newPos);
        return Objects.equals(a.name, b.name)
                && Objects.equals(a.artist, b.artist)
                && a.songCount == b.songCount
                && Objects.equals(a.artUri, b.artUri);
      }
    });

    this.albums.clear();
    this.albums.addAll(newList);
    diff.dispatchUpdatesTo(this);
  }

  public interface OnAlbumClickListener {
    void onAlbumClick(Album album);
  }

  static class VH extends RecyclerView.ViewHolder {
    ImageView ivAlbumArt;
    TextView tvAlbumName;
    TextView tvAlbumArtist;

    VH(@NonNull View itemView) {
      super(itemView);
      ivAlbumArt = itemView.findViewById(R.id.ivAlbumArt);
      tvAlbumName = itemView.findViewById(R.id.tvAlbumName);
      tvAlbumArtist = itemView.findViewById(R.id.tvAlbumArtist);
    }
  }
}