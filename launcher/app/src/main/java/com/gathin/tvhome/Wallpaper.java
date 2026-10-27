package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The desktop wallpaper.
 *
 * One picture only, always at {@code wallpaper.jpg} in the app's external files directory — the same
 * file the {@link Config} overlay reads. Everything here exists to keep that invariant: a new wallpaper
 * always replaces the old one rather than piling up beside it, because a TV box has very little
 * storage and a daily wallpaper left behind every morning would fill it within a couple of weeks.
 *
 * The Bing picture is downloaded at most once a day and only while the user has switched the feature
 * on. Nothing runs in the background: the refresh happens when the launcher starts.
 */
final class Wallpaper {

    private static final String TAG = "TvHome.Wallpaper";
    private static final String PREFS = "wallpaper";
    private static final String KEY_AUTO = "bing_daily";
    private static final String KEY_LAST_DAY = "bing_last_day";

    private static final String FILE_NAME = "wallpaper.jpg";
    /**
     * Bing answers on both hosts with the same JSON. Mainland TVs often cannot resolve
     * www.bing.com (8 devices reported it in the feedback console) while cn.bing.com, the China
     * endpoint, resolves fine, so it is tried first; the picture URL then stays on whichever
     * host answered.
     *
     * Android 4.x boxes reset the TLS handshake to Bing ("Connection reset by peer") even with
     * TLS 1.2 forced on, and that is most of the legacy wallpaper failures in the console. Those
     * versions ask over plain HTTP instead; Bing answers the same JSON, and the picture URL keeps
     * the host's scheme, so the download stays on HTTP too. Nothing private crosses this request.
     */
    private static final String[] BING_HOSTS = Build.VERSION.SDK_INT < 21
            ? new String[]{"http://cn.bing.com", "http://www.bing.com"}
            : new String[]{"https://cn.bing.com", "https://www.bing.com"};
    private static final String BING_PATH = "/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN";
    private static final int TIMEOUT = 15000;
    /** Bing serves 1920x1080 JPEGs; anything larger than this is wasted on a TV. */
    private static final int MAX_EDGE = 1920;
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private final Context ctx;
    private final SharedPreferences prefs;

