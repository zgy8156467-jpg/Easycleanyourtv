package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it.
 *
 * <p>The band is now a frosted glass slab rather than an opaque near-black one: a faint white gradient
 * with a hairline rim, so it reads as a lift both on pure black and over a bright poster. The old
 * "opaque on black, translucent over a picture" switch is gone; glass works in both cases.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Shader shader;
    private float shaderTop, shaderBottom;

    /** @param overWallpaper kept for call-site compatibility; the glass band is the same either way. */
    DockDecoration(int dockSize, float density, boolean overWallpaper) {
        this.dockSize = dockSize;
        this.density = density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x40FFFFFF);
        setOverWallpaper(overWallpaper);
    }

    void setOverWallpaper(boolean overWallpaper) {
        // No colour switch any more: a translucent white gradient stays legible on black and on art.
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = 14 * density;
        rect.inset(-pad, -pad);
        if (shader == null || rect.top != shaderTop || rect.bottom != shaderBottom) {
            shaderTop = rect.top;
            shaderBottom = rect.bottom;
            shader = new LinearGradient(0, shaderTop, 0, shaderBottom,
                    0x2EFFFFFF, 0x0FFFFFFF, Shader.TileMode.CLAMP);
            band.setShader(shader);
        }
        float radius = 26 * density;
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
