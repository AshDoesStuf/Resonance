package me.ash.resonance.glide;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;
import java.io.InputStream;
import me.ash.resonance.song.Song;

public class SongModelLoader implements ModelLoader<Song, InputStream> {

  private final Context context;

  public SongModelLoader(Context context) {
    this.context = context;
  }

  @Nullable
  @Override
  public LoadData<InputStream> buildLoadData(@NonNull Song song, int width, int height, @NonNull Options options) {
    return new LoadData<>(new ObjectKey(song.id), new AudioCoverFetcher(context, song.uri, song.id));
  }

  @Override
  public boolean handles(@NonNull Song song) {
    return true;
  }

  public static class Factory implements ModelLoaderFactory<Song, InputStream> {
    private final Context context;

    public Factory(Context context) {
      this.context = context;
    }

    @NonNull
    @Override
    public ModelLoader<Song, InputStream> build(@NonNull MultiModelLoaderFactory multiFactory) {
      return new SongModelLoader(context);
    }

    @Override
    public void teardown() {
    }
  }
}
