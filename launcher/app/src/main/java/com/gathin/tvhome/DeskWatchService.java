package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Home takeover for firmware that drops our accessibility service as soon as the user leaves the
 * settings page (LeTV EUI on the X4-50). With no accessibility the system keeps handing Home to
 * the vendor desktop, so this service watches the foreground app through usage stats and covers
 * the vendor desktop the moment it appears. Same signal and same takeover as
 * {@link HomeKeyService}, just without the accessibility binding.
 *
 * <p>Idles while the accessibility service is enabled, so TVs where the switch sticks pay nothing
 * for this. Needs the usage-access grant (Settings → Security → apps with usage access); without
 * it the scan stays empty and the service keeps waiting, no prompt shown.
 */
public class DeskWatchService extends Service {
    /** Sent to {@link BootReceiver} by the revive alarm. */
    static final String TICK = "com.gathin.tvhome.WATCH_TICK";
    private static final long REVIVE_MS = 5 * 60 * 1000L;
    /**
     * The scan runs off the main thread. {@code getRunningServices()} below walks every service in
     * the system and took 753ms during an app switch; on the main thread it also delays the
     * accessibility key filter, and every remote key waits for that answer.
     */
    private HandlerThread pollThread;
    private Handler handler;
    private long lastUsageCheck = 0;
    /** The bound-service check is a heavy binder call; five seconds is plenty for a kill signal. */
    private long lastLiveCheck = -100_000;
    private boolean a11yLive;
    private long lastTakeover = 0;
    /** The same stock-desktop event would be handed back by the one-second overlap; act once. */
    private String lastActedKey = "";
    /** Stock home appeared while takeover was suppressed; cover it when the suppression ends. */
    private long pendingTakeoverAt = 0;
    /** The first scan looks an hour back to see what is on screen right now; later ones poll. */
    private boolean caughtUp;
    /** One console line per process for each state worth seeing remotely. The "foreground" line
     *  names the first app that is not ours: it proves the usage feed works, and when a vendor
     *  desktop went unrecognised it is the package name to add to the profile. */
    private static boolean reportedStart, reportedIdle, reportedCover, reportedSeen;

    static void start(Context ctx) {
        // Usage stats is a 5.0 API. Before that the Home broadcast below is the only signal that
        // survives LeTV eUI dropping our accessibility service; the service is still worth running.
        if (Build.VERSION.SDK_INT < 21) {
            try {
                ctx.startService(new Intent(ctx, DeskWatchService.class));
            } catch (RuntimeException ignored) {
            }
            scheduleRevive(ctx);
            return;
        }
        // Persisted job: the boot path on firmware that filters BOOT_COMPLETED.
        DeskWatchJob.schedule(ctx);
        scheduleRevive(ctx);
        if (Build.VERSION.SDK_INT >= 26) DeskWatchNotify.start(ctx);
        else ctx.startService(new Intent(ctx, DeskWatchService.class));
    }

