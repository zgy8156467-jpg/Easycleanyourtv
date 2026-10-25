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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "hot" artwork behind each video app: six real posters per platform, refreshed once a day.
 *
 * <p>Every source is independent. One endpoint changing shape or timing out leaves that platform's
 * old files in place and does not touch the others. Tencent, iQiyi, Mango, Youku and CCTV name only
 * a cover and a title; Bilibili's ranking also names the playable video. Trailers for the others are
 * found by searching Bilibili for the title (see {@link BiliApi}), each platform's own list stays
 * its own, so two installed apps do not show the same six shows.
 *
 * <p>Pictures replace the old ones only after landing as a valid JPEG. Each picture has a sidecar
 * {@code N.json} holding the show's title and, when known, the Bilibili video that its trailer comes
 * from. They are written together: no metadata, no picture.
 */
final class HotPosters {

    private static final String TAG = "TvHome.Posters";
    private static final String PREFS = "posters";
    private static final int PER_SOURCE = 6;
    private static final int TIMEOUT = 12000;
    private static final long MAX_IMAGE_BYTES = 3L * 1024 * 1024;
    /** iQiyi's channel payload is ~2.5 MB uncompressed; the old 2 MB cap silently dropped it. */
    private static final long MAX_DOC_BYTES = 8L * 1024 * 1024;
    private static final long RETRY_MS = 6L * 60 * 60 * 1000;
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";

    private static final Pattern TENCENT_IMAGE =
            Pattern.compile("https://tv\\.puui\\.qpic\\.cn/[^\"'\\\\<>\\s]+?_pic_1920x800/[^\"'\\\\<>\\s]+");
    private static final Pattern TENCENT_COVER = Pattern.compile("coverPic:\\\"([^\\\"]+)\\\"");
    private static final Pattern TENCENT_TITLE = Pattern.compile("title:\\\"([^\\\"]*)\\\"");
    /** Most focusList entries leave {@code title} empty and name themselves further down. */
    private static final Pattern TENCENT_ENTRY_TITLE =
            Pattern.compile("(?:mzTitle|sourceTitle):\\\"([^\\\"]+)\\\"");
    private static final Pattern THUMBNAIL = Pattern.compile("thumbnail/\\d+x");

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

    private static Context app;
    private static final Map<String, String> labels = new HashMap<>();

    static void init(Context context) {
        app = context.getApplicationContext();
    }

    /**
     * Which poster source belongs to this package, or null when the app has none. The exact TV
     * packages come first; the name check catches vendor variants (极光/奇异果/云视听…).
     */
    static String sourceFor(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(BuildConfig.APPLICATION_ID)) return null;
        switch (pkg) {
            case "com.ktcp.video":
                return "tencent";
            case "com.gitvdemo.video":
                return "iqiyi";
            case "com.xiaodianshi.tv.yst":
                return "bilibili";
            case "com.hunantv.license":
                return "mango";
            default:
                break;
        }
        String label = label(pkg);
        if (label.contains("极光") || label.contains("腾讯")) return "tencent";
        if (label.contains("奇异果") || label.contains("爱奇艺")) return "iqiyi";
        if (label.contains("芒果")) return "mango";
        if (label.contains("哔哩") || label.contains("B站") || label.contains("小电视")) return "bilibili";
        if (label.contains("酷喵") || label.contains("优酷")) return "youku";
        if (label.contains("央视频") || label.contains("央视影音")) return "yangshipin";
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
        cleanupSingleSource();
        List<Seed> seeds;
        switch (source) {
            case "iqiyi":
                seeds = iqiyi();
                break;
            case "mango":
                seeds = mango();
                break;
            case "bilibili":
                seeds = bilibili();
                break;
            case "tencent":
                seeds = tencent();
                break;
            case "youku":
                seeds = youku();
                break;
            case "yangshipin":
                seeds = yangshipin();
                break;
            default:
                return false;
        }
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

