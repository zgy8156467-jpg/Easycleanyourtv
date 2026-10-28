package com.gathin.tvhome;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** One app and the last time it came to the foreground. */
    static final class Recent {
        final String pkg;
        final long ts;

        Recent(String pkg, long ts) {
            this.pkg = pkg;
            this.ts = ts;
        }
    }

    private UsageScan() {
    }

    /**
     * Distinct apps that came to the foreground since [from], newest first. Android does not let a
     * normal launcher read other apps' live processes, so this is the honest "recently used" feed
     * behind the control centre's running list.
     */
    static List<Recent> recent(Context ctx, long from, int limit) {
        List<Recent> out = new ArrayList<>();
        if (limit <= 0) return out;
        Map<String, Long> latest = new HashMap<>();
        for (Event e : foregroundEvents(ctx, from, System.currentTimeMillis())) {
            if (e.pkg == null || e.pkg.isEmpty()) continue;
            Long at = latest.get(e.pkg);
            if (at == null || e.ts > at) latest.put(e.pkg, e.ts);
        }
        for (Map.Entry<String, Long> e : latest.entrySet()) {
            out.add(new Recent(e.getKey(), e.getValue()));
        }
        Collections.sort(out, new Comparator<Recent>() {
            @Override
            public int compare(Recent a, Recent b) {
                return a.ts < b.ts ? 1 : (a.ts == b.ts ? 0 : -1);
            }
        });
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** Whether the user allowed usage access for this app; the scan reads nothing without it. */
    static boolean allowed(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT < 21) return false;
        try {
            android.app.AppOpsManager ops =
                    (android.app.AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            return ops != null && ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.getPackageName())
                    == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
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
