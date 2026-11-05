package com.gathin.tvhome;

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
 * A translucent glass slab: soft sheen fill, bright top specular, crisp rim and a faint outer glow.
 * One instance floats over the grid and is moved with spring animations between tiles.
 */
public class GlassView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF outer = new RectF();
    private final RectF band = new RectF();
    /** Top corners follow the slab, bottom corners are square; the gradient is done by then. */
    private final float[] bandRadii = new float[8];
    private final Path clip = new Path();
    private final float density;
    private float radius;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.25f * density);
        rim.setColor(0xE6FFFFFF);
        glow.setStyle(Paint.Style.STROKE);
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x2EFFFFFF, 0x0DFFFFFF, Shader.TileMode.CLAMP));
        specular.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x8AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the glow
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // Outer glow: three widening, fading strokes.
        for (int i = 1; i <= 3; i++) {
            glow.setStrokeWidth(i * 2f * density);
            glow.setColor((0x38 / i) << 24 | 0xFFFFFF);
            float o = i * density;
            outer.set(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o);
            canvas.drawRoundRect(outer, radius + o, radius + o, glow);
        }

        canvas.drawRoundRect(rect, radius, radius, fill);

        // No clipPath: the hardware canvas on Android 4.0 has none and throws (GLES20Canvas,
        // seen on a Haier Amber3). A path shaped like the band draws the same pixels — and its
        // square bottom edge is invisible because the gradient has already run out there.
        band.set(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f);
        bandRadii[0] = bandRadii[1] = bandRadii[2] = bandRadii[3] = radius;
        bandRadii[4] = bandRadii[5] = bandRadii[6] = bandRadii[7] = 0f;
        clip.reset();
        clip.addRoundRect(band, bandRadii, Path.Direction.CW);
        canvas.drawPath(clip, specular);

        canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
