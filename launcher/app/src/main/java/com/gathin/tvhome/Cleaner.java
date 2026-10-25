package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hands the cleaning to the TV itself. Third-party apps cannot touch another app's cache, and every
 * vendor already ships a cleaner; the only job here is finding it.
 *
 * <p>Xiaomi's TV manager publishes an action for its junk screen. Other brands do not, so they get
 * a package/label scan for the familiar names (管家/清理/加速/安全, tvmanager/clean/junk...). When
 * nothing matches, storage settings still offers the cache buttons, so the row never dead-ends.
 */
final class Cleaner {

    private static final String XIAOMI_ACTION = "com.xiaomi.mitv.tvmanager.action.CLEAN_DISK";
    private static final String STORAGE_SETTINGS = "android.settings.INTERNAL_STORAGE_SETTINGS";
    private static final String SETTINGS = "android.settings.SETTINGS";

    private static final String[] PKG_WORDS = {
            "tvmanager", "clean", "junk", "boost", "speedup", "security", "guard"};
    private static final String[] LABEL_WORDS = {
            "管家", "清理", "加速", "安全", "优化", "clean", "master"};

    private Cleaner() {
    }

    /** True when something was opened. The caller keeps a toast for the false case. */
    static boolean open(Context ctx) {
        if (startAction(ctx, XIAOMI_ACTION)) return true;
        for (String pkg : candidates(ctx)) {
            if (startPackage(ctx, pkg)) return true;
        }
        return startAction(ctx, STORAGE_SETTINGS) || startAction(ctx, SETTINGS);
    }

    private static boolean startAction(Context ctx, String action) {
        Intent intent = new Intent(action);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return start(ctx, intent);
    }

    private static boolean startPackage(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null && Build.VERSION.SDK_INT >= 21) {
            intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return start(ctx, intent);
        }
        // No LAUNCHER entry: take the package's exported MAIN activity instead.
        Intent main = new Intent(Intent.ACTION_MAIN).setPackage(pkg);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
            Intent explicit = new Intent(Intent.ACTION_MAIN)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (start(ctx, explicit)) return true;
        }
        return false;
    }

    private static boolean start(Context ctx, Intent intent) {
        try {
            ctx.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Installed packages that look like the vendor's cleaner, best guess first. */
    private static List<String> candidates(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        String self = ctx.getPackageName();
        List<String> hits = new ArrayList<>();
        List<String> weak = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            String pkg = ai.packageName;
            if (pkg.equals(self)) continue;
            String lower = pkg.toLowerCase(Locale.ROOT);
            boolean strong = false;
            for (String w : PKG_WORDS) {
                if (lower.contains(w)) {
                    strong = true;
                    break;
                }
            }
            if (!strong) {
                String label = String.valueOf(ai.loadLabel(pm)).toLowerCase(Locale.ROOT);
                for (String w : LABEL_WORDS) {
                    if (label.contains(w)) {
                        strong = true;
                        break;
                    }
                }
            }
            // A label-only match is weaker: the package name usually names the vendor's own tool.
            if (strong) (lower.contains("tvmanager") || lower.contains("clean") ? hits : weak).add(pkg);
        }
        hits.addAll(weak);
        return hits;
    }
}
