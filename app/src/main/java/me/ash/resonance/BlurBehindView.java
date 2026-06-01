package me.ash.resonance;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import me.ash.resonance.ui.GlassStyle;
import me.ash.resonance.ui.GlassStyleManager;

public class BlurBehindView extends View {

  // ── shared ────────────────────────────────────────────────────────────────
  private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
  private final Path clipPath = new Path();
  private Bitmap buffer;
  private Canvas bufferCanvas;
  private boolean capturing;

  private GlassStyleManager styleManager;
  private androidx.lifecycle.Observer<GlassStyle> styleObserver;

  // ── liquid shimmer ────────────────────────────────────────────────────────
  private float shimmerOffset = 0f;          // 0..1 normalised position
  private ValueAnimator shimmerAnimator;

  // ── style ─────────────────────────────────────────────────────────────────
  private GlassStyle style;

  public BlurBehindView(Context context) {
    super(context);
    init();
  }

  public BlurBehindView(Context context, AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  private void init() {
    setWillNotDraw(false);
    // Isolate this view's rendering — prevents blur bleeding onto siblings
    setLayerType(LAYER_TYPE_HARDWARE, null);

    styleManager = GlassStyleManager.get(getContext());
    style = styleManager.current();
    applyBlurEffect();

    // Observe live changes
    styleObserver = newStyle -> {
      setStyle(newStyle);
    };
    styleManager.observe().observeForever(styleObserver);

    if (style == GlassStyle.LIQUID) startShimmer();
  }

  // ── public API ────────────────────────────────────────────────────────────

  /**
   * Call after saving a new style preference to instantly update all instances.
   */
  public void setStyle(GlassStyle newStyle) {
    this.style = newStyle;
    applyBlurEffect();
    if (newStyle == GlassStyle.LIQUID) {
      startShimmer();
    } else {
      stopShimmer();
    }
    postInvalidate();
  }

  public void refresh() {
    if (!capturing) postInvalidateOnAnimation();
  }

  // ── blur kernel ───────────────────────────────────────────────────────────

  private void applyBlurEffect() {
    float r = (style == GlassStyle.LIQUID) ? 18f : 14f;
    setRenderEffect(
            RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
    );
  }

  // ── shimmer animator (Liquid only) ────────────────────────────────────────

  private void startShimmer() {
    if (shimmerAnimator != null && shimmerAnimator.isRunning()) return;
    shimmerAnimator = ValueAnimator.ofFloat(-0.4f, 1.4f);
    shimmerAnimator.setDuration(2800);
    shimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
    shimmerAnimator.setInterpolator(new LinearInterpolator());
    shimmerAnimator.addUpdateListener(a -> {
      shimmerOffset = (float) a.getAnimatedValue();
      if (!capturing) postInvalidateOnAnimation();
    });
    shimmerAnimator.start();
  }

  private void stopShimmer() {
    if (shimmerAnimator != null) {
      shimmerAnimator.cancel();
      shimmerAnimator = null;
    }
  }

  // ── draw ──────────────────────────────────────────────────────────────────

  @Override
  protected void onDraw(Canvas canvas) {
    if (capturing) return;

    // ── 1. clip to rounded rect ───────────────────────────────────────────
    clipPath.reset();
    float inset = getResources().getDisplayMetrics().density; // 1dp in px
    clipPath.addRoundRect(inset, inset, getWidth() - inset, getHeight() - inset,
            39f, 39f, Path.Direction.CW);
    canvas.save();
    canvas.clipPath(clipPath);

    // ── 2. capture what's behind this view ────────────────────────────────
    View root = ((Activity) getContext()).getWindow().getDecorView();
    int w = getWidth(), h = getHeight();
    if (w <= 0 || h <= 0) {
      canvas.restore();
      return;
    }

    if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h) {
      if (buffer != null) buffer.recycle();
      buffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      bufferCanvas = new Canvas(buffer);
    }

    buffer.eraseColor(Color.TRANSPARENT);
    try {
      capturing = true;
      setVisibility(INVISIBLE);

      int[] rootPos = new int[2], myPos = new int[2];
      root.getLocationOnScreen(rootPos);
      getLocationOnScreen(myPos);

      bufferCanvas.save();
      bufferCanvas.translate(-(myPos[0] - rootPos[0]), -(myPos[1] - rootPos[1]));
      root.draw(bufferCanvas);
      bufferCanvas.restore();
    } finally {
      setVisibility(VISIBLE);
      capturing = false;
    }

    canvas.drawBitmap(buffer, 0, 0, paint);

    // ── 3. style-specific overlay ─────────────────────────────────────────
    if (style == GlassStyle.LIQUID) {
      drawLiquid(canvas, w, h);
    } else {
      drawFrosted(canvas, w, h);
    }

    canvas.restore();

    setOutlineProvider(new android.view.ViewOutlineProvider() {
      @Override
      public void getOutline(View view, android.graphics.Outline outline) {
        float radius = getResources().getDisplayMetrics().density * 40; // matches 40dp
        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
      }
    });
    setClipToOutline(true);
  }