    /** iQiyi's channel pages carry wide 2260x744 banners; the old pcw imageUrl is a 120x160 thumb. */
    private List<Seed> iqiyi() {
        String[] channels = {"tv", "variety", "cartoon"};
        List<Seed> out = new ArrayList<>();
        for (String ch : channels) {
            if (out.size() >= PER_SOURCE) break;
            String json = readDoc("https://mesh.if.iqiyi.com/portal/lw/v7/channel/" + ch
                    + "?channel_id=2", "https://www.iqiyi.com/");
            if (json == null) continue;
            try {
                collectIqiyi(new JSONObject(json), out);
            } catch (Exception e) {
                Log.w(TAG, "iqiyi " + ch + " parse: " + e.getMessage());
            }
        }
        return first(out, PER_SOURCE * 2);
    }

    /** Mango's horizontal still is imgUrlH; older entries only have the square img. */
    private List<Seed> mango() {
        List<Seed> out = new ArrayList<>();
        for (int channel : new int[]{1, 2, 3}) {
            if (out.size() >= PER_SOURCE) break;
            String json = readDoc("https://pianku.api.mgtv.com/rider/list/pcweb/v3?allowedRC=1&channelId="
                    + channel + "&pn=1&pc=6&platform=pcweb&_support=10000000", "https://www.mgtv.com/");
            if (json == null) continue;
            try {
                JSONArray docs = new JSONObject(json).getJSONObject("data").getJSONArray("hitDocs");
                for (int i = 0; i < docs.length(); i++) {
                    JSONObject o = docs.getJSONObject(i);
                    String u = o.optString("imgUrlH", "");
                    if (u.isEmpty()) u = o.optString("img", "");
                    addSeed(out, u, o.optString("title", ""), "", 0);
                }
            } catch (Exception e) {
                Log.w(TAG, "mango " + channel + " parse: " + e.getMessage());
            }
        }
        return first(out, PER_SOURCE * 2);
    }

    /**
     * Bilibili answers a cookieless app with code -352 (risk control). The fingerprint endpoint
     * hands out a buvid pair without any auth; once those are sent as cookies the ranking API works.
     * If it still refuses, the public ranking HTML has the same cover art.
     *
     * <p>This is the Bilibili app's own list. Each entry already names the video that plays behind
     * it, so no search is needed. Portrait stills are dropped: the poster area is 16:9.
     */
    private List<Seed> bilibili() {
        List<Seed> out = new ArrayList<>();
        String cookie = bilibiliCookie();
        String json = biliDoc("/x/web-interface/ranking/v2?rid=0&type=all",
                "https://www.bilibili.com/v/popular/rank/all", cookie);
        if (json != null) {
            try {
                JSONArray list = new JSONObject(json).getJSONObject("data").getJSONArray("list");
                for (int i = 0; i < list.length() && out.size() < PER_SOURCE; i++) {
                    JSONObject o = list.getJSONObject(i);
                    JSONObject dim = o.optJSONObject("dimension");
                    if (dim != null && !Aspect.wide(dim.optInt("width", 0),
                            dim.optInt("height", 0), dim.optInt("rotate", 0))) continue;
                    addSeed(out, o.optString("pic", ""), o.optString("title", ""),
                            o.optString("bvid", ""), o.optLong("cid", 0));
                }
            } catch (Exception e) {
                Log.w(TAG, "bilibili parse: " + e.getMessage()
                        + " body=" + json.substring(0, Math.min(200, json.length())));
            }
        }
        if (out.isEmpty()) {
            String html = readDoc("https://www.bilibili.com/v/popular/rank/all",
                    "https://www.bilibili.com/", cookie);
            if (html != null) {
                Matcher m = Pattern.compile(
                        "(?:https?:)?//i[0-9]\\.hdslb\\.com/bfs/archive/[^\"'\\\\<>\\s]+?\\.(?:jpg|png|webp)")
                        .matcher(html);
                while (m.find() && out.size() < PER_SOURCE * 2) addSeed(out, m.group(), "", "", 0);
            }
        }
        return first(out, PER_SOURCE);
    }

