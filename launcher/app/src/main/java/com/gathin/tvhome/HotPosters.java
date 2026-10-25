package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * The "hot" artwork behind video apps: six posters, refreshed once a day.
 *
 * <p>One source for every app, so a new video app needs no work here. Bilibili's drama and film
 * rankings carry the cover, the title and the playable video in the same response, which also makes
 * the 16:9 rule free: an entry is known-good before its picture is downloaded. The per-platform
 * scrapers this class used to carry are gone; they only ever produced a picture whose trailer then
 * had to be guessed by title.
 *
 * <p>Pictures replace the old ones only after landing as a valid JPEG. Each picture has a sidecar
 * {@code N.json} holding the title and the Bilibili video that its trailer comes from. They are
 * written together: no metadata, no picture.
 */
final class HotPosters {

    private static final String TAG = "TvHome.Posters";
    private static final String PREFS = "posters";
    private static final int PER_SOURCE = 6;
    private static final int TIMEOUT = 12000;
    private static final long MAX_IMAGE_BYTES = 3L * 1024 * 1024;
    private static final long MAX_DOC_BYTES = 2L * 1024 * 1024;
    private static final long RETRY_MS = 6L * 60 * 60 * 1000;
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";

    /** Ranking partitions: drama and film. */
    private static final int RANK_DRAMA = 11;
    private static final int RANK_FILM = 23;
    private static final String RANK_REFER = "https://www.bilibili.com/v/popular/rank/all";

    /** One poster on disk plus what the desk needs to label and animate it. */
    static final class Poster {
        final File file;
        final int index;
        final String title;
        /** The video that plays behind this poster, when the source named one. */
        String bvid;
        long cid;

        Poster(File file, int index, String title, String bvid, long cid) {
            this.file = file;
            this.index = index;
            this.title = title == null ? "" : title;
            this.bvid = bvid == null ? "" : bvid;
            this.cid = cid;
        }
    }

    /** A picture the parsers found, before it has been downloaded. */
    private static final class Seed {
        final String url;
        final String title;
        /** The video that plays behind this picture. */
        String bvid;
        final long cid;

        Seed(String url, String title, String bvid, long cid) {
            this.url = url;
            this.title = title == null ? "" : title;
            this.bvid = bvid == null ? "" : bvid;
            this.cid = cid;
        }
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<String>());
    private static volatile String buvidCookie;
    private static volatile long buvidAt;

    HotPosters(Context context) {
        this.ctx = context.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Every video app draws from the same set: no per-app source to adapt. */
    private static final String SOURCE = "hot";

    /** The app's own name is the reliable signal on Chinese TVs; package names are only a backup. */
    private static final String[] LABEL_WORDS = {"视频", "影视", "电影", "影院", "剧场", "播放", "直播",
            "电视", "卫视", "剧集", "芒果", "爱奇艺", "优酷", "哔哩", "B站", "咪咕", "云视听", "极光",
            "南瓜", "电视猫", "泰捷", "当贝影视"};
    private static final String[] PACKAGE_WORDS = {"video", "movie", "vod"};
    /** Shells that happen to carry a convenient word in their package name. */
    private static final String[] SYSTEM_WORDS = {"launcher", "settings", "home", "wallpaper",
            "screensaver", "store", "market", "inputmethod", "systemui", "permission", "installer",
            "projection", "gallery"};

    private static Context app;
    private static final Map<String, String> labels = new HashMap<>();

    static void init(Context context) {
        app = context.getApplicationContext();
    }

    /** {@link #SOURCE} for apps that play video, null for everything else. */
    static String sourceFor(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(BuildConfig.APPLICATION_ID)) return null;
        String name = pkg.toLowerCase(Locale.ROOT);
        for (String word : SYSTEM_WORDS) {
            if (name.contains(word)) return null;
        }
        String label = label(pkg);
        for (String word : LABEL_WORDS) {
            if (label.contains(word)) return SOURCE;
        }
        for (String word : PACKAGE_WORDS) {
            if (name.contains(word)) return SOURCE;
        }
        return null;
    }

    private static String label(String pkg) {
        synchronized (labels) {
            String cached = labels.get(pkg);
            if (cached != null) return cached;
        }
        String label = "";
        if (app != null) {
            try {
                label = app.getPackageManager()
                        .getApplicationLabel(app.getPackageManager().getApplicationInfo(pkg, 0))
                        .toString();
            } catch (Exception ignored) {
            }
        }
        synchronized (labels) {
            labels.put(pkg, label);
        }
        return label;
    }

