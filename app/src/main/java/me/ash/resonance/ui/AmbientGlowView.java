package me.ash.resonance.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import me.ash.resonance.util.DominantColorExtractor;

/**
 * A sophisticated ambient wash view that creates a premium, atmospheric effect.
 * It blends artwork colors with the background and uses large, overlapping gradients.
 */
public class AmbientGlowView extends View {

  private static final int BASE_BACKGROUND = 0xFF010101; // Matches bg_primary
  private static final float MAX_LUMINANCE = 0.25f; // Keep it dark
  private static final float TARGET_SATURATION = 0.35f; // Desaturate for premium feel
  private static final float BLEND_RATIO = 0.25f; // 25% artwork color, 75% background

  private static final int SCALE_FACTOR = 8; // Render at 1/8th resolution for performance
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final android.graphics.Matrix shaderMatrix = new android.graphics.Matrix();

  private int colorTopLeft = BASE_BACKGROUND;
  private int colorBottomRight = BASE_BACKGROUND;
  private int colorCenter = BASE_BACKGROUND;

  private RadialGradient g1, g2, g3;
  private int lastW, lastH;
  private int lastC1, lastC2, lastC3;

  private android.graphics.Bitmap buffer;
  private Canvas bufferCanvas;
  private final android.graphics.Rect srcRect = new android.graphics.Rect();
  private final android.graphics.Rect dstRect = new android.graphics.Rect();

  private ValueAnimator animator;
  private ValueAnimator movementAnimator;
  private float phase = 0f;

  public AmbientGlowView(Context context) {
    super(context);
    init();
  }

