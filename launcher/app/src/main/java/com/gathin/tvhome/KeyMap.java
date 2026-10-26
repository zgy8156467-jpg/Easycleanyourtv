package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remote buttons the user taught the desk: key code → built-in action. Desktop only — the mapping is
 * read in {@link MainActivity#dispatchKeyEvent}, so other apps keep their own buttons.
 *
 * <p>Stored as one string, "code=func;code=func", because the map has a handful of entries and a
 * preferences file per entry would be more machinery than the feature deserves.
 */
final class KeyMap {

    static final int NONE = -1;
    static final int INPUTS = 0;
    static final int CONTROL = 1;
    static final int CAST = 2;
    static final int SETTINGS = 3;
    static final int LIVE_TV = 4;
    static final int TOP = 5;

    private static final String PREFS = "keymap";
    private static final String KEY = "map";

    private KeyMap() {
    }

    static Map<Integer, Integer> all(Context ctx) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (String part : prefs(ctx).getString(KEY, "").split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(Integer.parseInt(part.substring(0, eq)),
                        Integer.parseInt(part.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static int get(Context ctx, int code) {
        Integer func = all(ctx).get(code);
        return func == null ? NONE : func;
    }

    static void put(Context ctx, int code, int func) {
        Map<Integer, Integer> map = all(ctx);
        map.put(code, func);
        save(ctx, map);
    }

    static void remove(Context ctx, int code) {
        Map<Integer, Integer> map = all(ctx);
        map.remove(code);
        save(ctx, map);
    }

    private static void save(Context ctx, Map<Integer, Integer> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        prefs(ctx).edit().putString(KEY, sb.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
