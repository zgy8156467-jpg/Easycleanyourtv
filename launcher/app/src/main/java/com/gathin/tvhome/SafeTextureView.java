package com.gathin.tvhome;

import android.content.Context;
import android.view.TextureView;
import android.view.View;

/**
 * KitKat's TextureView touches a released SurfaceTexture in onVisibilityChanged and throws
 * NullPointerException when the window's visibility flips with no surface behind the view. The
 * desk hides and shows its window constantly (Home pressed, an app opened), so on a 4.4 MiTV3S
 * this crashed the launcher every time. Skipping the framework wiring while there is no surface
 * is safe — onSurfaceTextureAvailable registers everything again when one appears.
 */
final class SafeTextureView extends TextureView {

    SafeTextureView(Context context) {
        super(context);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        if (getSurfaceTexture() == null) return;
        super.onVisibilityChanged(changedView, visibility);
    }
}
