package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The dynamic-wallpaper library: short silent 1080p loops hosted on our own server. One clip is
 * picked per day by date, so the desk changes every morning without a setting to touch, and the
 * clip is deleted whenever the desk is left (opening an app, shutdown, a boot after a power cut):
 * a 1 GB TV box notices a few megabytes, and re-fetching one clip costs less than keeping them.
 *
 * <p>Nothing is bundled and nothing is kept overnight. The server is the bare IP because the
 * domain is still behind the ICP wall.
 */
final class LoopWallpaper {

    private static final String TAG = "TvHome.LoopWallpaper";
    /**
     * Through /_fb/, the one path nginx already forwards to the receiver on every firmware; the
     * receiver strips the prefix and serves /wallpapers/ from its own directory.
     */
    private static final String BASE = "http://114.55.39.150/_fb";
    private static final String INDEX = BASE + "/wallpapers/index.json";
    private static final String PREFS = "wallpaper";
    private static final String KEY_ON = "loop_on";
    private static final String KEY_ID = "loop_id";
    private static final String KEY_NAME = "loop_name";
    private static final String KEY_URL = "loop_url";
    private static final String KEY_DAY = "loop_day";
    /** "", a clip id, or "local": daily rotation, one library clip, or the user's own video. */
    private static final String KEY_PICK = "loop_pick";
    private static final String LOCAL_ID = "local";
    private static final String LOCAL_FILE = "local-wallpaper.mp4";
    /** A hand-picked clip is not checked against this; it is the user's own file. */
    private static final long MAX_LOCAL_BYTES = 150L * 1024 * 1024;
    private static final int TIMEOUT = 15000;
    /** A 1080p ten-second loop is under 20 MB; anything past this is not what the index promised. */
    private static final long MAX_BYTES = 40L * 1024 * 1024;

    static final class Clip {
        final String id;
        final String name;
        final String url;
        final String thumb;

