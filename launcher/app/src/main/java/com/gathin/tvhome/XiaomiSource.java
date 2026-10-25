package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Input sources on Xiaomi/Redmi TVs.
 *
 * <p>These sets have no TV input service at all — {@code dumpsys tv_input} is empty and
 * {@link TvInputs#list} comes back with nothing — so the desk used to fall back to the vendor's own
 * source panel. The vendor player does expose an exported activity that switches ports when it is
 * handed the port id as an int extra, which is the same path its own launcher uses. The ids are the
 * Xiaomi middleware constants ({@code mitv.tv.SourceManager}); checked on a MiTV4-ANSM0
 * (Android 6.0.1, 1280x720): {@code input} 1 shows TV, 2 shows AV, 23/24/25 show HDMI1/2/3.
 *
 * <p>Ports are listed whether or not something is plugged in, exactly like the vendor panel: the
 * activity itself says "searching for signal" on an empty port, and hiding a port the user is
 * about to plug a console into would be worse than showing one that is idle.
 */
final class XiaomiSource {

    private static final String TVPLAYER = "com.xiaomi.mitv.tvplayer";
    private static final String ACTIVITY = "com.xiaomi.mitv.tvplayer.ExternalSourceActivity";
    private static final String ACTION_PLAY = "com.xiaomi.mitv.tvplayer.EXTSRC_PLAY";
    private static final String EXTRA_INPUT = "input";

    static final int TV = 1;
    static final int AV = 2;
    static final int HDMI1 = 23;
    static final int HDMI2 = 24;
    static final int HDMI3 = 25;

    static final class Item {
        final int id;
        final String label;

        Item(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private XiaomiSource() {
    }

    /** The ports to list, or an empty list when this is not a set with the vendor player. */
    static List<Item> list(Context ctx) {
        List<Item> out = new ArrayList<>();
        Intent probe = new Intent(ACTION_PLAY).setClassName(TVPLAYER, ACTIVITY);
        try {
            PackageManager pm = ctx.getPackageManager();
            if (pm.getPackageInfo(TVPLAYER, 0) == null) return out;
            if (pm.resolveActivity(probe, 0) == null) return out;
        } catch (PackageManager.NameNotFoundException e) {
            return out;
        }
        out.add(new Item(HDMI1, "HDMI 1"));
        out.add(new Item(HDMI2, "HDMI 2"));
        out.add(new Item(HDMI3, "HDMI 3"));
        out.add(new Item(TV, "TV"));
        out.add(new Item(AV, "AV"));
        return out;
    }

    /** Ask the vendor player to switch to {@code id} and show it full screen. */
    static Intent viewIntent(int id) {
        return new Intent(ACTION_PLAY)
                .setClassName(TVPLAYER, ACTIVITY)
                .putExtra(EXTRA_INPUT, id);
    }

    /**
     * The vendor's own source list. Opened without the input extra, the activity is the picker, and
     * it is the only way to reach a port the fixed id table above does not know about.
     */
    static Intent panelIntent() {
        return new Intent(ACTION_PLAY).setClassName(TVPLAYER, ACTIVITY);
    }

    /**
     * The vendor's digital (DTMB) live TV page. tvplayer has no launcher category, so the usual
     * launch-intent lookup misses it on sets that do report a tuner through tv_input (MiTV-MFFU0,
     * Android 14). Null when the player is not installed.
     */
    static Intent liveTvIntent(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(TVPLAYER, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        return new Intent("com.xiaomi.mitv.tvplayer.DTMB_PLAY")
                .setClassName(TVPLAYER, "com.xiaomi.mitv.tvplayer.dtmb.DTMBActivity");
    }
}