    /** Cached posters for an app, in display order. Empty when nothing has been fetched yet. */
    List<Poster> listFor(String pkg) {
        String source = sourceFor(pkg);
        if (source == null) return Collections.emptyList();
        File[] files = dir(source).listFiles();
        if (files == null || files.length == 0) return Collections.emptyList();
        List<Poster> out = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && f.length() > 0 && f.getName().matches("\\d+\\.jpg")) out.add(poster(f));
        }
        Collections.sort(out, (a, b) -> a.index < b.index ? -1 : (a.index == b.index ? 0 : 1));
        // Caches written before untitled entries were skipped can still hold a blank one. Hide it
        // while titled posters exist, so rotation never lands on a caption-less still.
        List<Poster> titled = new ArrayList<>();
        for (Poster p : out) if (!p.title.isEmpty()) titled.add(p);
        return titled.isEmpty() ? out : titled;
    }

    private static Poster poster(File jpg) {
        int index = indexOf(jpg);
        String title = "";
        String bvid = "";
        long cid = 0;
        File meta = new File(jpg.getParentFile(), index + ".json");
        if (meta.isFile()) {
            try {
                JSONObject o = new JSONObject(readText(meta));
                title = o.optString("t", "");
                bvid = o.optString("b", "");
                cid = o.optLong("c", 0);
            } catch (Exception e) {
                Log.w(TAG, "bad sidecar " + meta.getName() + ": " + e.getMessage());
            }
        }
        return new Poster(jpg, index, title, bvid, cid);
    }

    /**
     * Fetch this app's posters if they are missing, a day old, or were cached before sidecars
     * existed. Never blocks: work happens on one background thread and {@code done} runs on the main
     * thread when it settles.
     */
    void refreshIfDueAsync(String pkg, final Runnable done) {
        final String source = sourceFor(pkg);
        if (source == null) {
            if (done != null) main.post(done);
            return;
        }
        if (!hasNetwork() || !due(source)) {
            if (done != null) main.post(done);
            return;
        }
        if (!inFlight.add(source)) {
            // Another focus already started this source; let its own completion bring the art in.
            if (done != null) main.postDelayed(done, 1500);
            return;
        }
        prefs.edit().putLong("try_" + source, System.currentTimeMillis()).apply();
        new Thread(() -> {
            boolean ok;
            try {
                ok = fetch(source);
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, source + " failed: " + e);
                Errors.record("poster", source + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
                ok = false;
            }
            // One migration attempt is enough. Sidecars that still failed are not worth hammering
            // the source for on every focus; tomorrow's refresh tries again.
            prefs.edit().putInt("meta1_" + source, 1).apply();
            if (ok) prefs.edit().putString("day_" + source, today()).apply();
            inFlight.remove(source);
            if (done != null) main.post(done);
        }, "posters-" + source).start();
    }

    private boolean due(String source) {
        boolean have = hasFiles(source);
        // Pictures cached by an older build have no sidecar: fetch once more so titles appear.
        if (have && metaMissing(source)) return true;
        if (today().equals(prefs.getString("day_" + source, "")) && have) return false;
        // Nothing on disk: try again on the next focus. With old files to fall back on, a failed
        // fetch waits six hours instead of hammering a broken endpoint every time focus moves.
        if (!have) return true;
        long last = prefs.getLong("try_" + source, 0);
        return System.currentTimeMillis() - last > RETRY_MS;
    }

    /** True when at least one cached picture predates the sidecar files. */
    private boolean metaMissing(String source) {
        if (prefs.getInt("meta1_" + source, 0) != 0) return false;
        File[] files = dir(source).listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (!f.getName().matches("\\d+\\.jpg")) continue;
            File meta = new File(f.getParentFile(), indexOf(f) + ".json");
            if (!meta.isFile()) return true;
        }
        return false;
    }

    private boolean hasFiles(String source) {
        File[] files = dir(source).listFiles();
        return files != null && files.length > 0;
    }

    private File dir(String source) {
        File root = ctx.getExternalFilesDir(null);
        return root == null ? new File(ctx.getFilesDir(), "posters/" + source)
                : new File(root, "posters/" + source);
    }

    private static int indexOf(File f) {
        try {
            return Integer.parseInt(f.getName().substring(0, f.getName().length() - 4));
        } catch (NumberFormatException e) {
            return 999;
        }
    }

    /** Remember which Bilibili video plays behind one poster. Keeps the title already on disk. */
    void saveResolvedTrailer(String source, int index, String bvid, long cid) {
        if (source == null || bvid == null || bvid.isEmpty()) return;
        File jpg = new File(dir(source), index + ".jpg");
        if (!jpg.isFile()) return;
        File meta = new File(jpg.getParentFile(), index + ".json");
        String title = "";
        try {
            if (meta.isFile()) title = new JSONObject(readText(meta)).optString("t", "");
        } catch (Exception ignored) {
        }
        writeSidecar(jpg, new Seed("", title, bvid, cid));
    }

    private boolean fetch(String source) {
        cleanupOldSources();
        List<Seed> seeds = hot();
        if (seeds.isEmpty()) {
            Log.w(TAG, source + ": no poster URLs");
            return false;
        }
        File d = dir(source);
        if (!d.isDirectory() && !d.mkdirs()) return false;
        int saved = 0;
        int index = 0;
        for (Seed seed : seeds) {
            if (index >= PER_SOURCE) break;
            File target = new File(d, index + ".jpg");
            index++;
            if (!downloadTo(seed.url, target)) continue;
            if (!writeSidecar(target, seed)) {
                target.delete();
                continue;
            }
            saved++;
        }
        Log.i(TAG, source + ": " + saved + " of " + index + " saved");
        return saved > 0;
    }

    private boolean writeSidecar(File jpg, Seed seed) {
        File meta = new File(jpg.getParentFile(), indexOf(jpg) + ".json");
        File part = new File(meta.getParentFile(), meta.getName() + ".part");
        try {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("t", seed.title);
            o.put("b", seed.bvid);
            o.put("c", seed.cid);
            o.put("ts", System.currentTimeMillis() / 1000);
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(o.toString().getBytes("UTF-8"));
            }
            if (meta.exists() && !meta.delete()) {
                part.delete();
                return false;
            }
            if (!part.renameTo(meta)) {
                part.delete();
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "sidecar " + meta.getName() + ": " + e.getMessage());
            part.delete();
            return false;
        }
    }

    private static String readText(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        }
    }

    // ---- sources ---------------------------------------------------------------------------------

    /** Drama and film rankings, interleaved, portrait entries dropped before any download. */
    private List<Seed> hot() {
        List<Seed> drama = ranking(RANK_DRAMA);
        List<Seed> film = ranking(RANK_FILM);
        List<Seed> out = new ArrayList<>();
        for (int i = 0; i < Math.max(drama.size(), film.size()); i++) {
            if (i < drama.size()) out.add(drama.get(i));
            if (i < film.size()) out.add(film.get(i));
        }
        return first(out, PER_SOURCE * 2);
    }

    /**
     * One Bilibili ranking partition. Each item already carries the cover, the title and the video
     * that will play behind it, so there is nothing to match by hand and nothing to adapt per app.
     */
    private List<Seed> ranking(int rid) {
        List<Seed> out = new ArrayList<>();
        String json = biliDoc("/x/web-interface/ranking/v2?rid=" + rid + "&type=all",
                RANK_REFER, bilibiliCookie());
        if (json == null) return out;
        try {
            JSONArray list = new JSONObject(json).getJSONObject("data").getJSONArray("list");
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                JSONObject dim = o.optJSONObject("dimension");
                if (dim != null && !Aspect.wide(dim.optInt("width", 0),
                        dim.optInt("height", 0), dim.optInt("rotate", 0))) continue;
                addSeed(out, o.optString("pic", ""), o.optString("title", ""),
                        o.optString("bvid", ""), o.optLong("cid", 0));
            }
        } catch (Exception e) {
            Log.w(TAG, "ranking " + rid + ": " + e.getMessage());
        }
        return out;
    }

    /** The four per-platform folders from before the single-source change; nothing reads them now. */
    private void cleanupOldSources() {
        if (prefs.getInt("one1", 0) != 0) return;
        for (String old : new String[]{"tencent", "iqiyi", "bilibili", "mango"}) {
            File[] files = dir(old).listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            dir(old).delete();
        }
        prefs.edit().putInt("one1", 1).apply();
    }

    /** Cached for an hour: the fingerprint endpoint is cheap but not free. */
    static String bilibiliCookie() {
        long now = System.currentTimeMillis();
        if (buvidCookie != null && now - buvidAt < 60 * 60 * 1000) return buvidCookie;
        String json = biliDoc("/x/frontend/finger/spi",
                "https://www.bilibili.com/", null);
        if (json == null) return "";
        try {
            JSONObject d = new JSONObject(json).optJSONObject("data");
            if (d == null) return "";
            buvidCookie = "buvid3=" + d.optString("b_3", "") + "; buvid4=" + d.optString("b_4", "");
            buvidAt = now;
            return buvidCookie;
        } catch (Exception e) {
            return "";
        }
    }

    private static void addSeed(List<Seed> out, String url, String title, String bvid, long cid) {
        url = normalize(url);
        if (url == null) return;
        for (Seed s : out) if (s.url.equals(url)) return;
        out.add(new Seed(url, cleanTitle(title), bvid, cid));
    }

    private static String normalize(String url) {
        if (url == null) return null;
        url = url.trim().replace("\\/", "/");
        if (url.isEmpty()) return null;
        if (url.startsWith("//")) url = "https:" + url;
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        if (!url.startsWith("https://")) return null;
        return url;
    }

    /** Search results wrap the matched words in &lt;em&gt; tags; posters should not show them. */
    private static String cleanTitle(String title) {
        if (title == null) return "";
        return title.replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
    }

    private static List<Seed> first(List<Seed> all, int n) {
        return all.size() <= n ? all : new ArrayList<>(all.subList(0, n));
    }

    // ---- network ---------------------------------------------------------------------------------

    static String readDoc(String url, String referer) {
        return readDoc(url, referer, null);
    }

    /**
     * Mirrors that answer the same API paths when a TV's DNS cannot resolve api.bilibili.com.
     * Seen on a MiTV-ANSP0 (Android 9): www.bilibili.com resolves, api.bilibili.com does not,
     * so every poster fetch died with UnknownHostException and the desk stayed on wallpaper.
     */
    private static final String[] BILI_MIRRORS = {"api.bilibili.cn", "api.biliapi.net"};

    /** Read a Bilibili API path, retrying on the mirrors before giving up. */
    static String biliDoc(String path, String referer, String cookie) {
        String doc = readDoc("https://api.bilibili.com" + path, referer, cookie);
        for (int i = 0; doc == null && i < BILI_MIRRORS.length; i++) {
            doc = readDoc("https://" + BILI_MIRRORS[i] + path, referer, cookie);
        }
        return doc;
    }

    static String readDoc(String url, String referer, String cookie) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            Tls12.apply(c);
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            if (referer != null) c.setRequestProperty("Referer", referer);
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                Log.w(TAG, "http " + code + " " + url);
                Errors.record("net", c.getURL().getHost() + " HTTP " + code);
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_DOC_BYTES) return null;
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), "UTF-8");
            }
        } catch (Exception e) {
            Log.w(TAG, "fetch " + url + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
            Errors.record("net", "fetch " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private boolean downloadTo(String url, File target) {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            Tls12.apply(c);
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                Log.w(TAG, "http " + code + " " + url);
                return false;
            }
            long len = c.getContentLength();   // often -1; the running count below is the real cap
            if (len > MAX_IMAGE_BYTES) {
                Log.w(TAG, "too large " + len + " " + url);
                return false;
            }
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_IMAGE_BYTES) {
                        out.close();
                        part.delete();
                        return false;
                    }
                    out.write(buf, 0, n);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(part.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                Log.w(TAG, "not an image (" + part.length() + "B) " + url);
                part.delete();
                return false;
            }
            if (target.exists() && !target.delete()) {
                part.delete();
                return false;
            }
            boolean renamed = part.renameTo(target);
            if (!renamed) Log.w(TAG, "rename failed " + target);
            return renamed;
        } catch (Exception e) {
            Log.w(TAG, "download " + url + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
            part.delete();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private boolean hasNetwork() {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private static String today() {
        java.util.Calendar c = java.util.Calendar.getInstance(Locale.CHINA);
        return c.get(java.util.Calendar.YEAR) + "-" + (c.get(java.util.Calendar.MONTH) + 1)
                + "-" + c.get(java.util.Calendar.DAY_OF_MONTH);
    }
}
