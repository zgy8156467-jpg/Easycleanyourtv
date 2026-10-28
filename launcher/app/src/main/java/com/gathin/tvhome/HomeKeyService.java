package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Some remotes — Xiaomi boxes and TVs especially — send KEYCODE_ALL_APPS for the Home button instead
 * of KEYCODE_HOME. Android TV routes that key only to the system-image launcher, so once the stock
 * launcher is replaced by this one, the button does nothing. This service catches that key and
 * re-issues it as a HOME intent for this launcher: when the desktop is already in front it lands in
 * MainActivity.onNewIntent and scrolls back to the top, which is the "Home returns to top" behaviour.
 *
 * <p>The same service gives the remote's own Settings (选项) button somewhere to go. The stock launcher
 * used to handle it; with the stock launcher gone the button does nothing, so it opens
 * {@link QuickSettingsActivity} — a small glass panel over whatever is on screen. Boxes that report
 * that button under a raw scan code instead of KEYCODE_SETTINGS can list it in
 * {@code res/values/config.xml} (settings_key_scancodes); the default list is empty, so nothing is
 * hijacked until the user asks for it. Every other key passes straight through, untouched.
 *
 * Off by default. It only ever runs if the user turns it on in Settings > Accessibility (or over adb):
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 * Declaring it costs nothing until it is enabled — no permission is requested at install.
 */
public class HomeKeyService extends AccessibilityService {

    /** The desktop's own panel writes this; see {@link MainActivity#menuKeyEverywhere()}. */
    static final String PREFS = "keys";
    static final String KEY_MENU_EVERYWHERE = "menu_key_everywhere";

    /** Remote buttons that should open quick settings rather than fall through to the app behind. */
    private int[] settingsScancodes = new int[0];

    /**
     * PatchWall's home activity is resumed for every Home press but fires no window-state event when
     * it is already alive, and the firmware keeps its window out of reach of accessibility services
     * (getRootInActiveWindow() returns null, and the Home key itself never reaches onKeyEvent). Both
     * packages are recorded by the system's usage stats, though, so a quick look at who was brought
     * to front is the one signal that survives this firmware. Needs the usage-access grant:
     *   adb shell appops set com.gathin.tvhome GET_USAGE_STATS allow
     */
    private final android.os.Handler watchdog = new android.os.Handler();
    private long lastUsageCheck = 0;
    /** What the stock desktop of this TV looks like; see {@link DeviceProfile}. */
    private DeviceProfile device;

