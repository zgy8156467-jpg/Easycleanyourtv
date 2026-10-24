package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Weather for the top bar: today plus up to two more days. Located by IP unless the user picked a
 * city by hand (a TV connected to a router with a VPN or a satellite box can otherwise sit in the
 * wrong province), cached for half an hour.
 *
 * <p>ip-api is plain HTTP on purpose — old boxes often cannot complete a TLS handshake — and hands
 * back Chinese city names with {@code lang=zh-CN}. Open-Meteo is free and needs no key.
 */
final class Weather {

    static final int CLEAR = 0, CLOUDY = 1, RAIN = 2, SNOW = 3, FOG = 4, THUNDER = 5;

    private static final String TAG = "TvHome.Weather";
    private static final String PREFS = "weather";
    private static final String KEY_CITY = "city";
    private static final String KEY_TEMP = "temp";
    private static final String KEY_KIND = "kind";
    private static final String KEY_AT = "at";
    private static final String KEY_VIDEO = "video";
    /** The three-day forecast as a compact JSON array [{k,x,n},…]. */
    private static final String KEY_FORECAST = "forecast";
    /** 1, 2 or 3: how many days the top bar shows. Cache always holds all three. */
    private static final String KEY_DAYS = "days";
    /** A city the user picked by hand; empty means IP lookup. */
    private static final String KEY_PLACE = "place";
    private static final String KEY_LAT = "place_lat";
    private static final String KEY_LON = "place_lon";
    private static final long TTL_MS = 30 * 60 * 1000L;
    private static final int TIMEOUT = 12000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Incremented by every fetch; an older thread that finishes late must not overwrite a newer one. */
    private static int latest;

    interface Callback {
        /** Runs on the main thread; null when nothing could be fetched and nothing was cached. */
        void onWeather(Now now);
    }

    static final class Day {
        final int kind;
        final int max;
        final int min;

        Day(int kind, int max, int min) {
            this.kind = kind;
            this.max = max;
            this.min = min;
        }
    }

    static final class Now {
        final String city;
        final int temp;
        final int kind;
        final Day[] days;

        Now(String city, int temp, int kind, Day[] days) {
            this.city = city;
            this.temp = temp;
            this.kind = kind;
            this.days = days == null ? new Day[0] : days;
        }
    }

    private Weather() {
    }

    static Now cached(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long at = p.getLong(KEY_AT, 0);
        if (at == 0) return null;
        return new Now(p.getString(KEY_CITY, ""), p.getInt(KEY_TEMP, 0), p.getInt(KEY_KIND, CLEAR),
                parseDays(p.getString(KEY_FORECAST, "")));
    }

    static boolean fresh(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long at = p.getLong(KEY_AT, 0);
        if (at <= 0 || System.currentTimeMillis() - at >= TTL_MS) return false;
        // A cache written before multi-day support has no forecast, so it needs one fetch.
        if (p.getString(KEY_FORECAST, "").isEmpty()) return false;
        // A cache saved for the IP location is not fresh once the user has picked another city.
        String place = p.getString(KEY_PLACE, "");
        return place.isEmpty() || place.equals(p.getString(KEY_CITY, ""));
    }

    static void refreshIfDue(Context ctx, Callback cb) {
        if (fresh(ctx)) {
            if (cb != null) cb.onWeather(cached(ctx));
            return;
        }
        fetch(ctx, cb);
    }

    /** Ignore the cache and ask the network. */
    static void fetch(Context ctx, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        final int token = ++latest;
        new Thread(() -> {
            Now result = null;
            try {
                String city;
                double lat;
                double lon;
                SharedPreferences p = prefs(app);
                String place = p.getString(KEY_PLACE, "");
                if (!place.isEmpty()) {
                    city = place;
                    lat = p.getFloat(KEY_LAT, 0f);
                    lon = p.getFloat(KEY_LON, 0f);
                    if (lat == 0 && lon == 0) throw new Exception("no coordinates for " + place);
                } else {
                    JSONObject ip = new JSONObject(read(
                            "http://ip-api.com/json/?fields=status,city,lat,lon&lang=zh-CN"));
                    if (!"success".equals(ip.optString("status"))) throw new Exception("ip lookup");
                    city = ip.optString("city", "");
                    lat = ip.getDouble("lat");
                    lon = ip.getDouble("lon");
                }
                // Plain HTTP: Open-Meteo answers on it, and Android 6 does not trust the
                // Let's Encrypt chain its HTTPS endpoint serves.
                JSONObject om = new JSONObject(read("http://api.open-meteo.com/v1/forecast?latitude="
                        + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                        + "&forecast_days=3&timezone=auto"));
                JSONObject cur = om.optJSONObject("current");
                if (cur != null) {
                    int code = cur.optInt("weather_code", 0);
                    int temp = (int) Math.round(cur.optDouble("temperature_2m", 0));
                    result = new Now(city, temp, kindFor(code), daysFrom(om.optJSONObject("daily")));
                    Log.i(TAG, "weather " + city + " " + temp + "° code=" + code);
                }
            } catch (Exception e) {
                // No network, blocked host, old TLS: keep showing whatever was cached.
                Log.w(TAG, "weather fetch failed", e);
            }
            // A newer request (the user picked another city) already owns the cache and the callback.
            if (token != latest) return;
            if (result != null) save(app, result);
            final Now done = result != null ? result : cached(app);
            if (cb != null) MAIN.post(() -> cb.onWeather(done));
        }, "weather").start();
    }