    /**
     * Tencent's channel pages embed their focus carousel as a JS object literal. Each entry carries
     * {@code title:"…"} a few hundred characters before its {@code coverPic:"…"}.
     */
    private List<Seed> tencent() {
        List<Seed> out = new ArrayList<>();
        for (String path : new String[]{"tv", "movie"}) {
            if (out.size() >= PER_SOURCE) break;
            String html = readDoc("https://v.qq.com/channel/" + path, "https://v.qq.com/");
            if (html == null) continue;
            int before = out.size();
            Matcher m = TENCENT_COVER.matcher(html);
            while (m.find() && out.size() < PER_SOURCE * 2) {
                String title = nearestTitle(html, m.start());
                if (title == null || title.isEmpty()) {
                    // Only the first entry has title:"…" before its coverPic; the rest are named in
                    // mzTitle/sourceTitle that follow it, inside the same object literal.
                    int end = html.indexOf("{id:\"", m.end());
                    if (end < 0) end = Math.min(html.length(), m.end() + 3000);
                    title = entryTitle(html.substring(m.end(), Math.max(m.end(), end)));
                }
                // A still with no name anywhere would rotate in untitled and with no clip to search
                // for; skip it and keep looking for a titled one.
                if (title == null || title.isEmpty()) continue;
                addSeed(out, m.group(1), title, "", 0);
            }
            if (out.size() > before) continue;
            // Older pages have no focusList; the plain 1920x800 stills still work, just untitled.
            Matcher legacy = TENCENT_IMAGE.matcher(html);
            while (legacy.find() && out.size() < PER_SOURCE * 2) {
                String u = legacy.group()
                        .replace("&amp;amp;", "&")
                        .replace("&amp;", "&");
                int cut = u.indexOf("&quot;");
                if (cut >= 0) u = u.substring(0, cut);
                cut = u.indexOf(");");
                if (cut >= 0) u = u.substring(0, cut);
                u = THUMBNAIL.matcher(u).replaceFirst("thumbnail/1280x");
                addSeed(out, u, "", "", 0);
            }
        }
        return first(out, PER_SOURCE);
    }

    /** The last title:"…" before a coverPic belongs to the same focusList entry. */
    private static String nearestTitle(String html, int coverAt) {
        int from = Math.max(0, coverAt - 700);
        Matcher t = TENCENT_TITLE.matcher(html.substring(from, coverAt));
        String title = null;
        while (t.find()) title = t.group(1);
        return title;
    }

    /** The first mzTitle/sourceTitle in an entry's tail. */
    private static String entryTitle(String tail) {
        Matcher t = TENCENT_ENTRY_TITLE.matcher(tail);
        return t.find() ? t.group(1) : null;
    }

    /**
     * Youku's home page ships its whole feed as {@code window.__INITIAL_DATA__} in the HTML. The
     * top carousel ("LUNBO") is the editorial pick; {@code hImg} is the 1920x1080 still, while
     * {@code img} is the portrait cover, so hImg is preferred. The literal is JavaScript, not JSON:
     * only {@code undefined} has to be rewritten before org.json will take it.
     */
    private List<Seed> youku() {
        List<Seed> out = new ArrayList<>();
        String html = readDoc("https://www.youku.com/channelweb/tv", "https://www.youku.com/");
        if (html == null) return out;
        JSONObject root = initialData(html);
        JSONArray modules = root == null ? null : root.optJSONArray("moduleList");
        if (modules == null) return out;
        for (int i = 0; i < modules.length(); i++) {
            JSONObject mod = modules.optJSONObject(i);
            if (mod == null || !mod.optString("typeName", "").contains("LUNBO")) continue;
            JSONArray comps = mod.optJSONArray("components");
            if (comps == null) continue;
            for (int c = 0; c < comps.length(); c++) {
                JSONObject comp = comps.optJSONObject(c);
                JSONArray items = comp == null ? null : comp.optJSONArray("itemList");
                if (items == null) continue;
                for (int k = 0; k < items.length() && out.size() < PER_SOURCE * 2; k++) {
                    JSONObject o = items.optJSONObject(k);
                    if (o == null) continue;
                    String u = o.optString("hImg", "");
                    if (u.isEmpty()) u = o.optString("img", "");
                    addSeed(out, u, o.optString("title", ""), "", 0);
                }
            }
            break;
        }
        return first(out, PER_SOURCE * 2);
    }

