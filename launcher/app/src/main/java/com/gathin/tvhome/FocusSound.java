package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * The desk's own focus thud, played only when the firmware's key click is off. With both on they
 * overlap into two sounds — the exact complaint that had this removed once. The system click is
 * not something an app can suppress, so the rule is: if sound_effects_enabled is on, the firmware
 * already gives the TV its click and this stays silent; turn the TV's key sounds off (Settings →
 * Sound) and the low thud takes its place. Soft, low, short — a move should be felt, not heard
 * twice. The user can switch it off in 桌面设置 → 桌面 → 按键音; the firmware's own click is not
 * something an app can silence, so that switch only covers this thud.
 */
final class FocusSound {
    /** 桌面设置 → 按键音; on by default so the feature keeps working as before. */
    static final String PREF_ON = "focus_sound_on";
    private static SoundPool pool;
    private static int tick = -1;
    private static long lastAt;
    private static long checkedAt;
    private static boolean systemClick;

    private FocusSound() {}

    static boolean on(Context ctx) {
        return ctx.getSharedPreferences(HomeKeyService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_ON, true);
    }

    static void setOn(Context ctx, boolean on) {
        ctx.getSharedPreferences(HomeKeyService.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_ON, on).apply();
    }

    /** Load the sample before the first key press; SoundPool decodes off the main thread. */
    static void warmUp(Context ctx) {
        if (on(ctx) && pool == null) init(ctx);
    }

    static void play(Context ctx) {
        long now = SystemClock.uptimeMillis();
        // Holding down a direction repeats focus every few frames; one thud per press is enough.
        if (now - lastAt < 45) return;
        lastAt = now;
        if (!on(ctx)) return;
        // The firmware's click and ours must never sound together; see the class comment. Read the
        // setting at most every two seconds: this runs on every focus move, and a provider round
        // trip per key press is latency the desk cannot afford.
        if (now - checkedAt > 2000) {
            checkedAt = now;
            try {
                systemClick = Settings.System.getInt(ctx.getContentResolver(),
                        Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0;
            } catch (Exception e) {
                systemClick = true;
            }
        }
        if (systemClick) return;
        if (pool == null) init(ctx);
        if (tick > 0) pool.play(tick, 0.55f, 0.55f, 1, 0, 1f);
    }

    private static void init(Context ctx) {
        if (Build.VERSION.SDK_INT >= 21) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build();
        } else {
            pool = new SoundPool(2, android.media.AudioManager.STREAM_SYSTEM, 0);
        }
        tick = pool.load(ctx.getApplicationContext(), R.raw.focus_tick, 1);
    }
}
