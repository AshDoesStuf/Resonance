package me.ash.resonance.util;

import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import me.ash.resonance.yt.YtTrack;

public class Utils {
  public static MediaItem buildYtItem(YtTrack track) {
    Uri uri = Uri.parse("ytmusic://" + track.videoId);

    return new MediaItem.Builder()
            .setUri(uri)
            .setMediaId(track.videoId)
            .setMediaMetadata(new MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setArtworkUri(track.thumbnailUrl != null
                            ? Uri.parse(track.thumbnailUrl)
                            : null)
                    .build())
            .build();
  }
}
