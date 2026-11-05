package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

/**
 * Frame-rate options for three different things: the panel's refresh rate, the desk's animation
 * rate, and the video wallpaper's playback rate. "Auto" reads what the box actually is — RAM, SDK
 * level and the modes the display reports — instead of assuming every TV is a 60 Hz flagship.
 */
final class FrameRate {
    private static final String PREFS = "frame_rate";
    private static final String KEY_REFRESH = "refresh_mode";
    private static final String KEY_UI = "ui_fps";
    private static final String KEY_VIDEO = "video_fps";

    private FrameRate() {
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Display mode id, or 0 to let the system pick. */
    static int refreshMode(Context c) {
        return prefs(c).getInt(KEY_REFRESH, 0);
    }

    static void setRefreshMode(Context c, int id) {
        prefs(c).edit().putInt(KEY_REFRESH, id).apply();
    }

    /** "auto", "60", "30" or "off". */
    static String uiChoice(Context c) {
        return prefs(c).getString(KEY_UI, "auto");
    }

    static void setUiChoice(Context c, String v) {
        prefs(c).edit().putString(KEY_UI, v).apply();
    }

    /** "auto", "source", "24" or "30". */
    static String videoChoice(Context c) {
        return prefs(c).getString(KEY_VIDEO, "auto");
    }

    static void setVideoChoice(Context c, String v) {
        prefs(c).edit().putString(KEY_VIDEO, v).apply();
    }

    /** Distinct refresh rates the panel offers, highest first. */
    static int[] rates(Context c) {
        if (Build.VERSION.SDK_INT < 23) return new int[0];
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm == null ? null : wm.getDefaultDisplay();
        if (d == null) return new int[0];
        java.util.TreeSet<Integer> rates =
                new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        for (Display.Mode m : d.getSupportedModes()) rates.add(Math.round(m.getRefreshRate()));
        int[] out = new int[rates.size()];
        int i = 0;
        for (int r : rates) out[i++] = r;
        return out;
    }

    static int currentHz(Context c) {
        if (Build.VERSION.SDK_INT < 23) return 60;
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm == null ? null : wm.getDefaultDisplay();
        return d == null ? 60 : Math.round(d.getRefreshRate());
    }

    /** The mode id for a refresh rate, or 0 when the panel has no such mode. */
    static int modeFor(Context c, int hz) {
        if (Build.VERSION.SDK_INT < 23 || hz == 0) return 0;
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm == null ? null : wm.getDefaultDisplay();
        if (d == null) return 0;
        for (Display.Mode m : d.getSupportedModes()) {
            if (Math.round(m.getRefreshRate()) == hz) return m.getModeId();
        }
        return 0;
    }

    /** Cheap boxes: under 1.5 GB of RAM or an Android older than 7.0. */
    static boolean weak(Context c) {
        if (Build.VERSION.SDK_INT < 24) return true;
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem > 0 && mi.totalMem < 1536L * 1024 * 1024;
    }

    /** "auto" resolved against this box: a weak one gets 30, the rest 60. */
    static String effectiveUi(Context c) {
        String v = uiChoice(c);
        if (!"auto".equals(v)) return v;
        return weak(c) ? "30" : "60";
    }

    /**
     * True when the user explicitly turned animations off, or left the desk on auto and the box is
     * weak. A 260 ms scale at 30 fps on a 2016 set reads as a delay between the key and the focus
     * moving; those sets get the instant focus change they had before, on a user report that rapid
     * D-pad presses felt dropped.
     */
    static boolean reducedMotion(Context c) {
        String v = uiChoice(c);
        if ("auto".equals(v)) return weak(c);
        return "off".equals(v);
    }

    /** Requested video rate, or 0 to follow the source. Honoured on Android 11+ only. */
    static float videoRate(Context c) {
        if (Build.VERSION.SDK_INT < 30) return 0;
        String v = videoChoice(c);
        if ("auto".equals(v)) return weak(c) ? 24f : 0f;
        if ("24".equals(v)) return 24f;
        if ("30".equals(v)) return 30f;
        return 0f;
    }

    static boolean videoRateSupported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    /** Applies the stored choices to a window. Call from onCreate and after every change. */
    static void apply(Activity a) {
        if (Build.VERSION.SDK_INT < 23) return;
        WindowManager.LayoutParams lp = a.getWindow().getAttributes();
        int mode = refreshMode(a);
        if (lp.preferredDisplayModeId != mode) {
            lp.preferredDisplayModeId = mode;
            a.getWindow().setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            // Static and global: every ValueAnimator on this process clocks at this interval.
            ValueAnimator.setFrameDelay("30".equals(effectiveUi(a)) ? 1000L / 30 : 0L);
        }
    }
}
