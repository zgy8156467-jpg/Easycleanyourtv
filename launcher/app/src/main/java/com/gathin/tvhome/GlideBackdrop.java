package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * The dark glass slots of one control-centre group, plus a white selection slab that glides
 * between them. Drawn as one layer underneath the items, which are themselves transparent.
 *
 * <p>Why one view instead of a focused background on every item: a state-list fill can only pop
 * on in place. Putting the fill here lets it travel, and lets it stay above the neighbouring
 * slots while doing so — the item children are drawn after this view, so labels stay readable
 * the whole way across.
 */
public final class GlideBackdrop extends View {

    private static final int WHITE_ALPHA = 0xF2;

    private final List<RectF> slots = new ArrayList<>();
    private final RectF current = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float radius;
    private boolean oval;
    private int focused = -1;
    private boolean placed;
    private ValueAnimator slide;
    private ValueAnimator fade;

    public GlideBackdrop(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        fill.setColor(0x66000000);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(density);
        rim.setColor(0x33FFFFFF);
        white.setColor(0xFFFFFFFF);
        white.setAlpha(0);
    }

    void setOval() {
        oval = true;
        invalidate();
    }

    void setRadiusDp(float dp) {
        radius = dp * density;
        invalidate();
    }

    /** Invert for the light sheet: faint ink slots, near-black selection, white glyphs on it. */
    void setLight() {
        fill.setColor(0x14000000);
        rim.setColor(0x1F000000);
        white.setColor(0xFF111114);
        invalidate();
    }

    /** Rects in this view's coordinates, in the same order as the focus() indices. */
    void setSlots(List<RectF> rects) {
        boolean same = rects.size() == slots.size();
        if (same) {
            for (int i = 0; i < rects.size(); i++) {
                if (!rects.get(i).equals(slots.get(i))) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return;
        slots.clear();
        for (RectF r : rects) slots.add(new RectF(r));
        if (focused >= 0 && focused < slots.size()) {
            if (!placed) {
                current.set(slots.get(focused));
                placed = true;
                white.setAlpha(WHITE_ALPHA);
            } else {
                slideTo(slots.get(focused), true);
            }
        }
        invalidate();
    }

    void focus(int index, boolean animate) {
        if (index < 0 || index >= slots.size()) {
            focused = index;
            return;
        }
        focused = index;
        cancelFade();
        if (!placed || !animate) {
            if (slide != null) slide.cancel();
            current.set(slots.get(index));
            placed = true;
        } else {
            slideTo(slots.get(index), true);
        }
        white.setAlpha(WHITE_ALPHA);
        invalidate();
    }

    int focused() {
        return focused;
    }

    void unfocus() {
        if (focused < 0) return;
        focused = -1;
        if (slide != null) slide.cancel();
        // Fade where it stands: snapping off reads as a glitch next to the glide in.
        cancelFade();
        fade = ValueAnimator.ofInt(white.getAlpha(), 0);
        fade.setDuration(140);
        fade.addUpdateListener(a -> {
            white.setAlpha((int) a.getAnimatedValue());
            invalidate();
        });
        fade.start();
    }

    private void cancelFade() {
        if (fade != null) {
            fade.cancel();
            fade = null;
        }
    }

    private void slideTo(RectF target, boolean animate) {
        if (!animate) {
            current.set(target);
            invalidate();
            return;
        }
        if (slide != null) slide.cancel();
        final RectF start = new RectF(current);
        final RectF end = new RectF(target);
        slide = ValueAnimator.ofFloat(0f, 1f);
        slide.setDuration(260);
        slide.setInterpolator(new DecelerateInterpolator(1.6f));
        slide.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            current.left = start.left + (end.left - start.left) * f;
            current.top = start.top + (end.top - start.top) * f;
            current.right = start.right + (end.right - start.right) * f;
            current.bottom = start.bottom + (end.bottom - start.bottom) * f;
            invalidate();
        });
        slide.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (RectF r : slots) {
            if (oval) canvas.drawOval(r, fill);
            else canvas.drawRoundRect(r, radius, radius, fill);
            if (oval) canvas.drawOval(r, rim);
            else canvas.drawRoundRect(r, radius, radius, rim);
        }
        if (white.getAlpha() > 0 && !current.isEmpty()) {
            if (oval) canvas.drawOval(current, white);
            else canvas.drawRoundRect(current, radius, radius, white);
        }
    }
}