        Clip(String id, String name, String url, String thumb) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.thumb = thumb;
        }
    }

    private LoopWallpaper() {
    }

    /** Days since the epoch: the whole library rotates on this, the same clip for every TV. */
    static long day() {
        return System.currentTimeMillis() / 86400000L;
    }

    /** The server's list, or the last list this box downloaded, or nothing when both are missing. */
    static List<Clip> list(Context ctx) {
        byte[] fresh = fetch(INDEX, 256 * 1024);
        if (fresh != null) {
            List<Clip> clips = parse(new String(fresh, java.nio.charset.Charset.forName("UTF-8")));
            if (!clips.isEmpty()) {
                write(new File(cacheDir(ctx), "index.json"), fresh);
                return clips;
            }
        }
        try (FileInputStream in = new FileInputStream(new File(cacheDir(ctx), "index.json"))) {
            return parse(readAll(in));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static List<Clip> parse(String json) {
        List<Clip> out = new ArrayList<>();
        try {
            JSONArray clips = new JSONObject(json).optJSONArray("clips");
            for (int i = 0; clips != null && i < clips.length(); i++) {
                JSONObject o = clips.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id");
                String file = o.optString("file");
                if (id.isEmpty() || file.isEmpty()) continue;
                out.add(new Clip(id, o.optString("name", id), BASE + "/wallpapers/" + file,
                        BASE + "/wallpapers/" + o.optString("thumb", "")));
            }
        } catch (Exception e) {
            Log.w(TAG, "bad index: " + e);
        }
        return out;
    }

    static File cacheDir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "loop");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** The clip for right now, already on disk, or null while it still has to be fetched. */
    static File file(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_ON, false)) return null;
        String pick = p.getString(KEY_PICK, "");
        if (LOCAL_ID.equals(pick)) {
            File f = localFile(ctx);
            return f.isFile() && f.length() > 0 ? f : null;
        }
        if (pick.isEmpty() && p.getLong(KEY_DAY, -1) != day()) return null;
        String id = pick.isEmpty() ? p.getString(KEY_ID, "") : pick;
        if (id.isEmpty()) return null;
        File f = new File(cacheDir(ctx), id + ".mp4");
        return f.isFile() && f.length() > 0 ? f : null;
    }

    static boolean isDaily(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PICK, "").isEmpty();
    }

    /** The user's own video; lives in files/ so a cache wipe cannot take it. */
    static File localFile(Context ctx) {
        return new File(ctx.getFilesDir(), LOCAL_FILE);
    }

    static boolean hasLocal(Context ctx) {
        File f = localFile(ctx);
        return f.isFile() && f.length() > 0;
    }

    /** Delete the imported clip; if it was on screen, fall back to the daily rotation. */
    static void removeLocal(Context ctx) {
        deleteLocal(ctx);
        if (!LOCAL_ID.equals(pick(ctx))) return;
        if (on(ctx)) {
            chooseDaily(ctx);
        } else {
            // Off stays off; turning it back on later starts from the daily rotation.
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .remove(KEY_PICK).apply();
        }
    }

    static boolean on(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false);
    }

    static void setOn(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
        // Off keeps the user's own video and the chosen clip on disk: turning it back on should not
        // need another download. Only the switch goes.
        if (!on) clearCache(ctx);
    }

    /** One clip from the library, every day, until the user picks something else. */
    static void choose(Context ctx, Clip clip) {
        clearCache(ctx);
        deleteLocal(ctx);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PICK, clip.id)
                .putString(KEY_ID, clip.id)
                .putString(KEY_NAME, clip.name)
                .putString(KEY_URL, clip.url)
                .putBoolean(KEY_ON, true)
                .remove(KEY_DAY)
                .apply();
    }

    /** Back to the date rotation. */
    static void chooseDaily(Context ctx) {
        clearCache(ctx);
        deleteLocal(ctx);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PICK, "")
                .remove(KEY_ID).remove(KEY_NAME).remove(KEY_URL).remove(KEY_DAY)
                .putBoolean(KEY_ON, true)
                .apply();
    }

    /** Copy a video the user picked (TV storage or a USB stick) in as the wallpaper. */
    static File importLocal(Context ctx, android.net.Uri uri) {
        File out = localFile(ctx);
        File tmp = new File(ctx.getFilesDir(), LOCAL_FILE + ".part");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_LOCAL_BYTES) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                        return null;
                    }
                    os.write(buf, 0, n);
                }
                os.flush();
            }
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            if (!tmp.renameTo(out)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            Log.w(TAG, "local video: " + e);
            return null;
        }
        clearCache(ctx);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PICK, LOCAL_ID)
                .putString(KEY_NAME, ctx.getString(R.string.loop_local_name))
                .putBoolean(KEY_ON, true)
                .remove(KEY_ID).remove(KEY_URL).remove(KEY_DAY)
                .apply();
        return out;
    }

    static String pick(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PICK, "");
    }

    private static void deleteLocal(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        localFile(ctx).delete();
    }

    static String name(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, "");
    }

    /** The clip for today's date, before anything has been downloaded. */
    static Clip today(Context ctx) {
        List<Clip> clips = list(ctx);
        if (clips.isEmpty()) return null;
        String pick = pick(ctx);
        if (!pick.isEmpty() && !LOCAL_ID.equals(pick)) {
            for (Clip c : clips) if (c.id.equals(pick)) return c;
        }
        return clips.get((int) (day() % clips.size()));
    }

    /** A grab of a library clip's poster, for the chooser grid. Blocking; call from a worker. */
    static android.graphics.Bitmap thumb(Clip clip) {
        if (clip.thumb.isEmpty()) return null;
        byte[] bytes = fetch(clip.thumb, 512 * 1024);
        return bytes == null ? null : android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    /**
     * Today's clip on disk, downloading it first when needed. Blocking; call from a worker thread.
     * Returns the previous clip when today's cannot be fetched, so an offline TV keeps a backdrop
     * instead of a black screen.
     */
    static synchronized File ensure(Context ctx) {
        File have = file(ctx);
        if (have != null) return have;
        if (LOCAL_ID.equals(pick(ctx))) return null;   // the user's file is gone; nothing to fetch
        Clip clip = today(ctx);
        if (clip == null) return newest(ctx);
        File out = new File(cacheDir(ctx), clip.id + ".mp4");
        if (!(out.isFile() && out.length() > 0)) {
            byte[] bytes = fetch(clip.url, MAX_BYTES);
            if (bytes == null || !write(out, bytes)) return newest(ctx);
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ID, clip.id)
                .putString(KEY_NAME, clip.name)
                .putString(KEY_URL, clip.url)
                .putLong(KEY_DAY, day())
                .apply();
        deleteOthers(ctx, out);
        return out;
    }

    /** Turn it off and free the disk immediately. */
    static void clear(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_ON).remove(KEY_ID).remove(KEY_NAME).remove(KEY_URL)
                .remove(KEY_DAY).remove(KEY_PICK).apply();
        clearCache(ctx);
        deleteLocal(ctx);
    }

    /** Delete every downloaded clip file, keeping the cached index. */
    static void clearCache(Context ctx) {
        File[] files = new File(ctx.getCacheDir(), "loop").listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".mp4")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static void deleteOthers(Context ctx, File keep) {
        File[] files = cacheDir(ctx).listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".mp4") && !f.equals(keep)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** Any clip still on disk, newest first; used when today's download fails offline. */
    private static File newest(Context ctx) {
        File[] files = cacheDir(ctx).listFiles();
        File best = null;
        if (files == null) return null;
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".mp4") || f.length() == 0) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    private static byte[] fetch(String url, long max) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            Tls12.apply(c);
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() / 100 != 2) return null;
            long len = c.getContentLength();
            if (len > max) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() + n > max) return null;
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } catch (Exception e) {
            Log.w(TAG, "fetch " + url + ": " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean write(File f, byte[] bytes) {
        File tmp = new File(f.getParentFile(), f.getName() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        return tmp.renameTo(f);
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
    }
}
