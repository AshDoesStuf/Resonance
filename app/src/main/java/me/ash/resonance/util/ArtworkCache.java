package me.ash.resonance.util;

import android.graphics.Bitmap;
import android.net.Uri;
import androidx.annotation.Nullable;

/**
 * A simple singleton to hold the currently playing artwork bitmap.
 * This allows MiniPlayerManager to show the artwork instantly during activity transitions
 * as a placeholder while Glide performs its own lookup.
 */
public class ArtworkCache {
  private static ArtworkCache instance;
  private Bitmap currentBitmap;
  private Uri currentUri;

  public static synchronized ArtworkCache getInstance() {
    if (instance == null) {
      instance = new ArtworkCache();
    }
    return instance;
  }

  public synchronized void setArtwork(@Nullable Uri uri, @Nullable Bitmap bitmap) {
    this.currentUri = uri;
    this.currentBitmap = bitmap;
  }

  @Nullable
  public synchronized Bitmap getBitmap(@Nullable Uri uri) {
    if (uri != null && uri.equals(currentUri)) {
      return currentBitmap;
    }
    return null;
  }
}