    /**
     * Android closes system dialogs before it brings the desktop up, and that broadcast carries
     * the reason "homekey". It is the one Home signal a pre-5.0 TV gives an app that is not the
     * default desktop and has no accessibility binding — LeTV eUI switches the service off as soon
     * as the user leaves its settings page. The broadcast reaches us while the stock desktop is
     * still starting, so the desk lands on top of it.
     */
    private final BroadcastReceiver homeKey = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) return;
            if (!"homekey".equals(intent.getStringExtra("reason"))) return;
            if (SystemClock.elapsedRealtime() <= HomeKeyService.suppressedUntil()) return;
            showDesk();
        }
    };
    private boolean homeKeyRegistered;

    @Override
    public void onCreate() {
        super.onCreate();
        pollThread = new HandlerThread("desk-watch");
        pollThread.start();
        handler = new Handler(pollThread.getLooper());
        if (Build.VERSION.SDK_INT < 21) {
            registerReceiver(homeKey, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            homeKeyRegistered = true;
        }
    }

    /**
     * The alarm lives in the system, so it outlives our process: LeTV's cleaner kills the desk a
     * few seconds after boot, and without this Home went to its desktop until the 15-minute job.
     * Inexact on purpose — a few minutes of batching is fine, no need to wake the panel.
     */
    static void scheduleRevive(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent tick = new Intent(ctx, BootReceiver.class).setAction(TICK);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0x4b3, tick,
                PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + REVIVE_MS, REVIVE_MS, pi);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 26+ demands the foreground notification within seconds of startForegroundService.
        if (Build.VERSION.SDK_INT >= 26) {
            DeskWatchNotify.foreground(this);
        } else {
            // No startForegroundService below 26, but a foreground service is still the only way
            // to stop LeTV's cleaner killing the process a few seconds after boot.
            Notification n = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_settings)
                    .setContentTitle(getString(R.string.desk_watch_name))
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_MIN)
                    .build();
            startForeground(0x4b1, n);
        }
        if (!reportedStart) {
            reportedStart = true;
            Errors.record("desk-watch", "service started");
        }
        handler.removeCallbacks(poll);
        if (Build.VERSION.SDK_INT >= 21) handler.post(poll);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacks(poll);
        if (pollThread != null) {
            // quitSafely is API 18; the legacy build still reaches 14.
            if (Build.VERSION.SDK_INT >= 18) pollThread.quitSafely();
            else pollThread.quit();
            pollThread = null;
            handler = null;
        }
        if (homeKeyRegistered) {
            unregisterReceiver(homeKey);
            homeKeyRegistered = false;
        }
        super.onDestroy();
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            scan();
            // 200ms: keeps the vendor desktop flash short without polling faster than the
            // accessibility path already does on the same boxes.
            handler.postDelayed(this, 200);
        }
    };

    private void scan() {
        // The switch alone is not enough: MIUI leaves it on after killing the service, and a dead
        // accessibility service covers nothing. Only a bound one earns this watcher's silence.
        // The binding changes only when the service dies or comes back, so checking it every scan
        // (5x a second) just burns binder calls; five seconds is fast enough to notice.
        long uptime = SystemClock.elapsedRealtime();
        if (uptime - lastLiveCheck > 5000) {
            lastLiveCheck = uptime;
            a11yLive = HomeKeyService.enabled(this) && HomeKeyService.running(this);
        }
        if (a11yLive || !UsageScan.allowed(this)) {
            if (!reportedIdle) {
                reportedIdle = true;
                Errors.record("desk-watch", a11yLive
                        ? "idle: accessibility on" : "idle: no usage access");
            }
            lastUsageCheck = 0;
            return;
        }
        long now = System.currentTimeMillis();
        boolean first = !caughtUp;
        caughtUp = true;
        long from;
        if (first) {
            // Waking up after a kill: the desktop may have been on screen for minutes and left no
            // recent event, so look back an hour and act on the newest one only.
            from = now - 60 * 60 * 1000L;
        } else {
            // Same one-second overlap as HomeKeyService: the system can report a resume a moment
            // after it happened, with a timestamp the previous read already covered.
            from = (lastUsageCheck != 0 && lastUsageCheck <= now && now - lastUsageCheck < 60000)
                    ? lastUsageCheck - 1000 : now - 2000;
        }
        lastUsageCheck = now;
        java.util.List<UsageScan.Event> events = UsageScan.foregroundEvents(this, from, now);
        if (first && !events.isEmpty()) {
            // Only the newest event describes the screen we woke up to; replaying the hour would
            // drag the member off whatever is playing.
            events = java.util.Collections.singletonList(events.get(events.size() - 1));
        }
        for (UsageScan.Event e : events) {
            if (!DeviceProfile.get(this).isStockHome(e.pkg, e.cls)) {
                if (!reportedSeen && e.pkg != null && !getPackageName().equals(e.pkg)) {
                    reportedSeen = true;
                    Errors.record("desk-watch", "foreground " + e.pkg + "/" + e.cls);
                }
                continue;
            }
            long elapsed = SystemClock.elapsedRealtime();
            if (elapsed <= HomeKeyService.suppressedUntil()) {
                pendingTakeoverAt = HomeKeyService.suppressedUntil();
                continue;
            }
            String key = e.ts + "|" + e.cls;
            if (key.equals(lastActedKey)) continue;
            lastActedKey = key;
            if (!reportedCover) {
                reportedCover = true;
                Errors.record("desk-watch", "covered " + e.pkg + "/" + e.cls);
            }
            showDesk();
        }
        if (pendingTakeoverAt != 0 && SystemClock.elapsedRealtime() >= pendingTakeoverAt) {
            pendingTakeoverAt = 0;
            showDesk();
        }
    }

    /** Same PendingIntent route as HomeKeyService: MIUI drops a plain background start. */
    private void showDesk() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastTakeover < 1000) return;
        lastTakeover = now;
        Intent desk = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            PendingIntent.getActivity(this, 0, desk,
                    PendingIntent.FLAG_UPDATE_CURRENT).send();
        } catch (PendingIntent.CanceledException ignored) {
        }
    }
}
