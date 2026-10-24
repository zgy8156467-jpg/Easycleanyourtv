package com.gathin.tvhome;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Finds a real, playable trailer for a poster on Bilibili.
 *
 * <p>iQiyi, Mango and Tencent stream only signed/DRM URLs; Bilibili's {@code playurl} still hands out
 * plain MP4s. Its own ranking already names a video per poster; the other three are matched by
 * searching the show's title for a 预告/片花.
 *
 * <p>Its main CDN ({@code *.edge.mountaintoys.cn}) chains to GlobalSign Root R46, which this TV's
 * certificate store does not have. The backup hosts on {@code *.bilivideo.com} chain to R3/R5 and do
 * work, so URLs are ordered by host before playback.
 */
final class BiliApi {

    private static final String TAG = "TvHome.Trailers";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final long CACHE_MS = 30 * 60 * 1000L;
    private static final int MAX_URLS = 3;

    /** What playback needs, plus the Bilibili identity so the next focus can skip the search. */
    static final class Clip {
        final List<String> urls;
        final String bvid;
        final long cid;

        Clip(List<String> urls, String bvid, long cid) {
            this.urls = urls;
            this.bvid = bvid;
            this.cid = cid;
        }
    }

    private static final class Cached {
        final List<String> urls;
        final String bvid;
        final long cid;
        final long at;

        Cached(Clip clip) {
            this.urls = clip.urls;
            this.bvid = clip.bvid;
            this.cid = clip.cid;
            this.at = System.currentTimeMillis();
        }
    }

    private static final Map<String, Cached> cache = new HashMap<>();

