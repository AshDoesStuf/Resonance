package me.ash.resonance.artist;

import android.annotation.SuppressLint;
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
import me.ash.resonance.playlist.PlaylistDetailAdapter;
import me.ash.resonance.song.Song;
import me.ash.resonance.ui.BaseDetailActivity;
import me.ash.resonance.yt.YtMusicService;
import me.ash.resonance.yt.YtTrack;

public class ArtistDetailActivity extends BaseDetailActivity {

  public static final String EXTRA_CHANNEL_ID = "channelId";
  public static final String EXTRA_NAME = "name";
  public static final String EXTRA_THUMB = "thumb";

  private String channelId;
  private String name;
  private String thumb;

  public static Intent createIntent(Context context, String channelId, String name, String thumb) {
    Intent i = new Intent(context, ArtistDetailActivity.class);
    i.putExtra(EXTRA_CHANNEL_ID, channelId);
    i.putExtra(EXTRA_NAME, name);
    i.putExtra(EXTRA_THUMB, thumb);
    return i;
  }

  @Override
  protected int getLayoutRes() {
    return R.layout.activity_detail;
  }

  @Override
  protected void bindHeader(View view) {
    view.findViewById(R.id.btnShufflePlaylist).setVisibility(View.VISIBLE);
    view.findViewById(R.id.btnMore).setVisibility(View.GONE);
    ((TextView) view.findViewById(R.id.tvDetailTitle)).setText(name);

    ImageView ivArt = view.findViewById(R.id.ivDetailAlbumArt);
    view.findViewById(R.id.cardDetailAlbumArt).setVisibility(View.VISIBLE);

    Glide.with(this)
            .load(thumb)
            .placeholder(R.drawable.ic_note_outlined)
            .error(R.drawable.ic_note_outlined)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(ivArt);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    channelId = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
    name = getIntent().getStringExtra(EXTRA_NAME);
    thumb = getIntent().getStringExtra(EXTRA_THUMB);

    super.onCreate(savedInstanceState);
  }

  @Override
  protected List<Song> loadSongs() {
    return new ArrayList<>();
  }

  @SuppressLint("SetTextI18n")
  @Override
  protected void onSongsLoaded(View root, RecyclerView rv, List<Song> songs) {
    if (channelId == null) {
      // Local library mode
      new Thread(() -> {
        List<Song> localSongs = new ArrayList<>();
        for (Song s : MusicLoader.loadSongs(this)) {
          if (name != null && name.equalsIgnoreCase(s.artist)) {
            localSongs.add(s);
          }
        }
        localSongs.sort((a, b) -> a.title.compareToIgnoreCase(b.title));

        runOnUiThread(() -> {
          this.currentSongs = localSongs;
          PlaylistDetailAdapter adapter = new PlaylistDetailAdapter(localSongs, s -> playSong(localSongs, s), this::onRemoveSong);
          adapter.setPlaylistName(name);
          adapter.setController(controller);
          rv.setAdapter(adapter);

          ((TextView) root.findViewById(R.id.tvDetailCount))
                  .setText(localSongs.size() + (localSongs.size() == 1 ? " song" : " songs"));
        });
      }).start();
      return;
    }

    // NewPipe uses channelId for artist details.
    // If it's a full URL, we extract the ID.
    String id = channelId;
    if (id != null && id.contains("channel/")) {
      id = id.split("channel/")[1];
    }

    YtMusicService.get().fetchRelatedTracks(id, new YtMusicService.SearchCallback() {
      @Override
      public void onResults(List<YtTrack> tracks) {
        List<Song> songList = new ArrayList<>();
        for (YtTrack t : tracks) {
          songList.add(new Song(
                  t.videoId,
                  t.title,
                  t.artist,
                  t.albumName,
                  t.formattedDuration(),
                  Uri.parse("ytmusic://" + t.videoId),
                  t.thumbnailUrl != null ? Uri.parse(t.thumbnailUrl) : null
          ));
        }
        runOnUiThread(() -> {
          ArtistDetailActivity.this.currentSongs = songList;
          PlaylistDetailAdapter adapter = new PlaylistDetailAdapter(songList, s -> playSong(songList, s), ArtistDetailActivity.this::onRemoveSong);
          adapter.setPlaylistName(name);
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

  @SuppressLint("SetTextI18n")
  private void updateCount(int count) {
    ((TextView) findViewById(R.id.tvDetailCount)).setText(count + (count == 1 ? " song" : " songs"));
  }
}
