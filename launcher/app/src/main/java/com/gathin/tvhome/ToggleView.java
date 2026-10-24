package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The Wi-Fi switch, drawn by hand: the platform Switch drags its theme, its padding and its
 * label along, and on a light pane those are exactly the parts that must not vary by TV ROM.
 * On is ink, matching the control centre's selection fill; blue would be the only hue on the
 * sheet.
 */
public class ToggleView extends View {

    private static final int TRACK_ON = 0xFF111114, TRACK_OFF = 0xFFE3E3E8;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean on;

    public ToggleView(Context context) {
        super(context);
    }

    public ToggleView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setOn(boolean value) {
        on = value;
        invalidate();
    }

    boolean isOn() {
        return on;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        // The view is a ring's width larger than the track, so focus can draw outside the track
        // instead of trying to find contrast on top of it.
        float edge = getResources().getDisplayMetrics().density * 3f;
        float r = (h - edge * 2) / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(on ? TRACK_ON : TRACK_OFF);
        canvas.drawRoundRect(new RectF(edge, edge, w - edge, h - edge), r, r, paint);
        float thumbInset = Math.max(1.5f, (h - edge * 2) * 0.1f);
        float d = h - edge * 2 - thumbInset * 2;
        float x = edge + thumbInset + (w - edge * 2 - thumbInset * 2 - d) * (on ? 1f : 0f);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x + d / 2, h / 2, d / 2, paint);
        if (isFocused()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.5f);
            paint.setColor(0xCC0A84FF);
            canvas.drawRoundRect(new RectF(1, 1, w - 1, h - 1), h / 2f - 1, h / 2f - 1, paint);
        }
    }
}