    Wallpaper(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean autoDaily() {
        return prefs.getBoolean(KEY_AUTO, false);
    }

    /**
 * Turn the daily refresh on or off. Switching it off only stops the automatic changes — the picture
 * already on the screen stays put, because someone who has been watching the Bing picture all week
 * usually wants to keep today's one, not have it disappear the moment they stop the daily updates.
 * Removing it is a separate, explicit choice ({@link #clear()}).
 */
void setAutoDaily(boolean on) {
        prefs.edit().putBoolean(KEY_AUTO, on).apply();
}

    /** True when today's Bing picture is already the one on disk. */
    boolean isCurrent() {
        return hasWallpaper() && today().equals(prefs.getString(KEY_LAST_DAY, ""));
    }

    /** True when a picture is already applied. */
    boolean hasWallpaper() {
        File f = file();
        return f != null && f.isFile() && f.length() > 0;
    }

    File file() {
        for (File f : targets()) if (f.isFile()) return f;
        return targets()[0];
    }

    /**
     * Where the picture may live, best first. External storage is the documented spot (the Config
     * overlay reads it), but some MIUI TVs answer EACCES for the app's own external folder — even
     * though AOSP allows it — so internal storage is the fallback that always works.
     */
    private File[] targets() {
        File dir = ctx.getExternalFilesDir(null);
        File internal = new File(ctx.getFilesDir(), FILE_NAME);
        return dir == null ? new File[]{internal} : new File[]{new File(dir, FILE_NAME), internal};
    }

    /** Delete the current wallpaper; the desktop falls back to its plain background. */
    boolean clear() {
        boolean ok = true;
        for (File f : targets()) ok &= !f.exists() || f.delete();
        return ok;
    }

    /**
     * Fetch today's Bing picture, unless it was already downloaded today or today is not available.
     * Blocking — call it off the main thread. Returns true when the wallpaper changed.
     */
    boolean refreshBingIfDue() {
        if (!autoDaily()) return false;
        String today = today();
        if (today.equals(prefs.getString(KEY_LAST_DAY, "")) && hasWallpaper()) return false;

        String url = bingImageUrl();
        if (url == null) {
            Log.w(TAG, "could not read the Bing image address");
            Errors.record("wallpaper", "Bing address unavailable");
            return false;
        }
        byte[] bytes = download(url);
        if (bytes == null) return false;
        // Only remember the day once the picture is on disk, or a failed download would block
        // today's retry until tomorrow.
        if (write(bytes)) prefs.edit().putString(KEY_LAST_DAY, today).apply();
        return hasWallpaper();
    }

    /** The Bing endpoint returns a small JSON document; read the first image URL out of it. */
    private String bingImageUrl() {
        Exception last = null;
        for (String host : BING_HOSTS) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(host + BING_PATH).openConnection();
                Tls12.apply(c);
                c.setConnectTimeout(TIMEOUT);
                c.setReadTimeout(TIMEOUT);
                c.setRequestProperty("Accept", "application/json");
                try (InputStream in = c.getInputStream()) {
                    String json = readAll(in);
                    int at = json.indexOf("\"url\"");
                    if (at < 0) return null;
                    int from = json.indexOf(':', at) + 1;
                    int quote = json.indexOf('"', from);
                    int end = json.indexOf('"', quote + 1);
                    if (quote < 0 || end < 0) return null;
                    // Bing returns a relative path such as /th?id=OHR.SomeName_EN-US1234567890.
                    String path = json.substring(quote + 1, end);
                    return path.startsWith("http") ? path : host + path;
                }
            } catch (Exception e) {
                last = e;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        if (last != null) {
            Log.w(TAG, "Bing lookup failed: " + last.getMessage());
            Errors.record("wallpaper", "Bing " + last.getClass().getSimpleName() + ": " + last.getMessage());
        }
        return null;
    }

    private byte[] download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            Tls12.apply(c);
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() / 100 != 2) return null;
            long len = c.getContentLength(); // API 1; getContentLengthLong() is API 24
            if (len > MAX_BYTES) return null;              // a multi-megabyte JPEG is not a wallpaper
            try (InputStream in = c.getInputStream()) {
                return readBytes(in);
            }
        } catch (Exception e) {
            Log.w(TAG, "download failed: " + e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Write the picture as the one andonly wallpaper. The file is replaced in place, so the picture
     * from yesterday is gone the moment the new one lands — no accumulation, no extra cleanup.
     */
    boolean write(byte[] bytes) {
        for (File f : targets()) if (commit(f, bytes)) return true;
        return false;
    }

    private boolean commit(File f, byte[] bytes) {
        File tmp = new File(f.getParentFile(), FILE_NAME + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "could not write the wallpaper: " + e.getMessage());
            tmp.delete();
            return false;
        }
        // BitmapFactory is the real test: a truncated or non-image file must not become the wallpaper.
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(tmp.getPath(), o);
        if (o.outWidth <= 0) {
            tmp.delete();
            return false;
        }
        if (f.exists() && !f.delete()) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(f);
    }

    /**
     * Copy a picture the user picked themselves. Anything already in place is replaced, so picking
     * a new picture does not leave the previous one on disk.
     */
    boolean importFrom(Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > MAX_BYTES) return false;
                out.write(buf, 0, n);
            }
            return write(out.toByteArray());
        } catch (Exception e) {
            Log.w(TAG, "import failed: " + e.getMessage());
            return false;
        }
    }

    /** Wallpaper file size in bytes, for the panel;0 when there is none. */
    long sizeBytes() {
        File f = file();
        return f != null && f.isFile() ? f.length() : 0;
    }

    private static String today() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) + "-" + (c.get(java.util.Calendar.MONTH) + 1)
                + "-" + c.get(java.util.Calendar.DAY_OF_MONTH);
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static byte[] readBytes(InputStream in) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            return null;
        }
    }
}