package com.maydings.vibelauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.Arrays;

/**
 * Frosted backdrop for the top bar: a blurred copy of whatever part of the grid has scrolled under it.
 * Android 9 has no live blur, so on each frame the covered strip of the grid is drawn into a bitmap at
 * 1/8 size, box-blurred and stretched back up. It fades in only while tiles are actually underneath,
 * so the dock view (and its ambient glow) is untouched.
 */
public class FrostView extends View {

    private static final int SCALE = 8;           // the strip is drawn at 1/8 size
    private static final int BLUR_RADIUS = 2;     // in downscaled pixels, three passes ~ a gaussian
    private static final int TINT = 0x8C000000;   // darkens the frost so the clock and buttons stay legible
    private static final int HAIRLINE = 0x26FFFFFF;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint();
    private final Rect dst = new Rect();
    private final int[] sourceLoc = new int[2];
    private final int[] myLoc = new int[2];
    private final float fadeDistance;

    private ViewGroup source;
    private Bitmap small;
    private Canvas smallCanvas;
    private int[] pixels, scratch, shown;
    /** 0 = nothing under the bar (invisible), 1 = fully frosted. */
    private float amount;

    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        update();
        return true;
    };

    public FrostView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fadeDistance = 24f * getResources().getDisplayMetrics().density;
    }

    /** No height of its own: it fills whatever its parent sizes to (the top bar). */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec) : 0;
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h);
    }

    /** The view to frost (the app grid). */
    void setSource(ViewGroup grid) {
        source = grid;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Invalidating from a pre-draw callback joins the frame being drawn; it doesn't schedule another.
        getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        super.onDetachedFromWindow();
    }

    /**
     * Runs before every frame. Only invalidates when the frost actually changed: invalidating
     * unconditionally kept the launcher drawing 60 frames a second while idle.
     */
    private void update() {
        if (source == null || getWidth() == 0 || getHeight() == 0) return;
        float a = coverage();
        if (a <= 0f) {
            if (amount != 0f) {
                amount = 0f;
                invalidate();
            }
            return;
        }
        boolean amountChanged = a != amount;
        amount = a;

        int w = Math.max(1, getWidth() / SCALE), h = Math.max(1, getHeight() / SCALE);
        if (small == null || small.getWidth() != w || small.getHeight() != h) {
            small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            smallCanvas = new Canvas(small);
            pixels = new int[w * h];
            scratch = new int[w * h];
            shown = null;
        }
        source.getLocationInWindow(sourceLoc);
        getLocationInWindow(myLoc);
        small.eraseColor(Color.BLACK);
        smallCanvas.save();
        smallCanvas.scale(1f / SCALE, 1f / SCALE);
        smallCanvas.translate(sourceLoc[0] - myLoc[0], sourceLoc[1] - myLoc[1]);
        try {
            source.draw(smallCanvas);
        } catch (RuntimeException e) {
            // e.g. a hardware-only bitmap that a software canvas can't draw: frost what we have.
        }
        smallCanvas.restore();

        small.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(pixels, scratch, w, h, BLUR_RADIUS, true);
            boxBlur(scratch, pixels, w, h, BLUR_RADIUS, false);
        }
        if (!amountChanged && shown != null && Arrays.equals(pixels, shown)) return;
        shown = pixels.clone();
        small.setPixels(pixels, 0, w, 0, 0, w, h);
        invalidate();
    }

    /** How far tiles reach up into the bar, eased over {@link #fadeDistance}. */
    private float coverage() {
        getLocationInWindow(myLoc);
        int barTop = myLoc[1], barBottom = barTop + getHeight();
        int reach = 0;
        for (int i = 0; i < source.getChildCount(); i++) {
            View child = source.getChildAt(i);
            child.getLocationInWindow(sourceLoc);
            int top = sourceLoc[1] + (int) child.getTranslationY();
            int bottom = top + child.getHeight();
            if (bottom > barTop && top < barBottom) reach = Math.max(reach, barBottom - top);
        }
        return Math.min(1f, reach / fadeDistance);
    }

    /** One horizontal or vertical box-blur pass over opaque ARGB pixels. */
    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int p = in[base + clamp(k, len) * step];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[base + i * step] = 0xFF000000 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int add = in[base + clamp(i + r + 1, len) * step];
                int sub = in[base + clamp(i - r, len) * step];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (amount <= 0f || small == null) return;
        int alpha = (int) (255 * amount);
        dst.set(0, 0, getWidth(), getHeight());
        bitmapPaint.setAlpha(alpha);
        canvas.drawBitmap(small, null, dst, bitmapPaint);

        fillPaint.setColor(scaleAlpha(TINT, amount));
        canvas.drawRect(dst, fillPaint);
        float line = getResources().getDisplayMetrics().density;
        fillPaint.setColor(scaleAlpha(HAIRLINE, amount));
        canvas.drawRect(0, getHeight() - line, getWidth(), getHeight(), fillPaint);
    }

    private static int scaleAlpha(int color, float f) {
        return ((int) ((color >>> 24) * f) << 24) | (color & 0x00FFFFFF);
    }
}
