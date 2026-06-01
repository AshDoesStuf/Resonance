package me.ash.resonance.album;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.util.ArrayList;
import java.util.List;

import me.ash.resonance.MusicLoader;
import me.ash.resonance.R;
import me.ash.resonance.song.Song;
import me.ash.resonance.ui.BaseDetailActivity;

public class AlbumDetailActivity extends BaseDetailActivity {

  public static final String EXTRA_ALBUM_ID = "album_id";
  public static final String EXTRA_ALBUM_NAME = "album_name";
  public static final String EXTRA_ALBUM_ARTIST = "album_artist";

  private long albumId;
  private String albumName;
  private String albumArtist;

  // ── launch helper ─────────────────────────────

  public static Intent createIntent(Context context, Album album) {
    Intent i = new Intent(context, AlbumDetailActivity.class);
    i.putExtra(EXTRA_ALBUM_ID, album.id);
    i.putExtra(EXTRA_ALBUM_NAME, album.name);
    i.putExtra(EXTRA_ALBUM_ARTIST, album.artist);
    return i;
  }

  // ── layout ───────────────────────────────────

  @Override
  protected int getLayoutRes() {
    return R.layout.activity_detail; // rename from bottom_sheet_detail
  }

  // ── init ─────────────────────────────────────

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    albumId = getIntent().getLongExtra(EXTRA_ALBUM_ID, -1);
    albumName = getIntent().getStringExtra(EXTRA_ALBUM_NAME);
    albumArtist = getIntent().getStringExtra(EXTRA_ALBUM_ARTIST);
  }

  // ── header ───────────────────────────────────

  @Override
  protected void bindHeader(View view) {

    view.findViewById(R.id.cardDetailAlbumArt).setVisibility(View.VISIBLE);
    view.findViewById(R.id.tvDetailArtist).setVisibility(View.VISIBLE);
    view.findViewById(R.id.btnSortPlaylist).setVisibility(View.VISIBLE);
    view.findViewById(R.id.btnShufflePlaylist).setVisibility(View.VISIBLE);

    ((TextView) view.findViewById(R.id.tvDetailTitle)).setText(albumName);
    ((TextView) view.findViewById(R.id.tvDetailArtist)).setText(albumArtist);

    ImageView ivArt = view.findViewById(R.id.ivDetailAlbumArt);

    Uri artUri = Uri.parse(
            "content://media/external/audio/albumart/" + albumId
    );

    Glide.with(this)
            .load(artUri)
            .placeholder(R.drawable.ic_note_outlined)
            .error(R.drawable.ic_note_outlined)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(ivArt);
  }

  // ── data ─────────────────────────────────────

  @Override
  protected List<Song> loadSongs() {

    List<Song> result = new ArrayList<>();

    for (Song s : MusicLoader.loadSongs(this)) {
      if (s.albumArtUri != null &&
              s.albumArtUri.toString().endsWith("/" + albumId)) {
        result.add(s);
      }
    }

    result.sort((a, b) ->
            a.title.compareToIgnoreCase(b.title)
    );

    return result;
  }

  // ── extra UI update ─────────────────────────

  @Override
  protected void onSongsLoaded(View root, RecyclerView rv, List<Song> songs) {
    ((TextView) root.findViewById(R.id.tvDetailCount))
            .setText(
                    songs.size() + (songs.size() == 1 ? " song" : " songs")
            );

    super.onSongsLoaded(root, rv, songs);
  }
}