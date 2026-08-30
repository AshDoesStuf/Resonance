package me.ash.resonance.songs;

import android.content.Context;
import androidx.media3.common.MediaItem;
import java.util.ArrayList;
import java.util.List;
import me.ash.resonance.ui.ResonanceDialog;

public class SongActionMenu {

  public interface ActionHandler {
    void onPlayNext(MediaItem song);
    void onAddToQueue(MediaItem song);
    void onAddToPlaylist(MediaItem song);
    void onGoToArtist(MediaItem song);
    void onGoToAlbum(MediaItem song);
    void onShare(MediaItem song);
    void onRemoveFromPlaylist(MediaItem song, String playlistId);
    void onRemoveFromQueue(MediaItem song, int index);
    void onRemoveDownload(MediaItem song);
  }

  public static void show(Context ctx, MediaItem song, SongContext context, ActionHandler handler) {
    List<String> labels = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();

    labels.add("Play Next");       actions.add(() -> handler.onPlayNext(song));
    labels.add("Add to Queue");    actions.add(() -> handler.onAddToQueue(song));
    labels.add("Add to Playlist"); actions.add(() -> handler.onAddToPlaylist(song));

    // Artist/Album navigation only works for local library songs.
    // Streamed songs (ytmusic://) don't have backing data entities for this yet.
    // Note: If streamed metadata is enriched later, this should be replaced
    // with a real capability check.
    boolean isStreamed = song.localConfiguration != null
            && "ytmusic".equals(song.localConfiguration.uri.getScheme());

    if (!isStreamed) {
      labels.add("Go to Artist"); actions.add(() -> handler.onGoToArtist(song));
      labels.add("Go to Album");  actions.add(() -> handler.onGoToAlbum(song));
    }

    labels.add("Share");           actions.add(() -> handler.onShare(song));

    switch (context.type) {
      case PLAYLIST:
        labels.add("Remove from Playlist");
        actions.add(() -> handler.onRemoveFromPlaylist(song, context.playlistId));
        break;
      case QUEUE:
        labels.add("Remove from Queue");
        actions.add(() -> handler.onRemoveFromQueue(song, context.queueIndex));
        break;
      case DOWNLOADED:
        labels.add("Remove Download");
        actions.add(() -> handler.onRemoveDownload(song));
        break;
      default:
        break;
    }

    new ResonanceDialog.Builder(ctx)
            .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
            .show();
  }
}