    /**
     * Resolve a clip. Runs on a background thread, never throws, and returns null when there is
     * nothing to play (the desk then keeps its slow zoom instead).
     */
    static Clip resolve(String source, HotPosters.Poster poster) {
        try {
            String bvid = poster.bvid;
            long cid = poster.cid;
            String key = source + "|" + (bvid == null ? "" : bvid) + "|" + cid + "|" + poster.title;
            synchronized (cache) {
                Cached c = cache.get(key);
                if (c != null && System.currentTimeMillis() - c.at < CACHE_MS) {
                    return new Clip(c.urls, c.bvid, c.cid);
                }
            }

            if (bvid == null || bvid.isEmpty()) {
                if (poster.title.isEmpty()) return null;
                bvid = search(poster.title);
                if (bvid == null) {
                    Log.i(TAG, "no search hit for " + poster.title);
                    return null;
                }
                cid = 0;
            }
            if (cid <= 0) cid = viewCid(bvid);
            if (cid <= 0) return null;

            List<String> urls = playUrls(bvid, cid);
            if (urls.isEmpty()) return null;

            // Remembered in memory even if the sidecar cannot be written: the poster object is what
            // the next focus reads.
            poster.bvid = bvid;
            poster.cid = cid;
            Clip clip = new Clip(urls, bvid, cid);
            synchronized (cache) {
                cache.put(key, new Cached(clip));
                cache.put((bvid + "|" + cid + "|" + poster.title), new Cached(clip));
            }
            return clip;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "resolve failed: " + e);
            Errors.record("trailer", "resolve " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /** Pick the best-looking "show + 预告" result. Bilibili search needs the buvid cookies. */
    private static String search(String show) {
        try {
            String keyword = URLEncoder.encode(show + " 预告", "UTF-8");
            String json = HotPosters.biliDoc(
                    "/x/web-interface/search/type?search_type=video&keyword=" + keyword,
                    REFERER, HotPosters.bilibiliCookie());
            if (json == null) return null;
            JSONObject data = new JSONObject(json).optJSONObject("data");
            JSONArray result = data == null ? null : data.optJSONArray("result");
            if (result == null) return null;
            String best = null;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < result.length(); i++) {
                JSONObject o = result.optJSONObject(i);
                if (o == null) continue;
                String bvid = o.optString("bvid", "");
                if (bvid.isEmpty()) continue;
                String title = o.optString("title", "").replaceAll("<[^>]+>", "");
                int seconds = duration(o.optString("duration", ""));
                int score = score(title, seconds);
                if (score > bestScore) {
                    bestScore = score;
                    best = bvid;
                }
            }
            if (best != null) Log.i(TAG, "search " + show + " -> " + best + " (" + bestScore + ")");
            return best;
        } catch (Exception e) {
            Log.w(TAG, "search " + show + ": " + e.getMessage());
            return null;
        }
    }

    private static int score(String title, int seconds) {
        int score = 0;
        if (title.contains("预告")) score += 100;
        if (title.contains("片花")) score += 90;
        if (title.contains("定档")) score += 60;
        if (title.contains("抢先")) score += 40;
        if (title.contains("花絮")) score += 20;
        if (seconds >= 20 && seconds <= 300) score += 50;
        else if (seconds > 300 && seconds <= 600) score += 10;
        else if (seconds > 600) score -= 80;
        else if (seconds > 0) score -= 50;
        return score;
    }

    /** "4:56", "12:30" or "1:02:03" to seconds; 0 when unknown. */
    private static int duration(String text) {
        if (text == null || text.isEmpty()) return 0;
        String[] parts = text.split(":");
        try {
            int total = 0;
            for (String p : parts) total = total * 60 + Integer.parseInt(p.trim());
            return total;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long viewCid(String bvid) {
        String json = HotPosters.biliDoc(
                "/x/web-interface/view?bvid=" + bvid,
                REFERER, HotPosters.bilibiliCookie());
        if (json == null) return 0;
        try {
            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null) return 0;
            long cid = data.optLong("cid", 0);
            if (cid > 0) return cid;
            JSONArray pages = data.optJSONArray("pages");
            if (pages != null && pages.length() > 0) return pages.optJSONObject(0).optLong("cid", 0);
        } catch (Exception e) {
            Log.w(TAG, "view " + bvid + ": " + e.getMessage());
        }
        return 0;
    }

    private static List<String> playUrls(String bvid, long cid) {
        String json = HotPosters.biliDoc(
                "/x/player/playurl?bvid=" + bvid + "&cid=" + cid
                        + "&qn=16&fnval=0&fnver=0&fourk=0",
                REFERER, null);
        if (json == null) return Collections.emptyList();
        try {
            JSONObject data = new JSONObject(json).optJSONObject("data");
            JSONArray durl = data == null ? null : data.optJSONArray("durl");
            if (durl == null || durl.length() == 0) return Collections.emptyList();
            List<String> all = new ArrayList<>();
            for (int i = 0; i < durl.length(); i++) {
                JSONObject d = durl.optJSONObject(i);
                if (d == null) continue;
                all.add(d.optString("url", ""));
                JSONArray backup = d.optJSONArray("backup_url");
                if (backup != null) {
                    for (int j = 0; j < backup.length(); j++) all.add(backup.optString(j, ""));
                }
            }
            List<String> usable = new ArrayList<>();
            for (String u : all) if (u != null && !u.isEmpty()) usable.add(u);
            // Bilivideo first (R3/R5, trusted), mountaintoys last (R46, not trusted here).
            Collections.sort(usable, (a, b) -> {
                int ra = hostRank(a), rb = hostRank(b);
                return ra < rb ? -1 : (ra == rb ? 0 : 1);
            });
            all = usable;
            List<String> urls = new ArrayList<>(new LinkedHashSet<>(all));
            if (urls.size() > MAX_URLS) urls = new ArrayList<>(urls.subList(0, MAX_URLS));
            for (String u : urls) Log.i(TAG, "url " + hostOf(u));
            return urls;
        } catch (Exception e) {
            Log.w(TAG, "playurl " + bvid + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static int hostRank(String url) {
        String host = hostOf(url);
        if (host.contains("bilivideo.com")) return 0;
        if (host.contains("mountaintoys")) return 2;
        return host.isEmpty() ? 3 : 1;
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }
}
