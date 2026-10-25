package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The user's own dock (the pinned apps along the bottom), editable from the sofa.
 *
 * Three layers, later wins: the compiled-in config.xml defaults, an optional config.json overlay pushed
 * by {@code tools/theme.sh}, and whatever the user picked here. An empty edit list means "the user
 * cleared the dock", so it must not fall back — that is why the layers are tracked separately instead
 * of collapsing into one nullable list.
 */
final class DockStore {

    private static final String PREFS = "dock";
    private static final String KEY_APPS = "apps";
    private static final String KEY_SIZE = "size";
    static final int MIN_SIZE = 1;
    static final int MAX_SIZE = 8;

    private final SharedPreferences prefs;
    /** config.xml defaults, optionally already overridden by config.json. */
    private final List<String> base;
    /** Slots to draw when the user has never resized the dock (the grid's column count). */
    private final int defaultSize;

    DockStore(Context ctx, List<String> configuredDefaults, int defaultSize) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.base = new ArrayList<>(configuredDefaults);
        this.defaultSize = Math.max(MIN_SIZE, Math.min(MAX_SIZE, defaultSize));
    }

    /** How many slots the dock draws. Resizable from the control centre. */
    int capacity() {
        int n = prefs.getInt(KEY_SIZE, defaultSize);
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, n));
    }

    /**
     * Resize the dock. Shrinking unpins the overflow (those apps return to the grid) so the dock
     * never keeps entries it cannot draw.
     *
     * @return how many apps were pushed back to the grid
     */
    int setCapacity(int size) {
        int n = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        prefs.edit().putInt(KEY_SIZE, n).apply();
        List<String> list = apps();
        if (list.size() <= n) return 0;
        int removed = list.size() - n;
        set(new ArrayList<>(list.subList(0, n)));
        return removed;
    }

    /** Pinned package names in dock order. Never null. */
    List<String> apps() {
        if (!prefs.contains(KEY_APPS)) return new ArrayList<>(base);
        String raw = prefs.getString(KEY_APPS, "");
        List<String> out = new ArrayList<>();
        for (String p : raw.split("\n")) {
            p = p.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /** True once the user has edited the dock themselves. */
    boolean isCustomised() {
        return prefs.contains(KEY_APPS);
    }

    /** Overwrite the dock. An empty list pins nothing, leaving the grid fully alphabetical. */
    void set(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(p.trim());
        }
        prefs.edit().putString(KEY_APPS, sb.toString()).apply();
    }

    /** Pin at the end of the dock, replacing whatever was in that slot. */
    void pin(String pkg, int slot) {
        List<String> list = apps();
        list.remove(pkg);                       // already pinned: move it rather than duplicate
        while (list.size() <= slot) list.add(null);
        list.set(slot, pkg);
        compact(list);
        set(list);
    }

    /** Replace the dock slot the app occupies, leaving it in the same position. */
    void pinOver(String pkg, int slot) {
        List<String> list = apps();
        list.remove(pkg);
        if (slot < 0 || slot >= list.size()) {
            pin(pkg, list.size());
            return;
        }
        list.set(slot, pkg);
        set(list);
    }

    /** Drop the app from the dock, letting the following entries close the gap. */
    void unpin(String pkg) {
        List<String> list = apps();
        list.remove(pkg);
        set(list);
    }

    boolean isPinned(String pkg) {
        return apps().contains(pkg);
    }

    private void compact(List<String> list) {
        // Collection.removeIf is API 24; this launcher runs on Android 6.
        java.util.Iterator<String> it = list.iterator();
        while (it.hasNext()) {
            String s = it.next();
            if (s == null || s.trim().isEmpty()) it.remove();
        }
    }
}