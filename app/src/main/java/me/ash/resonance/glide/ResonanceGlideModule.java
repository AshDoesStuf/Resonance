package me.ash.resonance.glide;

import android.content.Context;
import androidx.annotation.NonNull;
import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.module.AppGlideModule;
import androidx.media3.common.MediaItem;
import java.io.InputStream;
import me.ash.resonance.song.Song;

@GlideModule
public class ResonanceGlideModule extends AppGlideModule {
  @Override
  public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
    registry.prepend(Song.class, InputStream.class, new SongModelLoader.Factory(context));
    registry.prepend(MediaItem.class, InputStream.class, new MediaItemModelLoader.Factory(context));
  }
}