  public AmbientGlowView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  private void init() {
    setLayerType(LAYER_TYPE_HARDWARE, null);
    // Lower blur radius because we are scaling up a low-res buffer
    float blurRadius = 40f * getResources().getDisplayMetrics().density;
    setRenderEffect(RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    int w = getWidth();
    int h = getHeight();
    if (w == 0 || h == 0) return;

    int bw = Math.max(1, w / SCALE_FACTOR);
    int bh = Math.max(1, h / SCALE_FACTOR);

    if (buffer == null || buffer.getWidth() != bw || buffer.getHeight() != bh) {
      if (buffer != null) buffer.recycle();
      buffer = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
      bufferCanvas = new Canvas(buffer);
      srcRect.set(0, 0, bw, bh);
    }
    dstRect.set(0, 0, w, h);

    updateShaders(bw, bh);

    // Draw onto the low-res buffer
    bufferCanvas.drawColor(BASE_BACKGROUND);

    // Calculate subtle offsets for movement
    float offX1 = (float) Math.sin(phase) * (bw * 0.12f);
    float offY1 = (float) Math.cos(phase * 0.8f) * (bh * 0.1f);

    float offX2 = (float) Math.cos(phase * 1.2f) * (bw * 0.15f);
    float offY2 = (float) Math.sin(phase * 0.9f) * (bh * 0.08f);

    float offX3 = (float) Math.sin(phase * 0.5f) * (bw * 0.1f);
    float offY3 = (float) Math.cos(phase * 0.6f) * (bh * 0.05f);

    // Gradient 1: Top Left
    shaderMatrix.setTranslate(offX1, offY1);
    g1.setLocalMatrix(shaderMatrix);
    paint.setShader(g1);
    bufferCanvas.drawRect(0, 0, bw, bh, paint);

    // Gradient 2: Bottom Right
    shaderMatrix.setTranslate(bw + offX2, bh + offY2);
    g2.setLocalMatrix(shaderMatrix);
    paint.setShader(g2);
    bufferCanvas.drawRect(0, 0, bw, bh, paint);

    // Gradient 3: Center
    shaderMatrix.setTranslate(bw / 2f + offX3, bh / 3f + offY3);
    g3.setLocalMatrix(shaderMatrix);
    paint.setShader(g3);
    bufferCanvas.drawRect(0, 0, bw, bh, paint);

    // Draw the low-res buffer scaled up to the main canvas
    paint.setShader(null);
    canvas.drawBitmap(buffer, srcRect, dstRect, paint);
  }

  private void updateShaders(int w, int h) {
    if (w == lastW && h == lastH && colorTopLeft == lastC1 && colorBottomRight == lastC2 && colorCenter == lastC3) {
      return;
    }

    g1 = new RadialGradient(0, 0, w * 1.6f, new int[]{colorTopLeft, 0x00000000}, null, Shader.TileMode.CLAMP);
    g2 = new RadialGradient(0, 0, w * 1.6f, new int[]{colorBottomRight, 0x00000000}, null, Shader.TileMode.CLAMP);
    g3 = new RadialGradient(0, 0, w * 0.9f, new int[]{colorCenter, 0x00000000}, null, Shader.TileMode.CLAMP);

    lastW = w;
    lastH = h;
    lastC1 = colorTopLeft;
    lastC2 = colorBottomRight;
    lastC3 = colorCenter;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    startMovementAnimation();
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    stopMovementAnimation();
    if (animator != null) animator.cancel();
    if (buffer != null) {
      buffer.recycle();
      buffer = null;
    }
  }

  private void startMovementAnimation() {
    if (movementAnimator != null) return;
    movementAnimator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
    movementAnimator.setDuration(15000); // 15 seconds for a full cycle
    movementAnimator.setRepeatCount(ValueAnimator.INFINITE);
    movementAnimator.setInterpolator(new LinearInterpolator());
    movementAnimator.addUpdateListener(animation -> {
      phase = (float) animation.getAnimatedValue();
      invalidate();
    });
    movementAnimator.start();
  }

  private void stopMovementAnimation() {
    if (movementAnimator != null) {
      movementAnimator.cancel();
      movementAnimator = null;
    }
  }

  public void setColors(@NonNull DominantColorExtractor.GeneratedPalette palette) {
    int raw1 = palette.vibrant != 0 ? palette.vibrant : palette.dominant;
    int raw2 = palette.muted != 0 ? palette.muted : palette.darkVibrant;
    int raw3 = palette.dominant;

    int target1 = processAndBlend(raw1);
    int target2 = processAndBlend(raw2);
    int target3 = processAndBlend(raw3);

    animateToColors(target1, target2, target3);
  }

  private int processAndBlend(@ColorInt int color) {
    if (color == 0) return BASE_BACKGROUND;

    float[] hsl = new float[3];
    ColorUtils.colorToHSL(color, hsl);

    // 1. Desaturate
    hsl[1] = Math.min(hsl[1], TARGET_SATURATION);
    // 2. Clamp Luminance (ensure it stays dark)
    hsl[2] = Math.min(hsl[2], MAX_LUMINANCE);

    int processed = ColorUtils.HSLToColor(hsl);

    // 3. Blend with base background
    return ColorUtils.blendARGB(BASE_BACKGROUND, processed, BLEND_RATIO);
  }

  private void animateToColors(int targetTopLeft, int targetBottomRight, int targetCenter) {
    if (animator != null) animator.cancel();

    final int startTopLeft = colorTopLeft;
    final int startBottomRight = colorBottomRight;
    final int startCenter = colorCenter;

    ArgbEvaluator evaluator = new ArgbEvaluator();
    animator = ValueAnimator.ofFloat(0f, 1f);
    animator.setDuration(2000); // Slower, more premium transition
    animator.setInterpolator(new AccelerateDecelerateInterpolator());
    animator.addUpdateListener(animation -> {
      float fraction = (float) animation.getAnimatedValue();
      colorTopLeft = (int) evaluator.evaluate(fraction, startTopLeft, targetTopLeft);
      colorBottomRight = (int) evaluator.evaluate(fraction, startBottomRight, targetBottomRight);
      colorCenter = (int) evaluator.evaluate(fraction, startCenter, targetCenter);
      // No invalidate here; movement animator handles it at 60fps
    });
    animator.start();
  }
}
