package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The one place that talks to the author's server: the update manifest on launch and the crash /
 * feedback upload. Everything is plain HttpURLConnection + org.json, so no new dependencies.
 *
 * <p>The server lives on the bare IP on purpose: nompaws.com and its subdomains are behind the ICP
 * wall, and a TV in a group member's living room cannot reach them.
 */
final class Remote {

    private static final String TAG = "TvHome.Remote";
    private static final String BASE = "http://114.55.39.150";
    /** Upload token baked into every launcher; separate from the console's admin password. */
    // Injected from the gitignored keystore.properties at build time. Empty in public builds, which
    // disables reporting; the update check itself does not need it.
    private static final String TOKEN = BuildConfig.FEEDBACK_TOKEN;
    private static final String PREFS = "update";
    private static final String KEY_SKIP = "skip";
    private static final String DEVICE_PREFS = "device";
    private static final long BEAT_MS = 5 * 60 * 1000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Done {
        void run(boolean ok, String message);
    }

    interface Progress {
        void onProgress(int percent);
    }

    interface Found {
        void run(Update update);
    }

    static final class Update {
        int code;
        String name = "";
        String url = "";
        String notes = "";
        long size;
        String md5 = "";
    }

    private Remote() {
    }

    static int versionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String versionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ---- updates ---------------------------------------------------------------------------------

