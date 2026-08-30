package me.ash.resonance.util;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.palette.graphics.Palette;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DominantColorExtractor {

  private static final int CACHE_SIZE = 100;
  private static final LruCache<String, GeneratedPalette> cache = new LruCache<>(CACHE_SIZE);
  private static final ExecutorService executor = Executors.newFixedThreadPool(2);
  private static final Handler mainHandler = new Handler(Looper.getMainLooper());

  public static void extract(@NonNull String cacheKey, @Nullable Drawable drawable, @NonNull Callback callback) {
    if (drawable instanceof BitmapDrawable) {
      extract(cacheKey, ((BitmapDrawable) drawable).getBitmap(), callback);
    }
  }

  public static void extract(@NonNull String cacheKey, @Nullable Bitmap bitmap, @NonNull Callback callback) {
    GeneratedPalette cached = cache.get(cacheKey);
    if (cached != null) {
      callback.onGenerated(cached);
      return;
    }

    if (bitmap == null || bitmap.isRecycled()) return;

    executor.execute(() -> {
      try {
        Palette palette = Palette.from(bitmap).generate();
        GeneratedPalette generated = new GeneratedPalette(palette);
        cache.put(cacheKey, generated);
        mainHandler.post(() -> callback.onGenerated(generated));
      } catch (Exception e) {
        // Handle failures gracefully
      }
    });
  }

  public interface Callback {
    void onGenerated(@NonNull GeneratedPalette palette);
  }

  public static class GeneratedPalette {
    @ColorInt
    public final int dominant;
    @ColorInt
    public final int vibrant;
    @ColorInt
    public final int darkVibrant;
    @ColorInt
    public final int lightVibrant;
    @ColorInt
    public final int muted;
    @ColorInt
    public final int darkMuted;
    @ColorInt
    public final int lightMuted;

    public GeneratedPalette(@Nullable Palette palette) {
      if (palette != null) {
        this.dominant = palette.getDominantColor(0);
        this.vibrant = palette.getVibrantColor(dominant);
        this.darkVibrant = palette.getDarkVibrantColor(dominant);
        this.lightVibrant = palette.getLightVibrantColor(dominant);
        this.muted = palette.getMutedColor(dominant);
        this.darkMuted = palette.getDarkMutedColor(dominant);
        this.lightMuted = palette.getLightMutedColor(dominant);
      } else {
        this.dominant = 0;
        this.vibrant = 0;
        this.darkVibrant = 0;
        this.lightVibrant = 0;
        this.muted = 0;
        this.darkMuted = 0;
        this.lightMuted = 0;
      }
    }
  }
}
