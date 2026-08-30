package me.ash.resonance.glide;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import androidx.annotation.NonNull;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class AudioCoverFetcher implements DataFetcher<InputStream> {

  private final Context context;
  private final Uri uri;
  private final String modelId;

  public AudioCoverFetcher(Context context, Uri uri, String modelId) {
    this.context = context;
    this.uri = uri;
    this.modelId = modelId;
  }

  @Override
  public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super InputStream> callback) {
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    try {
      retriever.setDataSource(context, uri);
      byte[] picture = retriever.getEmbeddedPicture();
      if (picture != null) {
        callback.onDataReady(new ByteArrayInputStream(picture));
      } else {
        callback.onLoadFailed(new Exception("No embedded art found"));
      }
    } catch (Exception e) {
      callback.onLoadFailed(e);
    } finally {
      try {
        retriever.release();
      } catch (Exception ignored) {
      }
    }
  }

  @Override
  public void cleanup() {
  }

  @Override
  public void cancel() {
  }

  @NonNull
  @Override
  public Class<InputStream> getDataClass() {
    return InputStream.class;
  }

  @NonNull
  @Override
  public DataSource getDataSource() {
    return DataSource.LOCAL;
  }
}
