package me.ash.resonance;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A clean, AOSP-style "frosted glass" panel.
 * <p>
 * Recipe (mirrors the official window-blur guidance, adapted for an
 * in-window view since cross-window blur APIs only blur OTHER windows):
 * <p>
 * 1. Capture: snapshot the decor view behind this view into a Bitmap.
 * 2. Blur:    run that bitmap through a RenderNode with a BlurEffect
 * (radius ~80px, matching the "frosted glass" guidance).
 * 3. Tint:    a single translucent dark layer over the blur for legibility.
 * 4. Specular: a soft highlight along the top edge ("lit rim").
 * 5. Border:  a 1dp ~20% white hairline outline.
 * <p>
 * No noise, no animated highlights, no per-style overlays — just the
 * minimal set of layers that reads as "glass" on a real OS.
 */
public class BlurBehindView extends View {

  // Matches the AOSP guidance: ~80px gives a good frosted-glass effect.
  // Converted to a density-aware value so it reads consistently across devices.
  private static final float BLUR_RADIUS_DP = 28f;

  // Single translucent tint — dark enough for text legibility, light enough
  // that the blurred content still reads through.
  // Increased opacity for a denser "acrylic" feel (approx 70%).
  private static final int TINT_COLOR = 0xB3121212;

  // 1dp hairline at ~20% white — the single most important "glass" cue.
  private static final int BORDER_COLOR = 0x33FFFFFF;

  private final Paint backdropPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
  private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path clipPath = new Path();
  private final Path borderPath = new Path();

  private Bitmap capturedBackdrop;
  private Canvas captureCanvas;
  private boolean capturing = false;

  private RenderNode blurNode;
  private int blurNodeW, blurNodeH;

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
    // Hardware layer so compositing works correctly. No RenderEffect is set
    // on the view itself — that would blur the overlays/border too.
    setLayerType(LAYER_TYPE_HARDWARE, null);

