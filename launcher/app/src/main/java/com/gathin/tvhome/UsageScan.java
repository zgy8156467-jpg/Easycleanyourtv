package com.gathin.tvhome;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * The usage-stats foreground feed PatchWall needs, isolated so Dalvik on Android 4.x never resolves
 * android.app.usage. Callers check the API level before calling.
 */
final class UsageScan {

    static final class Event {
        final String pkg;
        final String cls;
        final long ts;

        Event(String pkg, String cls, long ts) {
            this.pkg = pkg;
            this.cls = cls;
            this.ts = ts;
        }
    }

    private UsageScan() {
    }

    static List<Event> foregroundEvents(Context ctx, long from, long to) {
        List<Event> out = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT < 21) return out;
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return out;
            android.app.usage.UsageEvents events = usm.queryEvents(from, Math.max(to, from + 1));
            if (events == null) return out;
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    out.add(new Event(e.getPackageName(), e.getClassName(), e.getTimeStamp()));
                }
            }
        } catch (RuntimeException e) {
            // At boot the usage-stats service can still be starting; an exception here used to kill
            // the process, and this firmware never rebinds a dead accessibility service.
            android.util.Log.w("TvHome.HomeKey", "usage scan: " + e);
            Errors.record("home-key", "usage scan " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }
}
