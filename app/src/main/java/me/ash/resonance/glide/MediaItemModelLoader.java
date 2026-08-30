package me.ash.resonance.glide;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;
import java.io.InputStream;

public class MediaItemModelLoader implements ModelLoader<MediaItem, InputStream> {

  private final Context context;

  public MediaItemModelLoader(Context context) {
    this.context = context;
  }

  @Nullable
  @Override
  public LoadData<InputStream> buildLoadData(@NonNull MediaItem item, int width, int height, @NonNull Options options) {
    if (item.localConfiguration == null) return null;
    return new LoadData<>(new ObjectKey(item.mediaId), new AudioCoverFetcher(context, item.localConfiguration.uri, item.mediaId));
  }

  @Override
  public boolean handles(@NonNull MediaItem item) {
    // Only handle if it's a local file and has no artwork URI
    return item.localConfiguration != null && item.mediaMetadata.artworkUri == null;
  }

  public static class Factory implements ModelLoaderFactory<MediaItem, InputStream> {
    private final Context context;

    public Factory(Context context) {
      this.context = context;
    }

    @NonNull
    @Override
    public ModelLoader<MediaItem, InputStream> build(@NonNull MultiModelLoaderFactory multiFactory) {
      return new MediaItemModelLoader(context);
    }

    @Override
    public void teardown() {
    }
  }
}