    float density = getResources().getDisplayMetrics().density;
    borderPaint.setStyle(Paint.Style.STROKE);
    borderPaint.setStrokeWidth(density); // 1dp
    borderPaint.setColor(BORDER_COLOR);
  }

  /**
   * Call if you need to force a redraw (e.g. after the backdrop changes).
   */
  public void refresh() {
    if (!capturing) postInvalidateOnAnimation();
  }

  // ── blur node ──────────────────────────────────────────────────────────

  private void rebuildBlurNode(int w, int h) {
    if (w <= 0 || h <= 0) return;
    float r = BLUR_RADIUS_DP * getResources().getDisplayMetrics().density;
    blurNode = new RenderNode("backdrop_blur");
    blurNode.setPosition(0, 0, w, h);
    blurNode.setRenderEffect(
            RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
    blurNodeW = w;
    blurNodeH = h;
  }

  private int getBlurPad() {
    return (int) Math.ceil(BLUR_RADIUS_DP * getResources().getDisplayMetrics().density);
  }

  // ── size / lifecycle ──────────────────────────────────────────────────

  @Override
  protected void onSizeChanged(int w, int h, int oldW, int oldH) {
    super.onSizeChanged(w, h, oldW, oldH);
    if (w <= 0 || h <= 0) return;

    // Bitmap and RenderNode are now managed in onDraw/captureBackdrop
    // to account for dynamic padding.
    if (capturedBackdrop != null) {
      capturedBackdrop.recycle();
      capturedBackdrop = null;
    }
    blurNode = null;
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    if (capturedBackdrop != null) {
      capturedBackdrop.recycle();
      capturedBackdrop = null;
    }
    blurNode = null;
  }

  // ── draw ──────────────────────────────────────────────────────────────

  @Override
  protected void onDraw(Canvas canvas) {
    if (capturing) return;
    int w = getWidth(), h = getHeight();
    if (w <= 0 || h <= 0) return;

    int pad = getBlurPad();
    captureBackdrop(w, h, pad);

    float[] radii = getCornerRadii();
    clipPath.reset();
    clipPath.addRoundRect(0, 0, w, h, radii, Path.Direction.CW);
    canvas.save();
    canvas.clipPath(clipPath);

    // Blurred backdrop, isolated inside its own RenderNode.
    // We draw a larger RenderNode to include the "context" padding,
    // then translate it so the blurred content aligns perfectly.
    int nw = w + 2 * pad;
    int nh = h + 2 * pad;
    if (blurNode == null || blurNodeW != nw || blurNodeH != nh) rebuildBlurNode(nw, nh);

    Canvas nodeCanvas = blurNode.beginRecording();
    if (capturedBackdrop != null) {
      nodeCanvas.drawBitmap(capturedBackdrop, 0, 0, backdropPaint);
    }
    blurNode.endRecording();

    canvas.save();
    canvas.translate(-pad, -pad);
    canvas.drawRenderNode(blurNode);
    canvas.restore();

    // Single translucent tint for legibility.
    canvas.drawColor(TINT_COLOR);

    // Subtle top-edge specular ("lit rim").
    // Reduced height and opacity to prevent "glow" on small components.
    float dp = getResources().getDisplayMetrics().density;
    float specH = 3f * dp;
    overlayPaint.setShader(new LinearGradient(
            0, 0, 0, specH,
            0x20FFFFFF, 0x00FFFFFF,
            Shader.TileMode.CLAMP
    ));
    canvas.drawRect(0, 0, w, specH, overlayPaint);

    canvas.restore();

    // Border drawn last, outside the clip, so the hairline sits cleanly
    // on the edge of the shape.
    drawGlassBorder(canvas, w, h, radii);
  }

  private void captureBackdrop(int w, int h, int pad) {
    View root = getRootView();
    int bw = w + 2 * pad;
    int bh = h + 2 * pad;

    if (capturedBackdrop == null
            || capturedBackdrop.getWidth() != bw
            || capturedBackdrop.getHeight() != bh) {
      if (capturedBackdrop != null) capturedBackdrop.recycle();
      capturedBackdrop = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
      captureCanvas = new Canvas(capturedBackdrop);
    }
    capturedBackdrop.eraseColor(Color.TRANSPARENT);
    try {
      capturing = true;
      setVisibility(INVISIBLE);
      int[] rootPos = new int[2], myPos = new int[2];
      root.getLocationOnScreen(rootPos);
      getLocationOnScreen(myPos);
      captureCanvas.save();
      // Offset capture by 'pad' to grab content outside our bounds
      captureCanvas.translate(-(myPos[0] - rootPos[0] - pad), -(myPos[1] - rootPos[1] - pad));
      root.draw(captureCanvas);
      captureCanvas.restore();
    } finally {
      setVisibility(VISIBLE);
      capturing = false;
    }
  }

  private float[] getCornerRadii() {
    Outline tmp = new Outline();
    try {
      getOutlineProvider().getOutline(this, tmp);
    } catch (Exception ignored) {
    }
    float r = 0f;
    try {
      java.lang.reflect.Method m =
              android.graphics.Outline.class.getDeclaredMethod("getRadius");
      m.setAccessible(true);
      Object val = m.invoke(tmp);
      if (val instanceof Float) r = (Float) val;
    } catch (Exception ignored) {
    }

    if (r > 0) return new float[]{r, r, r, r, r, r, r, r};
    float top = 28f * getResources().getDisplayMetrics().density;
    return new float[]{top, top, top, top, 0f, 0f, 0f, 0f};
  }

  private void drawGlassBorder(Canvas canvas, int w, int h, float[] radii) {
    float sw = borderPaint.getStrokeWidth();
    float half = sw / 2f;
    borderPath.reset();
    borderPath.addRoundRect(half, half, w - half, h - half, radii, Path.Direction.CW);
    canvas.drawPath(borderPath, borderPaint);
  }
}