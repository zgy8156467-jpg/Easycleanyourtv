package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Input sources and settings on TCL televisions (China firmware, 50V6EA / ak30a5 / Android 9).
 *
 * <p>Like the Xiaomi sets there is no tv_input service, but TCL does publish its port table:
 * {@code content://com.tcl.tv.sourceprovider} answers SourceId/SourceName, and the Live TV app
 * switches when its TVActivity is handed the id as an int extra {@code targetSourceId}. Measured
 * 2026-10-26: 2 TV, 3 AV1, 8 HDMI1, 9 HDMI2 — its own id table, not Android's.
 *
 * <p>Settings is not an activity worth launching. com.tcl.settings/.ui.MainActivity is a stub that
 * starts the overlay service and finishes within a few seconds, which from the desk looks like
 * nothing happened and drops the user back on it. The overlay is
 * {@code com.tcl.settings/.ShowWindowService}, action SHOW_WINDOW, extras Type=Settings and
 * Catagory (TCL's spelling) picking the page. Only Main/Picture/network/about open a page of their
 * own; any other value falls back to Main.
 */
final class TclSource {

    private static final String LIVE_TV = "com.tcl.tv";
    private static final String TV_ACTIVITY = "com.tcl.tv.TVActivity";
    private static final String PROVIDER = "content://com.tcl.tv.sourceprovider";
    static final String SETTINGS = "com.tcl.settings";
    private static final String SHOW_WINDOW_SERVICE = "com.tcl.settings.ShowWindowService";
    private static final String SHOW_WINDOW_ACTION = "com.tcl.settings.SHOW_WINDOW";
    private static final String EXTRA_SOURCE = "targetSourceId";

    static final int TV = 2;

    static final class Item {
        final int id;
        final String label;
        final boolean hdmi;

        Item(int id, String label, boolean hdmi) {
            this.id = id;
            this.label = label;
            this.hdmi = hdmi;
        }
    }

    private TclSource() {
    }

    /** True on a set with TCL's Live TV app. The provider and the overlay live in it. */
    static boolean present(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(LIVE_TV, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** HDMI and AV ports, minus TV — the tuner has its own Live TV row. Empty if the query fails. */
    static List<Item> list(Context ctx) {
        List<Item> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(Uri.parse(PROVIDER),
                    new String[]{"SourceId", "SourceName"}, null, null, "SourceId");
            while (c != null && c.moveToNext()) {
                int id = c.getInt(0);
                String name = c.getString(1);
                if (name == null || id == TV) continue;
                out.add(new Item(id, pretty(name), name.trim().toUpperCase(Locale.ROOT).startsWith("HDMI")));
            }
        } catch (Exception e) {
            out.clear();
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** The provider says "HDMI1"; the panel style has a space. */
    private static String pretty(String name) {
        String n = name.trim();
        if (n.length() > 4 && n.toUpperCase(Locale.ROOT).startsWith("HDMI")) return "HDMI " + n.substring(4);
        return n;
    }

    /** Ask the Live TV app to switch to {@code id} and show it full screen. */
    static Intent viewIntent(int id) {
        return new Intent().setClassName(LIVE_TV, TV_ACTIVITY).putExtra(EXTRA_SOURCE, id);
    }

    /**
     * Live TV. com.tcl.tv declares no launcher category, so the usual launch-intent lookup finds
     * nothing; the tuner source id is the page. Null when the app is absent.
     */
    static Intent liveTvIntent(Context ctx) {
        return present(ctx) ? viewIntent(TV) : null;
    }

    /**
     * Open a settings page in TCL's overlay. False when this is not a TCL set or the service would
     * not start, so the caller can fall back to its own intent. {@code category} null means "no
     * overlay page" and is never started — the overlay would open Main, which is wrong for rows
     * that are not settings at all.
     */
    static boolean openSettings(Context ctx, String category) {
        if (category == null) return false;
        try {
            ctx.getPackageManager().getPackageInfo(SETTINGS, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        Intent i = new Intent(SHOW_WINDOW_ACTION)
                .setClassName(SETTINGS, SHOW_WINDOW_SERVICE)
                .putExtra("Type", "Settings")
                .putExtra("Catagory", category);
        try {
            ctx.startService(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