    /**
     * Checks in the background; calls back on the main thread only when there is a newer published
     * build. {@code done} always runs, with false when the manifest could not be fetched, so the
     * caller can retry a check that ran before the TV's Wi-Fi had associated.
     */
    static void checkUpdate(final Context context, final Found found, final Done done) {
        final int installed = versionCode(context);
        final String skipped = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SKIP, "");
        new Thread(() -> {
            final Update update = fetch();
            if (update == null) {
                MAIN.post(() -> done.run(false, null));
                return;
            }
            if (update.code > installed && !String.valueOf(update.code).equals(skipped)) {
                MAIN.post(() -> {
                    found.run(update);
                    done.run(true, null);
                });
            } else {
                MAIN.post(() -> done.run(true, null));
            }
        }, "update-check").start();
    }

    /**
     * The pill in the top bar: always reports back, and ignores the remembered "not now" — asking by
     * hand means the user wants to see the release even if they skipped it on the last boot.
     */
    static void checkUpdateNow(final Context context, final Found found, final Done done) {
        final int installed = versionCode(context);
        new Thread(() -> {
            final Update update = fetch();
            if (update == null) {
                MAIN.post(() -> done.run(false, null));
            } else if (update.code <= installed) {
                MAIN.post(() -> done.run(true, null));
            } else {
                MAIN.post(() -> found.run(update));
            }
        }, "update-check").start();
    }

    /**
     * The stable release to switch to from a beta build. Same package name, so this is a normal
     * update: it only installs when the stable versionCode is at least the beta's.
     */
    static void checkStable(final Found found, final Done done) {
        new Thread(() -> {
            final Update update = fetch("/update.json");
            MAIN.post(() -> {
                if (update == null || update.code <= 0) done.run(false, null);
                else found.run(update);
            });
        }, "stable-check").start();
    }

    private static Update fetch() {
        return fetch("/" + BuildConfig.UPDATE_PATH);
    }

    private static Update fetch(String path) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + path).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "tvhome");
            if (conn.getResponseCode() != 200) {
                Errors.record("update", "manifest HTTP " + conn.getResponseCode());
                return null;
            }
            JSONObject json = new JSONObject(readAll(conn.getInputStream(), 64 * 1024));
            int code = json.optInt("versionCode", 0);
            if (code <= 0) {
                Errors.record("update", "manifest without versionCode");
                return null;
            }
            Update update = new Update();
            update.code = code;
            update.name = json.optString("versionName", "");
            update.url = json.optString("url", "");
            update.notes = json.optString("notes", "");
            update.size = json.optLong("size", 0);
            update.md5 = json.optString("md5", "");
            return update;
        } catch (Exception e) {
            Log.w(TAG, "update check failed", e);
            Errors.record("update", "check " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Downloads the APK into the cache and calls back (main thread) with its path when it is verified. */
    static void download(final Context context, final Update update,
                         final Progress progress, final Done done) {
        new Thread(() -> {
            final File dir = new File(context.getCacheDir(), "update");
            final File part = new File(dir, "tvhome.part");
            final File apk = new File(dir, "tvhome.apk");
            HttpURLConnection conn = null;
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache dir");
                String spec = update.url.startsWith("http") ? update.url
                        : BASE + "/apk/" + BuildConfig.CHANNEL + "-" + update.code + ".apk";
                conn = (HttpURLConnection) new URL(spec).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "tvhome");
                if (conn.getResponseCode() != 200) {
                    throw new IOException("HTTP " + conn.getResponseCode());
                }
                long total = conn.getContentLength();
                MessageDigest digest = MessageDigest.getInstance("MD5");
                int last = -1;
                long got = 0;
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        digest.update(buf, 0, n);
                        got += n;
                        if (total > 0 && progress != null) {
                            int pct = (int) (got * 100 / total);
                            if (pct != last) {
                                last = pct;
                                final int shown = pct;
                                MAIN.post(() -> progress.onProgress(shown));
                            }
                        }
                    }
                }
                if (!update.md5.isEmpty()
                        && !hex(digest.digest()).equalsIgnoreCase(update.md5)) {
                    throw new IOException("md5 mismatch");
                }
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                if (!part.renameTo(apk)) throw new IOException("rename");
                MAIN.post(() -> done.run(true, apk.getAbsolutePath()));
            } catch (Exception e) {
                Log.w(TAG, "download failed", e);
                Errors.record("update", "download " + update.code + ": " + e.getMessage());
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                MAIN.post(() -> done.run(false, e.toString()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "update-download").start();
    }

    private static final String APK_MIME = "application/vnd.android.package-archive";

    /**
     * Hands the downloaded APK to the system installer. False when this TV has no package installer
     * this app can reach.
     *
     * <p>Tries every shape Chinese TV firmwares are known to want, in order: VIEW with a content
     * URI (AOSP, Google TV), VIEW/INSTALL_PACKAGE still with content, then below Android 7 a
     * file:// URI — some Android 6 TV installers (MIUI TV among them) only register the file
     * scheme and reject content:// with ActivityNotFoundException. Each failure is recorded so the
     * console shows which shape the TV refused.
     */
    static boolean install(Context context, File apk) {
        // PackageInstaller session first. It needs no installer activity with an intent filter, and
        // that is exactly what some TV ROMs lack: on MIUI TV 6 (MiTV4) every VIEW / INSTALL_PACKAGE
        // intent dies with ActivityNotFoundException, so the old paths could never work there.
        if (Build.VERSION.SDK_INT >= 21 && installWithSession(context, apk)) return true;
        Uri uri = null;
        try {
            uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".files", apk);
        } catch (Exception e) {
            Log.w(TAG, "FileProvider failed", e);
            Errors.record("update", "install provider: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        if (uri != null) {
            String[] actions = {Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE};
            for (String action : actions) {
                Intent intent = new Intent(action)
                        .setDataAndType(uri, APK_MIME)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                if (Intent.ACTION_INSTALL_PACKAGE.equals(action)) {
                    intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
                }
                try {
                    context.startActivity(intent);
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "install " + action + " failed", e);
                    Errors.record("update", "install " + action + ": "
                            + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
        }
        if (Build.VERSION.SDK_INT < 24) {
            File shared = copyToExternal(context, apk);
            if (shared != null) {
                try {
                    context.startActivity(new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(Uri.fromFile(shared), APK_MIME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "install file:// failed", e);
                    Errors.record("update", "install file: "
                            + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
        }
        return false;
    }

    /** Streams the APK into a PackageInstaller session; the system shows its own confirmation. */
    private static boolean installWithSession(Context context, File apk) {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        if (installer == null) return false;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            int id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (OutputStream out = session.openWrite("tvhome.apk", 0, apk.length());
                     InputStream in = new FileInputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent result = new Intent(UpdateResultReceiver.ACTION)
                        .setPackage(context.getPackageName());
                PendingIntent pending = PendingIntent.getBroadcast(context, id, result, 0);
                session.commit(pending.getIntentSender());
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "install session failed", e);
            Errors.record("update", "install session: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    /** A copy of the update outside the private cache, so a file://-only installer can read it. */
    private static File copyToExternal(Context context, File apk) {
        try {
            File dir = context.getExternalFilesDir("update");
            if (dir == null) return null;
            File out = new File(dir, "tvhome.apk");
            try (InputStream in = new java.io.FileInputStream(apk);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "copy to external failed", e);
            Errors.record("update", "copy: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    // ---- heartbeat -------------------------------------------------------------------------------

    private static boolean beating;

    /**
     * Tells the console this TV is alive, now and every five minutes. The console counts a device as
     * in use while its last beat is under ten minutes old and as off otherwise, so silence is enough
     * to mark a shutdown — no event is sent on power off, which the launcher cannot intercept.
     */
    static void startHeartbeat(final Context context) {
        if (beating) return;
        beating = true;
        final Context app = context.getApplicationContext();
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                final Context c = app;
                new Thread(() -> beat(c), "heartbeat").start();
                handler.postDelayed(this, BEAT_MS);
            }
        }, 8000);   // after the desk has settled; a cold boot is busy enough already
    }

    private static String deviceId(Context context) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString("id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("id", id).apply();
        }
        return id;
    }

    private static void beat(Context context) {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("id", deviceId(context));
            body.put("channel", BuildConfig.CHANNEL);
            body.put("versionCode", versionCode(context));
            body.put("versionName", versionName(context));
            body.put("brand", Build.MANUFACTURER);
            body.put("model", Build.MODEL);
            body.put("android", Build.VERSION.RELEASE);
            body.put("api", Build.VERSION.SDK_INT);
            // Errors ride along with the beat; a failure keeps them queued for the next round.
            JSONArray errors = Errors.peek(40);
            if (errors != null) body.put("errors", errors);
            byte[] data = body.toString().getBytes("UTF-8");
            conn = (HttpURLConnection) new URL(BASE + "/_fb/heartbeat").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(data.length);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Fb-Token", TOKEN);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(data);
            } finally {
                out.close();
            }
            if (conn.getResponseCode() == 200) {
                if (errors != null) Errors.ack(errors.length());
                // The console may have asked for this TV's recent remote keys. Pull requests are
                // cleared by the server once the log arrives; a lost upload is retried next beat.
                final String reply = readAll(conn.getInputStream(), 4096);
                boolean pull = false;
                try {
                    pull = new JSONObject(reply).optBoolean("pullKeys", false);
                } catch (Exception ignored) {
                }
                if (pull) postKeyLog(context);
            }
        } catch (Exception e) {
            Log.w(TAG, "heartbeat failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** A small JSON upload, separate from reports: no logcat, no ZIP, just the key ring buffer. */
    private static void postKeyLog(Context context) {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("id", deviceId(context));
            body.put("versionName", versionName(context));
            body.put("keys", KeyLog.snapshot());
            byte[] data = body.toString().getBytes("UTF-8");
            conn = (HttpURLConnection) new URL(BASE + "/_fb/keylog").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(data.length);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Fb-Token", TOKEN);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(data);
            }
            int code = conn.getResponseCode();
            if (code != 200) Log.w(TAG, "keylog HTTP " + code);
        } catch (Exception e) {
            Log.w(TAG, "keylog failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---- feedback --------------------------------------------------------------------------------

    /**
     * Zips device info, the crash text (if any) and a logcat tail, then posts it to the console.
     * The callbacks run on the main thread; failures are silent by design — a TV with no network
     * still has to be usable, and the console simply will not get that report.
     */
    static void sendReport(final Context context, final String kind, final String note,
                           final String crash, final Done done) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                byte[] zip = buildReport(app, kind, note, crash);
                int code = postReport(zip);
                if (code != 200) Errors.record("feedback", "upload HTTP " + code);
                MAIN.post(() -> done.run(code == 200, "HTTP " + code));
            } catch (Exception e) {
                Log.w(TAG, "report failed", e);
                Errors.record("feedback", "upload " + e.getClass().getSimpleName() + ": " + e.getMessage());
                MAIN.post(() -> done.run(false, e.toString()));
            }
        }, "report-upload").start();
    }

    private static byte[] buildReport(Context context, String kind, String note, String crash)
            throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(buffer);
        try {
            JSONObject meta = new JSONObject();
            meta.put("kind", kind);
            meta.put("note", note == null ? "" : note);
            JSONObject device = new JSONObject();
            device.put("brand", Build.MANUFACTURER);
            device.put("model", Build.MODEL);
            device.put("android", Build.VERSION.RELEASE);
            device.put("api", Build.VERSION.SDK_INT);
            meta.put("device", device);
            JSONObject app = new JSONObject();
            app.put("versionName", versionName(context));
            app.put("versionCode", versionCode(context));
            app.put("channel", BuildConfig.CHANNEL);
            meta.put("app", app);
            meta.put("build", Build.DISPLAY);
            meta.put("fingerprint", Build.FINGERPRINT);
            meta.put("ts", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
                    .format(new Date()));
            add(zip, "meta.json", meta.toString());
            if (crash != null && !crash.isEmpty()) add(zip, "crash.txt", crash);
            String log = logcat();
            if (!log.isEmpty()) add(zip, "log.txt", log);
        } finally {
            zip.finish();
            zip.close();
        }
        return buffer.toByteArray();
    }

    private static void add(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes("UTF-8"));
        zip.closeEntry();
    }

    /** Last few hundred log lines; non-system apps only see their own process, which is what we need. */
    private static String logcat() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(
                    new String[]{"logcat", "-d", "-t", "400", "-v", "threadtime"});
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && text.length() < 200_000) {
                    text.append(line).append('\n');
                }
            }
            return text.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static int postReport(byte[] body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection)
                new URL(BASE + "/_fb/report").openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Content-Type", "application/zip");
            conn.setRequestProperty("X-Fb-Token", TOKEN);
            conn.setRequestProperty("User-Agent", "tvhome");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                try (InputStream drain = in) {
                    while (drain.read() != -1) {
                        // drain so the connection can be reused
                    }
                }
            }
            return code;
        } finally {
            conn.disconnect();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream stream = in) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) > 0 && out.size() < limit) {
                out.write(buf, 0, n);
            }
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }
}
