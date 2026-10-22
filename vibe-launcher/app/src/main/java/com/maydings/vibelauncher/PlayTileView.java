package com.maydings.vibelauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * The play button with the picture alone: round (its corner radius half its size, set by Home), with a
 * play triangle, see-through like a top bar pill (pill_bg) so the picture shows under it. Holding OK fills
 * it with a translucent white wash from the reading side (setFill), like a chosen pill.
 */
public class PlayTileView extends View {

    private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path(), triangle = new Path();
    private float radius, fill;
    private boolean fromRight;

    public PlayTileView(Context context, AttributeSet attrs) {
        super(context, attrs);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(getResources().getDisplayMetrics().density);   // 1dp, as pill_bg's
        rim.setColor(0x38FFFFFF);
        light.setColor(0xF5FFFFFF);  // the triangle
        wash.setColor(0x66FFFFFF);   // the fill: about a chosen pill's white (pill_bg)
    }

    /** The tile's corner radius, as the focused tile's. */
    void setRadius(float px) {
        radius = px;
        rebuild(getWidth(), getHeight());
        invalidate();
    }

    /** How full, from 0 to 1, filling from the right for right-to-left languages. */
    void setFill(float fill, boolean fromRight) {
        this.fill = Math.max(0f, Math.min(1f, fill));
        this.fromRight = fromRight;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        rebuild(w, h);
    }

    private void rebuild(int w, int h) {
        shape.reset();
        triangle.reset();
        if (w == 0 || h == 0) return;
        shape.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        // pill_bg's unfocused fill: white fading from 18% at the top to 8% at the bottom.
        tile.setShader(new LinearGradient(0, 0, 0, h, 0x2EFFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        // A triangle 40% of the tile's height, nudged right so it looks centred.
        float t = h * 0.2f, cx = w / 2f, cy = h / 2f;
        triangle.moveTo(cx - t * 0.8f, cy - t);
        triangle.lineTo(cx + t * 1.1f, cy);
        triangle.lineTo(cx - t * 0.8f, cy + t);
        triangle.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawPath(shape, tile);
        canvas.drawPath(triangle, light);
        if (fill > 0f) {
            float filled = w * fill;
            canvas.save();
            if (fromRight) canvas.clipRect(w - filled, 0, w, h);
            else canvas.clipRect(0, 0, filled, h);
            canvas.drawPath(shape, wash);
            canvas.restore();
        }
        canvas.drawPath(shape, rim);
    }
}