    /** The {@code window.__INITIAL_DATA__} literal, with its JavaScript {@code undefined}s folded. */
    private static JSONObject initialData(String html) {
        int at = html.indexOf("window.__INITIAL_DATA__");
        int start = at < 0 ? -1 : html.indexOf('{', at);
        int end = start < 0 ? -1 : html.indexOf("</script>", start);
        if (start < 0 || end < 0) return null;
        String json = html.substring(start, end).trim();
        if (json.endsWith(";")) json = json.substring(0, json.length() - 1);
        json = json.replace(":undefined", ":null")
                .replace(",undefined", ",null")
                .replace("[undefined", "[null");
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            Log.w(TAG, "youku state: " + e.getMessage());
            return null;
        }
    }

    /**
     * 央视频's mobile home page is plain JSON; its carousel is eight 1920x1080 banners with a promo
     * line each. One entry has a blank title and no usable name, so it is left out later.
     */
    private List<Seed> yangshipin() {
        List<Seed> out = new ArrayList<>();
        String json = readDoc("https://capi.yangshipin.cn/api/oms/m/page/PG00000001.json",
                "https://m.yangshipin.cn/");
        if (json == null) return out;
        try {
            JSONObject data = new JSONObject(json).optJSONObject("data");
            JSONArray mods = data == null ? null : data.optJSONArray("feedModuleList");
            if (mods == null) return out;
            for (int i = 0; i < mods.length(); i++) {
                JSONObject m = mods.optJSONObject(i);
                if (m == null || !"carousel".equals(m.optString("moduleType", ""))) continue;
                JSONArray items = m.optJSONArray("dataList");
                if (items == null) continue;
                for (int k = 0; k < items.length() && out.size() < PER_SOURCE * 2; k++) {
                    JSONObject o = items.optJSONObject(k);
                    if (o == null) continue;
                    addSeed(out, o.optString("coverUrl", ""), o.optString("title", ""), "", 0);
                }
                break;
            }
        } catch (Exception e) {
            Log.w(TAG, "yangshipin: " + e.getMessage());
        }
        return first(out, PER_SOURCE * 2);
    }

    /** Depth-first search: the shape of these responses changes, the key name does not. */
    private static void collectIqiyi(JSONObject o, List<Seed> out) {
        String url = o.optString("banner_image_url", "");
        if (!url.isEmpty()) {
            String title = firstNonEmpty(o, "title", "display_name", "name", "subtitle");
            addSeed(out, url, title, "", 0);
        }
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            Object v = o.opt(it.next());
            if (v instanceof JSONObject) collectIqiyi((JSONObject) v, out);
            else if (v instanceof JSONArray) collectIqiyi((JSONArray) v, out);
        }
    }

    private static void collectIqiyi(JSONArray a, List<Seed> out) {
        for (int i = 0; i < a.length(); i++) {
            Object v = a.opt(i);
            if (v instanceof JSONObject) collectIqiyi((JSONObject) v, out);
            else if (v instanceof JSONArray) collectIqiyi((JSONArray) v, out);
        }
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    /** 2.0.15's single shared folder; every per-app source is back, so it is dead weight. */
    private void cleanupSingleSource() {
        if (prefs.getInt("one2", 0) != 0) return;
        File[] files = dir("hot").listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        dir("hot").delete();
        prefs.edit().putInt("one2", 1).apply();
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
        // iQiyi now hands out AVIF, which Android 6 cannot decode. Their CDN serves the same
        // picture as JPEG when the extension asks for it.
        if (url.endsWith(".avif") && url.contains("iqiyipic.com")) {
            url = url.substring(0, url.length() - 5) + ".jpg";
        }
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
            // These CDNs answer 403 to a bare request from a TV.
            String host = new URL(url).getHost();
            if (host.contains("qpic.cn")) c.setRequestProperty("Referer", "https://v.qq.com/");
            else if (host.contains("iqiyi")) c.setRequestProperty("Referer", "https://www.iqiyi.com/");
            else if (host.contains("ykimg") || host.contains("alicdn"))
                c.setRequestProperty("Referer", "https://www.youku.com/");
            else if (host.contains("yangshipin"))
                c.setRequestProperty("Referer", "https://m.yangshipin.cn/");
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
