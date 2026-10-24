package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Non-fatal errors — a poster that will not load, an update that will not download, Home-key
 * takeover switched off. They go to a small on-disk queue and ride out on the 15-minute heartbeat,
 * so the console can see a feature rotting before it turns into a crash report.
 *
 * <p>Disk, not memory: a TV that reboots every night would otherwise forget everything.
 */
final class Errors {

    private static final String TAG = "TvHome.Errors";
    private static final String FILE = "pending-errors.jsonl";
    private static final int MAX_PENDING = 300;
    private static final int MAX_MESSAGE = 200;
    private static final long DEDUPE_MS = 5 * 60 * 1000L;

    private static final Object LOCK = new Object();
    private static File pending;
    private static String lastSig = "";
    private static long lastSigAt;

    private Errors() {
    }

    static void install(Context context) {
        pending = new File(context.getApplicationContext().getFilesDir(), FILE);
    }

    static void record(String key, String message) {
        if (pending == null) return;
        String msg = clean(message);
        String sig = key + "|" + msg;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            // The same failure repeats on every focus; one line per five minutes tells the story.
            if (sig.equals(lastSig) && now - lastSigAt < DEDUPE_MS) return;
            lastSig = sig;
            lastSigAt = now;
            try {
                JSONObject event = new JSONObject();
                event.put("t", now);
                event.put("k", key);
                event.put("m", msg);
                List<String> lines = readLines();
                while (lines.size() >= MAX_PENDING) lines.remove(0);
                lines.add(event.toString());
                writeLines(lines);
            } catch (Exception e) {
                Log.w(TAG, "record: " + e);
            }
        }
    }

    /** The oldest {@code max} events, or null when the queue is empty. */
    static JSONArray peek(int max) {
        if (pending == null) return null;
        synchronized (LOCK) {
            List<String> lines = readLines();
            if (lines.isEmpty()) return null;
            JSONArray out = new JSONArray();
            for (int i = 0; i < Math.min(max, lines.size()); i++) {
                try {
                    out.put(new JSONObject(lines.get(i)));
                } catch (Exception ignored) {
                    // a torn line is not worth keeping; it falls out of the queue below
                }
            }
            return out.length() == 0 ? null : out;
        }
    }

    /** Drops the sent events; call only after the server has answered. */
    static void ack(int sent) {
        if (pending == null || sent <= 0) return;
        synchronized (LOCK) {
            List<String> lines = readLines();
            if (sent >= lines.size()) {
                //noinspection ResultOfMethodCallIgnored
                pending.delete();
                return;
            }
            writeLines(new ArrayList<>(lines.subList(sent, lines.size())));
        }
    }

    private static String clean(String message) {
        String s = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        return s.length() > MAX_MESSAGE ? s.substring(0, MAX_MESSAGE) : s;
    }

    private static List<String> readLines() {
        ArrayList<String> out = new ArrayList<>();
        if (pending == null || !pending.isFile()) return out;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(pending), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) out.add(line);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void writeLines(List<String> lines) {
        File tmp = new File(pending.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            for (String line : lines) {
                out.write((line + "\n").getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "write: " + e);
            return;
        }
        if (!tmp.renameTo(pending)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
