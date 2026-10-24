package com.gathin.tvhome;

import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedList;

/**
 * The last few hundred remote keys the desk itself received, kept in memory for the console to
 * pull when a group member reports that some button does nothing. Only keys handled by this app
 * are recorded — the accessibility service never reads other apps' input.
 */
final class KeyLog {

    private static final int MAX = 500;
    private static final LinkedList<JSONObject> LOG = new LinkedList<>();

    private KeyLog() {
    }

    static synchronized void record(int keyCode, int repeat, String focus) {
        JSONObject row = new JSONObject();
        try {
            row.put("t", System.currentTimeMillis());
            row.put("k", keyCode);
            row.put("n", KeyEvent.keyCodeToString(keyCode));
            if (repeat > 0) row.put("r", repeat);
            if (focus != null && !focus.isEmpty()) row.put("f", focus);
        } catch (Exception ignored) {
            return;
        }
        LOG.add(row);
        while (LOG.size() > MAX) LOG.removeFirst();
    }

    static synchronized JSONArray snapshot() {
        JSONArray out = new JSONArray();
        for (JSONObject row : LOG) out.put(row);
        return out;
    }
}
