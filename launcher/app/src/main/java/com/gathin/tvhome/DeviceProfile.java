package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What this particular TV needs. Every place the launcher behaves differently by brand reads from
 * here: which package is the stock desktop it has to cover, which packages are the vendor's idle
 * screensaver, where the vendor keeps its settings, and whether some remotes send Home as
 * {@code KEYCODE_ALL_APPS}.
 *
 * <p>Two sources, in this order. The brand table below knows the quirks someone has already hit
 * (Xiaomi's PatchWall is the only one so far). The device itself fills in the rest: the packages
 * that claim {@code CATEGORY_HOME} are the stock desktop wherever this runs, so a brand nobody has
 * seen yet still gets Home takeover without a rebuild. Add a brand to the table only when a probe
 * cannot see the quirk — never as the first choice.
 *
 * <p>Read once per process; see {@link #get(Context)}.
 */
final class DeviceProfile {

    private static final String TAG = "TvHome.Device";

    /** One brand's known quirks. Empty fields mean "let the probe answer". */
    private static final class Brand {
        final String name;
        final String[] match;
        final String[] homes;
        final String[] homeClassSuffixes;
        final String[] screenSavers;
        final String[] settings;
        final boolean allAppsIsHome;

        Brand(String name, String[] match, String[] homes, String[] homeClassSuffixes,
              String[] screenSavers, String[] settings, boolean allAppsIsHome) {
            this.name = name;
            this.match = match;
            this.homes = homes;
            this.homeClassSuffixes = homeClassSuffixes;
            this.screenSavers = screenSavers;
            this.settings = settings;
            this.allAppsIsHome = allAppsIsHome;
        }
    }

    private static final String[] NONE = new String[0];

    private static final Brand GENERIC =
            new Brand("generic", NONE, NONE, NONE, NONE, NONE, false);

    /**
     * Order matters only for matching: the first brand whose keyword appears in the manufacturer,
     * brand or model wins.
     *
     * <p>Xiaomi: PatchWall is {@code com.mitv.tvhome} and its home activity ends in
     * {@code MainActivityUserMode} — its source picker and TV pages share the package, and covering
     * those made the two launchers fight until the remote died. Xiaomi remotes also send Home as
     * {@code KEYCODE_ALL_APPS}.
     *
     * <p>Skyworth (创维/酷开, measured on 8R720_A5FP / Android 13 / 2026-10-23): the probe finds
     * nothing, because the desktop declares no {@code CATEGORY_HOME} activity at all — the firmware
     * sends Home straight to {@code com.tianci.movieplatform/…rootpage.HomeActivity}. Its idle
     * carousel is {@code com.coocaa.screensaver} (class {@code com.skyworth.screensaver.ui.*}), and
     * the user settings app is {@code com.skyworth.setting}.
     *
     * <p>Skyworth 8R710_A6E (85A6E, Android 13, 2026-10-24): the same package, but this firmware
     * shows the home page as {@code com.coocaa.homepage.vast.HomePageActivity} inside the host
     * {@code com.ccos.cooui.movieplatform.MainActivity}, and accessibility reports the host name,
     * so that host is the suffix. This set also has no Accessibility page at all; see
     * {@link #accessibilitySettingsPresent}.
     */
    private static final Brand[] BRANDS = {
            new Brand("xiaomi",
                    new String[]{"xiaomi", "redmi", "mitv"},
                    new String[]{"com.mitv.tvhome"},
                    new String[]{"MainActivityUserMode"},
                    new String[]{"com.mitv.screensaver", "com.xiaomi.tv.gallery"},
                    new String[]{"com.xiaomi.mitv.settings", "com.mitv.tvsettings",
                            "com.miui.tvsettings"},
                    true),
            new Brand("skyworth",
                    new String[]{"skyworth", "coocaa", "tianci", "创维", "酷开"},
                    new String[]{"com.tianci.movieplatform"},
                    // 8R720 (Android 13): rootpage.HomeActivity. 8R710_A6E (Android 13): the
                    // window title says com.coocaa.homepage.vast.HomePageActivity, but that activity
                    // is loaded inside a host and the accessibility event reports the host class
                    // com.ccos.cooui.movieplatform.MainActivity — matching the window title never fired.
                    new String[]{"rootpage.HomeActivity", "movieplatform.MainActivity"},
                    new String[]{"com.coocaa.screensaver"},
                    new String[]{"com.skyworth.setting"},
                    false),
            // Sony BRAVIA (2026-10-22): ordinary Android TV. Stock desktop and settings come from
            // the probe and the standard settings action; the one thing that bit us was that an
            // explicit Xiaomi settings component "resolved" on this set even though the package was
            // never installed — see installed(). Named here so feedback says "sony", nothing else.
            new Brand("sony", new String[]{"sony", "bravia"}, NONE, NONE, NONE, NONE, false),
            // Hisense VIDAA (seen on VIDAA_TV / Android 5.1.1, 8.0 and 11). The desktop is 聚好看,
            // com.jamdeo.tv.vod. Some firmwares launch its home page by component instead of through
            // CATEGORY_HOME, so the probe finds nothing and Home takeover never fires; naming it here
            // covers those. Only LauncherActivity counts as home — the same package hosts the VOD
            // pages, and covering those would pull the user out of what they opened.
            new Brand("hisense",
                    new String[]{"hisense", "vidaa", "jamdeo", "海信"},
                    new String[]{"com.jamdeo.tv.vod"},
                    new String[]{"LauncherActivity"},
                    NONE,
                    NONE,
                    false),
    };

    final String name;
    /** Packages that are the stock desktop: coming to front triggers the takeover. */
    final Set<String> stockHomes;
    /** Activity class suffixes that count as "the stock home page"; empty means any activity. */
    final List<String> homeClassSuffixes;
    final Set<String> screenSavers;
    /** Vendor settings packages that are actually installed, best first. */
    final List<String> settingsPackages;
    final boolean allAppsIsHome;
    /**
     * Whether this set has a Settings > Accessibility screen. When it does not, the launcher shows
     * the adb lines instead of a button that opens a dead stub; see {@code MainActivity}.
     */
    final boolean accessibilitySettings;

    private DeviceProfile(String name, Set<String> stockHomes, List<String> homeClassSuffixes,
                          Set<String> screenSavers, List<String> settingsPackages,
                          boolean allAppsIsHome, boolean accessibilitySettings) {
        this.name = name;
        this.stockHomes = Collections.unmodifiableSet(stockHomes);
        this.homeClassSuffixes = Collections.unmodifiableList(homeClassSuffixes);
        this.screenSavers = Collections.unmodifiableSet(screenSavers);
        this.settingsPackages = Collections.unmodifiableList(settingsPackages);
        this.allAppsIsHome = allAppsIsHome;
        this.accessibilitySettings = accessibilitySettings;
    }

    private static DeviceProfile cached;

    static synchronized DeviceProfile get(Context context) {
        if (cached == null) cached = resolve(context.getApplicationContext());
        return cached;
    }

    /** True when this foreground window is the stock desktop's home page. */
    boolean isStockHome(String pkg, String cls) {
        if (pkg == null || !stockHomes.contains(pkg)) return false;
        if (homeClassSuffixes.isEmpty()) return true;
        if (cls == null) return false;
        for (String suffix : homeClassSuffixes) if (cls.endsWith(suffix)) return true;
        return false;
    }

    /**
     * True when this window is an idle screensaver. The class check matters: a gallery app also
     * hosts the normal photo gallery, and only its screensaver page is idle. Brand class names
     * differ — Xiaomi ends in {@code ScreenSaverActivity}, Skyworth's carousel is
     * {@code com.skyworth.screensaver.ui.MainActivity} — so any class that names a screensaver
     * counts, and a null class counts inside a screensaver-only package.
     */
    boolean isScreenSaver(String pkg, String cls) {
        if (pkg == null) return false;
        if (cls == null) return screenSavers.contains(pkg);
        return cls.toLowerCase(Locale.ROOT).contains("screensaver");
    }

    /** One line for the console, so a new brand can be described without asking its owner. */
    String describe() {
        return "profile=" + name
                + " stockHome=" + stockHomes
                + " screensaver=" + screenSavers
                + " settings=" + settingsPackages
                + " allAppsHome=" + allAppsIsHome
                + " a11ySettings=" + accessibilitySettings;
    }

    private static DeviceProfile resolve(Context ctx) {
        Brand brand = matchBrand();
        PackageManager pm = ctx.getPackageManager();

        Set<String> homes = new HashSet<>(Arrays.asList(brand.homes));
        for (String pkg : homeCandidates(pm)) {
            if (!pkg.equals(ctx.getPackageName())) homes.add(pkg);
        }

        Set<String> screens = new HashSet<>(Arrays.asList(brand.screenSavers));

        List<String> settings = new ArrayList<>();
        for (String pkg : brand.settings) {
            if (installed(pm, pkg)) settings.add(pkg);
        }

        DeviceProfile profile = new DeviceProfile(brand.name, homes,
                Arrays.asList(brand.homeClassSuffixes), screens,
                settings.isEmpty() ? Collections.emptyList() : settings,
                brand.allAppsIsHome, accessibilitySettingsPresent(pm));
        Log.i(TAG, profile.describe());
        return profile;
    }

    private static Brand matchBrand() {
        String id = (Build.MANUFACTURER + " " + Build.BRAND + " " + Build.MODEL
                + " " + Build.DEVICE).toLowerCase(Locale.ROOT);
        for (Brand brand : BRANDS) {
            for (String key : brand.match) {
                if (id.contains(key.toLowerCase(Locale.ROOT))) return brand;
            }
        }
        return GENERIC;
    }

    /** Every package that claims to be a desktop on this device, including the stock one. */
    private static List<String> homeCandidates(PackageManager pm) {
        List<String> out = new ArrayList<>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        try {
            for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
            }
        } catch (Exception e) {
            Log.w(TAG, "home query failed: " + e);
        }
        return out;
    }

    /**
     * getPackageInfo, not resolveActivity: on Sony BRAVIA an explicit Xiaomi component resolved
     * even though the package was not installed (TvHome 2026-10-22).
     */
    private static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whether Settings > Accessibility actually opens something. AOSP TV firmware without the
     * Settings app answers the standard intent with {@code com.android.tv.frameworkpackagestubs},
     * whose stub screens are dead ends; Skyworth 8R710 (85A6E, Android 13, 2026-10-24) has no
     * other handler, and no accessibility page anywhere in com.skyworth.setting either. No
     * handler at all counts as missing too.
     */
    private static boolean accessibilitySettingsPresent(PackageManager pm) {
        ResolveInfo ri = pm.resolveActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 0);
        if (ri == null || ri.activityInfo == null) return false;
        return !"com.android.tv.frameworkpackagestubs".equals(ri.activityInfo.packageName);
    }
}
