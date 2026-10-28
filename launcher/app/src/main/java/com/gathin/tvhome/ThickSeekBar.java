package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.animation.DecelerateInterpolator;
import android.widget.SeekBar;

/**
 * SeekBar drawn as the iOS Control Centre capsule, rotated for a landscape TV: one fat rounded
 * pill, a white fill that grows from the left, and no knob at all.
 *
 * <p>The fill is the capsule clipped at the level, but built as one anti-aliased path so the
 * edges stay smooth and never poke out of the pill's rounded ends. The drawn level follows the
 * real progress with a short animation, so a held key slides instead of stepping.
 *
 * <p>The platform's own track and thumb are replaced with transparent stubs, so the remote's
 * left/right keys and accessibility keep working unchanged — only the drawing is ours.
 */
public final class ThickSeekBar extends SeekBar {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final RectF rect = new RectF();
    private final Path path = new Path();

    private float shown;
    private int animTarget = Integer.MIN_VALUE;
    private boolean placed;
    private ValueAnimator animator;

    public ThickSeekBar(Context context) {
        this(context, null);
    }

    public ThickSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setPadding(0, 0, 0, 0);
        // No setSplitTrack: we draw the whole slider in onDraw and never call super.onDraw, so the
        // platform track is never painted. It was gated on 21+ (KitKat has no such method and
        // threw NoSuchMethodError opening the control centre), but the call was dead either way.
        setProgressDrawable(new ColorDrawable(Color.TRANSPARENT));
        setThumb(new ColorDrawable(Color.TRANSPARENT));
        setThumbOffset(0);
        if (Build.VERSION.SDK_INT >= 16) setBackground(null);
        else setBackgroundDrawable(null);
        // Without this the remote skips the sliders entirely and left/right never reach them.
        setFocusable(true);
        setFocusableInTouchMode(false);
    }

    /**
     * Eases the drawn level toward the real one. Driven from onDraw rather than setProgress:
     * the remote's keys change progress through ProgressBar's internal path, which never calls
     * the public setProgress(int).
     */
    private void follow() {
        int target = getProgress();
        if (!placed) {
            // First paint: the panel's opening value, not a change to glide from zero.
            placed = true;
            shown = target;
            animTarget = target;
            return;
        }
        if (target == animTarget) return;
        animTarget = target;
        if (animator != null) animator.cancel();
        final float start = shown, end = target;
        if (start == end) return;
        animator = ValueAnimator.ofFloat(start, end);
        animator.setDuration(140);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        follow();
        float r = h / 2f;

        // The track is nearly opaque: a see-through pill picks up the frost's colour blotches and
        // reads as dirt at couch distance.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6D2D2D7);
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, paint);

        float fraction = getMax() > 0 ? shown / getMax() : 0f;
        float x = w * Math.max(0f, Math.min(1f, fraction));
        if (x > 0.01f) {
            // Ink while the panel is merely showing, white while the slider is the selection —
            // the same switch the tiles make.
            paint.setColor(isFocused() ? 0xFFFFFFFF : 0xFF111114);
            path.reset();
            if (x < r) {
                // Below one radius the cut sits on the left cap itself: follow the cap's arc so
                // the white stays inside the pill instead of poking out above and below.
                float dy = (float) Math.sqrt(r * r - (r - x) * (r - x));
                float beta = (float) Math.toDegrees(Math.atan2(dy, r - x));
                path.moveTo(x, h / 2f - dy);
                rect.set(0, 0, 2 * r, h);
                path.arcTo(rect, -180 + beta, -2 * beta, false);
                path.close();
            } else if (x <= w - r) {
                // The normal case: left cap, straight edge at the level.
                path.moveTo(x, 0);
                path.lineTo(r, 0);
                rect.set(0, 0, 2 * r, h);
                path.arcTo(rect, -90, -180, false);
                path.lineTo(x, h);
                path.close();
            } else {
                // Past the right cap the fill has to come back down the curve, or the level would
                // stick out of the pill's right end the same way it did on the left.
                float d = x - (w - r);
                float dy = (float) Math.sqrt(r * r - d * d);
                float gamma = (float) Math.toDegrees(Math.acos(Math.min(1f, d / r)));
                rect.set(w - 2 * r, 0, w, h);
                path.moveTo(r, 0);
                path.lineTo(w - r, 0);
                path.arcTo(rect, -90, 90 - gamma, false);
                path.lineTo(x, h / 2f + dy);
                path.arcTo(rect, gamma, 90 - gamma, false);
                path.lineTo(r, h);
                rect.set(0, 0, 2 * r, h);
                path.arcTo(rect, 90, 180, false);
                path.close();
            }
            canvas.drawPath(path, paint);

            // A hairline where the white ends keeps the edge readable against pale grey. Only the
            // straight middle edge has one; at the caps the level is the cap's own curve.
            if (x >= r && x <= w - r) {
                paint.setColor(0x1F000000);
                canvas.drawRect(x, 0, x + density, h, paint);
            }
        }

        // A hairline rim keeps the pale pill from dissolving into the pale frost.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(0x1F000000);
        rect.set(density * 0.5f, density * 0.5f, w - density * 0.5f, h - density * 0.5f);
        canvas.drawRoundRect(rect, r - density * 0.5f, r - density * 0.5f, paint);

        if (isFocused()) {
            float inset = 2f * density;
            paint.setStrokeWidth(density * 2f);
            paint.setColor(0xCC0A84FF);
            rect.set(inset, inset, w - inset, h - inset);
            canvas.drawRoundRect(rect, (h - inset * 2) / 2f, (h - inset * 2) / 2f, paint);
        }
    }
}
