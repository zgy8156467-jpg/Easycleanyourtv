package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.WeakHashMap;

/**
 * Focus feedback borrowed from tvOS: a light band sweeps across a focused card once, and the card
 * lifts a little so it casts a shadow. Attach once per view, then call {@link #onFocus} from the
 * focus listener that already does the scale.
 */
final class FocusFx {
    private static final float LIFT_DP = 8f;
    private static final long SWEEP_MS = 620;
    /** One sweep at a time per view; a fast D-pad cancels the old one rather than stacking. */
    private static final WeakHashMap<View, ValueAnimator> SWEEPS = new WeakHashMap<>();
    private static final WeakHashMap<View, Sheen> SHEENS = new WeakHashMap<>();

    private FocusFx() {}

    /** White glare, for cards on the dark desk and the dark selection inside the control panel. */
    static void attach(View v) {
        attach(v, 0xFFFFFF);
    }

    /**
     * @param rgb band colour without alpha; alpha peaks at ~22% mid-sweep. Pass a dark colour on
     *            light panes (the Wi-Fi sheet), where a white band would disappear.
     */
    static void attach(View v, int rgb) {
        // The foreground is the only layer every one of these layouts has, and it draws above
        // children. ViewOverlay looked right but never reached the screen on this firmware.
        if (Build.VERSION.SDK_INT < 23 || SHEENS.containsKey(v)) return;
        // ponytail: the sweep redraws the whole card every frame (a fresh LinearGradient each
        // time) and the lift forces a shadow render; on the 2016-era boxes that shows up as
        // dropped D-pad repeats. Those sets keep the plain scale feedback they had before.
        if (FrameRate.weak(v.getContext()) || FrameRate.reducedMotion(v.getContext())) return;
        Sheen sheen = new Sheen(rgb);
        Drawable existing = v.getForeground();
        v.setForeground(existing == null ? sheen : new LayerDrawable(new Drawable[]{existing, sheen}));
        SHEENS.put(v, sheen);
    }

    static void onFocus(View v, boolean focused) {
        onFocus(v, focused, true);
    }

    /**
     * @param lift true where the view has a rounded outline of its own (grid and dock cards). The
     *             control panel's rows sit over a shared selection slab, so they only get the
     *             sweep, never a shadow of their own.
     */
    static void onFocus(View v, boolean focused, boolean lift) {
        // No sheen means the effect was skipped for this device (see attach); the lift must go
        // with it or the shadow render alone still costs frames on the weak boxes.
        Sheen sheen = SHEENS.get(v);
        if (sheen == null) return;
        if (lift && Build.VERSION.SDK_INT >= 21) {
            float dz = focused ? LIFT_DP * v.getResources().getDisplayMetrics().density : 0f;
            v.animate().translationZ(dz)
                    .setDuration(focused ? 220 : 160)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
        ValueAnimator old = SWEEPS.remove(v);
        if (old != null) old.cancel();
        if (!focused) {
            sheen.progress(-1f);
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(SWEEP_MS);
        a.setInterpolator(new DecelerateInterpolator(1.4f));
        a.addUpdateListener(anim -> sheen.progress((float) anim.getAnimatedValue()));
        a.start();
        SWEEPS.put(v, a);
    }

    /** The band. Progress -1 hides it; 0..1 walks it from off-left to off-right. */
    private static final class Sheen extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int rgb;
        private float t = -1f;

        Sheen(int rgb) {
            this.rgb = rgb & 0xFFFFFF;
        }

        void progress(float p) {
            t = p;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            if (t < 0f || t > 1f) return;
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            if (w <= 0 || h <= 0) return;
            // Sin gives a fade in and out, so the band never pops on or off at the edges.
            int alpha = (int) (0x38 * Math.sin(Math.PI * t));
            if (alpha <= 0) return;
            float band = w * 0.30f + h * 0.22f;
            float x = -band + t * (w + 2f * band);
            int color = (alpha << 24) | rgb;
            paint.setShader(new LinearGradient(
                    x - band, -h * 0.15f,
                    x + band, h * 1.15f,
                    new int[]{0, color, 0}, null, Shader.TileMode.CLAMP));
            canvas.save();
            canvas.clipRect(b);
            canvas.drawRect(b, paint);
            canvas.restore();
            paint.setShader(null);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