    private static void save(Context ctx, Now now) {
        JSONArray days = new JSONArray();
        try {
            for (Day d : now.days) {
                JSONObject o = new JSONObject();
                o.put("k", d.kind);
                o.put("x", d.max);
                o.put("n", d.min);
                days.put(o);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not encode forecast", e);
        }
        prefs(ctx).edit()
                .putString(KEY_CITY, now.city)
                .putInt(KEY_TEMP, now.temp)
                .putInt(KEY_KIND, now.kind)
                .putString(KEY_FORECAST, days.toString())
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply();
    }

    private static Day[] parseDays(String json) {
        if (json == null || json.isEmpty()) return new Day[0];
        try {
            JSONArray arr = new JSONArray(json);
            Day[] days = new Day[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                days[i] = new Day(o.optInt("k", CLOUDY), o.optInt("x", 0), o.optInt("n", 0));
            }
            return days;
        } catch (Exception e) {
            return new Day[0];
        }
    }

    /** Open-Meteo daily arrays, index 0 = today. Missing blocks simply mean no multi-day line. */
    private static Day[] daysFrom(JSONObject daily) {
        if (daily == null) return new Day[0];
        JSONArray codes = daily.optJSONArray("weather_code");
        JSONArray max = daily.optJSONArray("temperature_2m_max");
        JSONArray min = daily.optJSONArray("temperature_2m_min");
        if (codes == null || max == null || min == null) return new Day[0];
        int n = Math.min(codes.length(), Math.min(max.length(), min.length()));
        Day[] days = new Day[n];
        for (int i = 0; i < n; i++) {
            days[i] = new Day(kindFor(codes.optInt(i, 0)),
                    (int) Math.round(max.optDouble(i, 0)),
                    (int) Math.round(min.optDouble(i, 0)));
        }
        return days;
    }

    /** 1–3 days shown in the top bar. */
    static int forecastDays(Context ctx) {
        int d = prefs(ctx).getInt(KEY_DAYS, 1);
        return d < 1 ? 1 : Math.min(d, 3);
    }

    static void setForecastDays(Context ctx, int days) {
        prefs(ctx).edit().putInt(KEY_DAYS, days < 1 ? 1 : Math.min(days, 3)).apply();
    }

    /** The city the user picked, or "" when the location comes from the IP. */
    static String place(Context ctx) {
        return prefs(ctx).getString(KEY_PLACE, "");
    }

    static void setPlace(Context ctx, String name, double lat, double lon) {
        prefs(ctx).edit()
                .putString(KEY_PLACE, name)
                .putFloat(KEY_LAT, (float) lat)
                .putFloat(KEY_LON, (float) lon)
                .apply();
    }

    static void clearPlace(Context ctx) {
        prefs(ctx).edit().remove(KEY_PLACE).remove(KEY_LAT).remove(KEY_LON).apply();
    }

    static boolean videoOn(Context ctx) {
        return prefs(ctx).getBoolean(KEY_VIDEO, false);
    }

    static void setVideoOn(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_VIDEO, on).apply();
    }

    static int kindFor(int code) {
        if (code == 0 || code == 1) return CLEAR;
        if (code == 2 || code == 3) return CLOUDY;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 67) return RAIN;
        if (code >= 71 && code <= 77) return SNOW;
        if (code >= 80 && code <= 82) return RAIN;
        if (code == 85 || code == 86) return SNOW;
        if (code >= 95) return THUNDER;
        return CLOUDY;
    }

    static int label(int kind) {
        switch (kind) {
            case RAIN:
                return R.string.weather_rain;
            case SNOW:
                return R.string.weather_snow;
            case FOG:
                return R.string.weather_fog;
            case THUNDER:
                return R.string.weather_thunder;
            case CLOUDY:
                return R.string.weather_cloudy;
            default:
                return R.string.weather_clear;
        }
    }

    static int icon(int kind) {
        switch (kind) {
            case RAIN:
                return R.drawable.ic_weather_rain;
            case SNOW:
                return R.drawable.ic_weather_snow;
            case FOG:
                return R.drawable.ic_weather_fog;
            case THUNDER:
                return R.drawable.ic_weather_thunder;
            case CLOUDY:
                return R.drawable.ic_weather_cloudy;
            default:
                return R.drawable.ic_weather_clear;
        }
    }

    static int video(int kind) {
        switch (kind) {
            case RAIN:
                return R.raw.weather_rain;
            case SNOW:
                return R.raw.weather_snow;
            case FOG:
                return R.raw.weather_fog;
            case THUNDER:
                return R.raw.weather_thunder;
            case CLOUDY:
                return R.raw.weather_cloudy;
            default:
                return R.raw.weather_clear;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String read(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        Tls12.apply(c);
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setRequestProperty("User-Agent", "tvhome/" + com.gathin.tvhome.BuildConfig.VERSION_NAME);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
