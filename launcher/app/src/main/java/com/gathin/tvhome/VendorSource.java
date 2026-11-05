package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

/**
 * The set's own signal-source app, for TVs that predate TV Input Framework (API 21) and publish no
 * port table of their own.
 *
 * <p>LeTV eUI (S50 Air, Android 4.3; X3-55, Android 5.0.1) ships
 * {@code com.stv.signalsourcemanager}, an exported activity that shows the source picture and
 * switches to the input used last. It takes no port id — the remote's source key picks another
 * port from inside it. MStar's generic MTvPlayer exposes the same page as
 * {@code com.mstar.tv.tvplayer.ui/.channel.SourceInfoActivity}. Both are ordinary exported
 * activities: no permission and no tv_input service involved.
 */
final class VendorSource {

    private static final ComponentName[] PANELS = {
            new ComponentName("com.stv.signalsourcemanager",
                    "com.stv.signalsourcemanager.MainActivity"),
            new ComponentName("com.mstar.tv.tvplayer.ui",
                    "com.mstar.tv.tvplayer.ui.channel.SourceInfoActivity"),
    };

    private VendorSource() {
    }

    /** True when this TV has a source page we can start. */
    static boolean present(Context ctx) {
        for (ComponentName panel : PANELS) if (exported(ctx, panel)) return true;
        return false;
    }

    /** Open the set's source page. False when it would not start, so the caller can say so. */
    static boolean open(Context ctx) {
        for (ComponentName panel : PANELS) {
            if (!exported(ctx, panel)) continue;
            Intent i = new Intent(Intent.ACTION_MAIN).setComponent(panel)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
                return true;
            } catch (RuntimeException ignored) {
                // Fall through to the next panel.
            }
        }
        return false;
    }

    /**
     * getActivityInfo, not resolveActivity: on some Sony sets an explicit component resolved even
     * though its package was never installed (see DeviceProfile.installed).
     */
    private static boolean exported(Context ctx, ComponentName cn) {
        try {
            ActivityInfo info = ctx.getPackageManager().getActivityInfo(cn, 0);
            return info.exported && info.enabled && info.applicationInfo.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