    /** The last stock-desktop event the desk was brought up for; the overlap would replay it otherwise. */
    private String lastActedKey = "";
    /**
     * PatchWall came home while takeover was suppressed (switching to a TV input suppresses it for
     * 20s). The event will not repeat once Home is pressed again, so cover the desk when the
     * suppression ends instead of dropping the event on the floor.
     */
    private long pendingTakeoverAt = 0;
    private final Runnable pollUsage = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            // Query one second back from the last poll, not from it exactly: the system sometimes
            // reports a resume a moment after it happened, with a timestamp inside the window the
            // last poll already read. Without the overlap that event is never returned again, and
            // the desk sits under PatchWall until the next Home press. lastActedKey drops the
            // PatchWall events the overlap replays.
            if (lastUsageCheck != 0 && lastUsageCheck <= now
                    && now - lastUsageCheck < 60000) {
                scanUsage(lastUsageCheck - 1000, now);
            } else {
                scanUsage(now - 2000, now);
            }
            lastUsageCheck = now;
            if (pendingTakeoverAt != 0) {
                long elapsed = android.os.SystemClock.elapsedRealtime();
                if (elapsed >= pendingTakeoverAt) {
                    if (elapsed > suppressUntil) {
                        pendingTakeoverAt = 0;
                        showDesk();
                    } else {
                        // Another suppression was started meanwhile; wait for it to end too.
                        pendingTakeoverAt = suppressUntil;
                    }
                }
            }
            // 150ms: the shorter the gap, the shorter PatchWall's flash before the desk covers it.
            // The window is a fixed one second, so the per-poll cost stays flat.
            watchdog.postDelayed(this, 150);
        }
    };

    private DeviceProfile device() {
        if (device == null) device = DeviceProfile.get(this);
        return device;
    }

    /** The 1-second takeover throttle can eat the first HOME; retry once when it does. */
    private void showDeskForScreenSaver() {
        if (showDesk()) return;
        watchdog.removeCallbacks(deskRetry);
        watchdog.postDelayed(deskRetry, 1100);
    }

    private final Runnable deskRetry = this::showDesk;

    private void scanUsage(long from, long to) {
        // Usage stats is a 5.0 API. On 4.x the accessibility window event is the only takeover
        // signal, and that path needs no polling at all.
        if (android.os.Build.VERSION.SDK_INT < 21) return;
        for (UsageScan.Event e : UsageScan.foregroundEvents(this, from, to)) {
            if (e.pkg != null) foreground = e.pkg;
            // Only the home page, never the source picker or a TV input page: those live in the
            // same package, and covering them made the two launchers fight until the remote died.
            boolean stockHome = device().isStockHome(e.pkg, e.cls);
            boolean screenSaver = device().isScreenSaver(e.pkg, e.cls);
            if (stockHome || screenSaver) {
                // The one-second overlap hands the same event back on every poll for a second;
                // a repeat is the same resume, not a new Home press.
                String key = e.ts + "|" + e.cls;
                if (!key.equals(lastActedKey)) {
                    lastActedKey = key;
                    if (android.os.SystemClock.elapsedRealtime() > suppressUntil) {
                        if (screenSaver) showDeskForScreenSaver();
                        else showDesk();
                    } else if (stockHome) {
                        pendingTakeoverAt = suppressUntil;
                    }
                }
            }
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        if (android.os.Build.VERSION.SDK_INT >= 21) watchdog.post(pollUsage);
        // At boot this is bound after PatchWall has already come up, so there is no window event to
        // react to; claim the screen ourselves shortly after boot instead. The first intent can be
        // swallowed while PatchWall is still opening its own window, so ask twice more.
        if (android.os.SystemClock.elapsedRealtime() < 120000) {
            showDesk();
            watchdog.postDelayed(showDeskTask, 1500);
            watchdog.postDelayed(showDeskTask, 4000);
        }
    }

    private final Runnable showDeskTask = new Runnable() {
        @Override
        public void run() {
            showDesk();
        }
    };

    @Override
    public void onDestroy() {
        watchdog.removeCallbacks(pollUsage);
        watchdog.removeCallbacks(showDeskTask);
        super.onDestroy();
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        return false;
    }

    /**
     * True when the user asked for the Menu button to open quick settings from other apps too.
     *
     * <p>This is the one trade-off in the whole feature: catching Menu everywhere means an app that
     * uses that button for its own menu loses it. Off by default, so nothing is taken away silently —
     * the desktop's settings panel turns it on explicitly.
     */
    private boolean menuOpensQuickSettings() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_MENU_EVERYWHERE, false);
    }

    /** The package of the window that came to front last; decides whether Home is "ours" to handle. */
    private String foreground = "";

    /** Last time PatchWall was covered up, so a burst of window events starts us only once. */
    private long lastTakeover = 0;

    /** Until this time the desk stays out of PatchWall's way — see {@link #suppressTakeover}. */
    private static volatile long suppressUntil = 0;

    /**
     * Stop covering PatchWall for a while. The desk calls this before opening the vendor's own source
     * picker: that picker is another PatchWall window, and the two of them yanking the screen back and
     * forth left the remote dead until a reboot.
     */
    static void suppressTakeover(long millis) {
        suppressUntil = android.os.SystemClock.elapsedRealtime() + millis;
    }

    /** Until when takeover is suppressed; {@link DeskWatchService} has to respect it too. */
    static long suppressedUntil() {
        return suppressUntil;
    }

    /** Whether the user turned this service on in Settings &gt; Accessibility. */
    static boolean enabled(Context ctx) {
        String enabled = android.provider.Settings.Secure.getString(ctx.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String on = android.provider.Settings.Secure.getString(ctx.getContentResolver(),
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
        if (enabled == null || !"1".equals(on)) return false;
        ComponentName cn = new ComponentName(ctx, HomeKeyService.class);
        return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString());
    }

    /**
     * Bring the desk to front. The intent is sent as a PendingIntent because MIUI silently stops a
     * plain {@code startActivity} from a background process ("Activity start request from ... stopped")
     * and only lets it through ~5s later; through a PendingIntent it lands right away.
     */
    private boolean showDesk() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastTakeover < 1000) return false;
        lastTakeover = now;
        Intent desk = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            android.app.PendingIntent.getActivity(this, 0, desk,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT).send();
        } catch (android.app.PendingIntent.CanceledException ignored) {
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null) {
            foreground = event.getPackageName().toString();
            // Xiaomi keeps its stock PatchWall as HOME and hands it every Home press, and it refuses
            // BOOT_COMPLETED too, so nothing else reaches this launcher. Whenever PatchWall's home
            // activity comes to front, put the desk back on top. Deliberately not every PatchWall
            // window: its source picker and TV pages live in the same package, and covering those
            // made the two launchers fight over focus until the remote stopped responding at all.
            CharSequence cls = event.getClassName();
            String className = cls == null ? null : cls.toString();
            boolean stockHome = device().isStockHome(foreground, className);
            boolean screenSaver = device().isScreenSaver(foreground, className);
            if (stockHome || screenSaver) {
                if (android.os.SystemClock.elapsedRealtime() > suppressUntil) {
                    if (screenSaver) showDeskForScreenSaver();
                    else showDesk();
                } else if (stockHome) {
                    pendingTakeoverAt = suppressUntil;
                }
            }
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        // The remote's source key. TCL's firmware handles the code itself and sends it to its TV
        // app, which from the desk looks like the button doing nothing (it just re-opens the same
        // input). While the desk is in front, take it and open our own source panel; anywhere else
        // leave it alone so the vendor UI still answers.
        if (event.getKeyCode() == KeyEvent.KEYCODE_TV_INPUT) {
            if (!getPackageName().equals(foreground)) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                MainActivity.onInputKeyFromService();
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            // The system swallows Home when the launcher is already in front — it re-sends the same
            // intent and the task manager drops it as a duplicate, so the desk never hears about it.
            // Here it does hear, and the desk can go back to the top.
            if (getPackageName().equals(foreground)) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    MainActivity.onHomeKeyFromService();
                }
                return true;
            }
            // From another app the key usually goes missing — Xiaomi's framework routes Home
            // straight to PatchWall before any of this. When it does arrive (it is not consistent
            // on this firmware), skip PatchWall entirely and show the desk right now.
            if (foreground.isEmpty()
                    || android.os.SystemClock.elapsedRealtime() <= suppressUntil) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                showDesk();
            }
            return true;
        }
        if (isSettingsKey(event)
                || (event.getKeyCode() == KeyEvent.KEYCODE_MENU && menuOpensQuickSettings())) {
            // Starting the translucent panel while the screen is still waking leaves it without a
            // focusable window; the pending key press then trips an input-dispatch ANR (seen on the
            // MiTV4X / Android 6: QuickSettingsActivity, "no window has focus"). Let the key
            // through instead, so the system's own wake handling runs and the user presses again.
            if (!screenOn()) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                startActivity(new Intent(this, QuickSettingsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS || !device().allAppsIsHome) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        }
        return true;
    }

    /** Whether the screen is on and awake; the panel is only safe to start then. */
    private boolean screenOn() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return true;
        return Build.VERSION.SDK_INT >= 20 ? pm.isInteractive() : pm.isScreenOn();
    }

    @Override
    public void onInterrupt() {
    }
}
