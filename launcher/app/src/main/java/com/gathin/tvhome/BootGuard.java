package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Last line of defence for boxes that never get past the boot logo (a Xiaomi on Android 4.4.4 is
 * the reported one). We cannot reproduce the firmware, so the guard is generic: three process
 * starts less than a minute apart, with no successful first frame in between, mean the desk is
 * looping rather than being restarted by a cleaner. The next start runs a stripped desk — no
 * posters, trailers, weather loop, dynamic wallpaper or animation — and the auto-relaunch after
 * boot stands down, so the stock launcher can at least come up.
 */
final class BootGuard {

    private static final String PREFS = "boot_guard";
    private static final String KEY_COUNT = "count";
    private static final String KEY_LAST = "last_start";
    private static final long QUICK_MS = 60_000L;
    private static final int FAILS = 3;

    private BootGuard() {
    }

    /** Called once per process, before the desk is built. */
    static void noteStart(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = p.getLong(KEY_LAST, 0);
        int count = now - last < QUICK_MS ? p.getInt(KEY_COUNT, 0) + 1 : 1;
        // commit: the process may be killed before apply() reaches the disk.
        p.edit().putInt(KEY_COUNT, count).putLong(KEY_LAST, now).commit();
    }

    /** A first frame reached the screen: the start worked. */
    static void noteOk(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    static boolean safeMode(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getInt(KEY_COUNT, 0) >= FAILS;
    }

    static int starts(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_COUNT, 0);
    }
}
