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
import me.ash.resonance.playlist.PlaylistDetailAdapter;
import me.ash.resonance.yt.YtMusicService;
import me.ash.resonance.yt.YtTrack;
import me.ash.resonance.R;
import me.ash.resonance.song.Song;
import me.ash.resonance.ui.BaseDetailActivity;

public class AlbumDetailActivity extends BaseDetailActivity {

  public static final String EXTRA_BROWSE_ID = "browseId";
  public static final String EXTRA_TITLE = "title";
  public static final String EXTRA_THUMB = "thumb";
  public static final String EXTRA_ARTIST = "artist";

  private String browseId;
  private String title;
  private String thumb;
  private String artist;

  // ── launch helper ─────────────────────────────

  public static Intent createIntent(Context context, String browseId, String title, String artist, String thumb) {
    Intent i = new Intent(context, AlbumDetailActivity.class);
    i.putExtra(EXTRA_BROWSE_ID, browseId);
    i.putExtra(EXTRA_TITLE, title);
    i.putExtra(EXTRA_ARTIST, artist);
    i.putExtra(EXTRA_THUMB, thumb);
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
    browseId = getIntent().getStringExtra(EXTRA_BROWSE_ID);
    title = getIntent().getStringExtra(EXTRA_TITLE);
    artist = getIntent().getStringExtra(EXTRA_ARTIST);
    thumb = getIntent().getStringExtra(EXTRA_THUMB);

    super.onCreate(savedInstanceState);
  }

  // ── header ───────────────────────────────────

  @Override
  protected void bindHeader(View view) {

    view.findViewById(R.id.cardDetailAlbumArt).setVisibility(View.VISIBLE);
    view.findViewById(R.id.tvDetailArtist).setVisibility(View.VISIBLE);
    view.findViewById(R.id.btnShufflePlaylist).setVisibility(View.VISIBLE);
    view.findViewById(R.id.btnMore).setVisibility(View.GONE);

    ((TextView) view.findViewById(R.id.tvDetailTitle)).setText(title);
    ((TextView) view.findViewById(R.id.tvDetailArtist)).setText(artist);

    ImageView ivArt = view.findViewById(R.id.ivDetailAlbumArt);

    Glide.with(this)
            .load(thumb)
            .placeholder(R.drawable.ic_note_outlined)
            .error(R.drawable.ic_note_outlined)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(ivArt);
  }

  @Override
  protected List<Song> loadSongs() {
    // We override onSongsLoaded to fetch from network instead of using this blocking method.
    return new ArrayList<>();
  }

  @Override
  protected void onSongsLoaded(View root, RecyclerView rv, List<Song> songs) {
    if (browseId == null) {
      // Local library mode
      new Thread(() -> {
        List<Song> localSongs = new ArrayList<>();
        // For local albums, we filter by name/artist since we don't have the ID easily in this new Intent
        for (Song s : MusicLoader.loadSongs(this)) {
          if (title != null && title.equalsIgnoreCase(s.album) &&
                  (artist == null || artist.equalsIgnoreCase(s.artist))) {
            localSongs.add(s);
          }
        }
        localSongs.sort((a, b) -> a.title.compareToIgnoreCase(b.title));

        runOnUiThread(() -> {
          this.currentSongs = localSongs;
          PlaylistDetailAdapter adapter = new PlaylistDetailAdapter(localSongs, s -> playSong(localSongs, s), this::onRemoveSong);
          adapter.setPlaylistName(title);
          adapter.setController(controller);
          rv.setAdapter(adapter);

          ((TextView) root.findViewById(R.id.tvDetailCount))
                  .setText(localSongs.size() + (localSongs.size() == 1 ? " song" : " songs"));
        });
      }).start();
      return;
    }

    // YT Music mode
    YtMusicService.get().fetchAlbumTracks(browseId, new YtMusicService.SearchCallback() {
      @Override
      public void onResults(List<YtTrack> tracks) {
        List<Song> songList = new ArrayList<>();
        for (YtTrack t : tracks) {
          songList.add(new Song(
                  t.videoId,
                  t.title,
                  t.artist,
                  title,
                  t.formattedDuration(),
                  Uri.parse("ytmusic://" + t.videoId),
                  t.thumbnailUrl != null ? Uri.parse(t.thumbnailUrl) : null
          ));
        }
        runOnUiThread(() -> {
          AlbumDetailActivity.this.currentSongs = songList;
          PlaylistDetailAdapter adapter = new PlaylistDetailAdapter(songList, s -> playSong(songList, s), AlbumDetailActivity.this::onRemoveSong);
          adapter.setPlaylistName(title);
          adapter.setController(controller);
          rv.setAdapter(adapter);

          ((TextView) root.findViewById(R.id.tvDetailCount))
                  .setText(songList.size() + (songList.size() == 1 ? " song" : " songs"));
        });
      }

      @Override
      public void onError(Exception e) {
        // Handle error
      }
    });
  }
}