package me.ash.resonance.songs;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import me.ash.resonance.R;

public class SongRowBinder {

  private static final RequestOptions GLIDE_OPTIONS = new RequestOptions()
          .placeholder(R.drawable.music_note_24px)
          .error(R.drawable.music_note_24px)
          .transform(new RoundedCorners(20));

  public static class Views {
    public ImageView ivArt, ivDragHandle, ivPlaying;
    public TextView tvTitle, tvArtist, tvTrackNumber, tvDuration;
    public android.widget.CheckBox cbSelect; // null if the screen has no selection mode
  }

  public static void bind(Views v, MediaItem item, boolean isPlaying,
                           boolean showDragHandle, Integer trackNumber,
                           String durationText, Boolean isSelected) {
    v.tvTitle.setText(item.mediaMetadata.title != null ? item.mediaMetadata.title : "Unknown");
    v.tvArtist.setText(item.mediaMetadata.artist != null ? item.mediaMetadata.artist : "Unknown");
    v.tvTitle.setAlpha(isPlaying ? 1f : 0.85f);

    Glide.with(v.ivArt.getContext())
            .load(item.mediaMetadata.artworkUri != null ? item.mediaMetadata.artworkUri : item)
            .apply(GLIDE_OPTIONS)
            .into(v.ivArt);

    if (v.ivPlaying != null) {
      v.ivPlaying.setVisibility(isPlaying ? View.VISIBLE : View.GONE);
      if (isPlaying) {
        v.ivPlaying.setImageResource(R.drawable.ic_equalizer_animated);
        Drawable d = v.ivPlaying.getDrawable();
        if (d instanceof android.graphics.drawable.Animatable anim) {
          if (!anim.isRunning()) anim.start();
        }
      } else {
        Drawable d = v.ivPlaying.getDrawable();
        if (d instanceof android.graphics.drawable.Animatable anim) {
          if (anim.isRunning()) anim.stop();
        }
      }
    }
    if (v.ivDragHandle != null) {
      v.ivDragHandle.setVisibility(showDragHandle ? View.VISIBLE : View.GONE);
    }
    if (v.tvTrackNumber != null) {
      v.tvTrackNumber.setVisibility(trackNumber != null ? View.VISIBLE : View.GONE);
      if (trackNumber != null) v.tvTrackNumber.setText(String.valueOf(trackNumber));
    }
    if (v.tvDuration != null) {
      v.tvDuration.setVisibility(durationText != null ? View.VISIBLE : View.GONE);
      if (durationText != null) v.tvDuration.setText(durationText);
    }
    if (v.cbSelect != null && isSelected != null) {
      v.cbSelect.setVisibility(View.VISIBLE);
      v.cbSelect.setChecked(isSelected);
    } else if (v.cbSelect != null) {
      v.cbSelect.setVisibility(View.GONE);
    }
  }
}
