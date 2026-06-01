package me.ash.resonance.artist;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import me.ash.resonance.MusicLoader;
import me.ash.resonance.R;
import me.ash.resonance.playlist.PlaylistDetailAdapter;
import me.ash.resonance.queue.QueueManager;
import me.ash.resonance.song.Song;
import me.ash.resonance.ui.BaseDetailActivity;

public class ArtistDetailActivity extends BaseDetailActivity {

  public static final String TAG = "ArtistDetailSheet";
  private static final String ARG_ARTIST_NAME = "artist_name";
  private MediaController controller;

  public static ArtistDetailActivity newInstance(Artist artist) {
    ArtistDetailActivity sheet = new ArtistDetailActivity();
    return sheet;
  }

  public static Intent createIntent(Context context, Artist artist) {
    Intent i = new Intent(context, ArtistDetailActivity.class);
    i.putExtra(ARG_ARTIST_NAME, artist.name);
    return i;
  }

  @Override
  protected int getLayoutRes() {
    return R.layout.activity_detail;
  }

  @Override
  protected void bindHeader(View view) {
    view.findViewById(R.id.btnSortPlaylist).setVisibility(View.VISIBLE);
    view.findViewById(R.id.btnShufflePlaylist).setVisibility(View.VISIBLE);
    ((TextView) view.findViewById(R.id.tvDetailTitle))
            .setText(getIntent().getExtras().getString(ARG_ARTIST_NAME, ""));
  }

  @Override
  protected List<Song> loadSongs() {
    String artistName = getIntent().getExtras().getString(ARG_ARTIST_NAME, "");
    List<Song> result = new ArrayList<>();
    for (Song s : MusicLoader.loadSongs(this)) {
      String n = s.artist != null ? s.artist.trim() : "";
      boolean match = n.equalsIgnoreCase(artistName)
              || (artistName.equals("Unknown Artist")
              && (n.isEmpty() || n.equals("<unknown>")));
      if (match) result.add(s);
    }
    result.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
    return result;
  }

  @SuppressLint("SetTextI18n")
  @Override
  protected void onSongsLoaded(View root, RecyclerView rv, List<Song> songs) {
    ((TextView) root.findViewById(R.id.tvDetailCount))
            .setText(songs.size() + (songs.size() == 1 ? " song" : " songs"));
    super.onSongsLoaded(root, rv, songs);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    String artistName = getIntent().getExtras().getString(ARG_ARTIST_NAME, "");

    // Filter songs by this artist
    List<Song> allSongs = MusicLoader.loadSongs(this);
    List<Song> artistSongs = new ArrayList<>();
    for (Song s : allSongs) {
      String name = s.artist != null ? s.artist.trim() : "";
      String unknown = "Unknown Artist";
      boolean matches = name.equalsIgnoreCase(artistName)
              || (artistName.equals(unknown) && (name.isEmpty() || name.equals("<unknown>")));
      if (matches) artistSongs.add(s);
    }
    artistSongs.sort((a, b) -> a.title.compareToIgnoreCase(b.title));

    updateCount(artistSongs.size());

    RecyclerView rv = findViewById(R.id.rvDetailSongs);
    rv.setLayoutManager(new LinearLayoutManager(this));

    List<Song> songs = artistSongs; // effectively final for lambda
    rv.setAdapter(new PlaylistDetailAdapter(
            songs,
            song -> {
              if (controller == null) return;
              List<MediaItem> items = buildMediaItems(songs);
              int idx = songs.indexOf(song);
              QueueManager.get().setOriginalItems(items);
              controller.setMediaItems(items, idx, 0);
              controller.prepare();
              controller.play();
            },
            song -> { /* no remove action for artists */ }
    ));
  }

  @SuppressLint("SetTextI18n")
  private void updateCount(int count) {
    ((TextView) findViewById(R.id.tvDetailCount)).setText(count + (count == 1 ? " song" : " songs"));
  }
}