  // ── Frosted overlay ───────────────────────────────────────────────────────

  private void drawFrosted(Canvas canvas, int w, int h) {
    // Semi-opaque dark tint — this is what makes it feel like frosted glass
    // not a window. Tune the alpha (currently 0x55 = ~33%) to taste.
    canvas.drawColor(0x55101520);

    // Subtle warm/neutral fill so it doesn't look like a plain dark sheet
    Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    tint.setShader(new LinearGradient(
            0, 0, w, h,
            new int[]{0x18FFFFFF, 0x08AABBCC},
            null,
            Shader.TileMode.CLAMP
    ));
    canvas.drawRect(0, 0, w, h, tint);

    // Top-edge specular — the "glass rim" highlight
    Paint topEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    topEdge.setShader(new LinearGradient(
            0, 0, 0, 20,
            0x22FFFFFF, 0x00FFFFFF,
            Shader.TileMode.CLAMP
    ));
    canvas.drawRect(0, 0, w, 20, topEdge);
  }

  // ── Liquid Glass overlay ──────────────────────────────────────────────────

  private void drawLiquid(Canvas canvas, int w, int h) {
    // Same near-zero base as frosted — difference is motion, not opacity
    canvas.drawColor(0x08000000);

    // Band 1 — slow wide sweep
    float band1X = shimmerOffset * (w + 260) - 130;
    Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    band.setShader(new LinearGradient(
            band1X - 80, 0, band1X + 80, 0,
            new int[]{0x00FFFFFF, 0x12FFFFFF, 0x00FFFFFF},
            null,
            Shader.TileMode.CLAMP
    ));
    canvas.drawRect(0, 0, w, h, band);

    // Band 2 — narrower, offset phase, moves slightly faster
    float band2X = ((shimmerOffset + 0.5f) % 1.4f - 0.2f) * (w + 200) - 100;
    band.setShader(new LinearGradient(
            band2X - 40, 0, band2X + 40, 0,
            new int[]{0x00FFFFFF, 0x0AFFFFFF, 0x00FFFFFF},
            null,
            Shader.TileMode.CLAMP
    ));
    canvas.drawRect(0, 0, w, h, band);

    // Diagonal refraction — top-left corner catch, very faint
    Paint refract = new Paint(Paint.ANTI_ALIAS_FLAG);
    refract.setShader(new LinearGradient(
            0, 0, w * 0.5f, h,
            new int[]{0x0AFFFFFF, 0x00FFFFFF},
            null,
            Shader.TileMode.CLAMP
    ));
    canvas.drawRect(0, 0, w, h, refract);

    // Same hairline top edge as frosted — slightly brighter to mark the liquid rim
    Paint topEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    topEdge.setShader(new LinearGradient(
            0, 0, 0, 16,
            0x1EFFFFFF, 0x00FFFFFF,
            Shader.TileMode.CLAMP
    ));
    canvas.drawRect(0, 0, w, 16, topEdge);
  }


  // ── lifecycle ─────────────────────────────────────────────────────────────

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (style == GlassStyle.LIQUID) startShimmer();
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    stopShimmer();
    // Stop observing to prevent leaks
    if (styleObserver != null) {
      styleManager.observe().removeObserver(styleObserver);
      styleObserver = null;
    }
    if (buffer != null) {
      buffer.recycle();
      buffer = null;
    }
  }
}