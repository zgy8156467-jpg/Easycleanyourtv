package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.TextureView;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * The picture behind the desk: the user's wallpaper underneath, the focused video app's hot poster on
 * top, and two scrims so the clock and dock stay readable on a bright picture.
 *
 * <p>Only two full-screen bitmaps exist at a time. Everything is decoded on one background thread at
 * RGB_565 and screen size; a result whose generation is stale is dropped before it ever reaches a
 * view. Bitmaps on screen are never recycled until they have been detached.
 *
 * <p>A poster is also the stage for its own trailer: three seconds after it appears, a muted clip
 * plays over it for a few seconds. The poster layer sits above the VideoView and simply goes
 * transparent while the clip runs, so there is never a black frame between the two.
 */
public class WallpaperStage extends FrameLayout {

    /** What the desk does when a poster appears or is swiped between. */
    interface PosterListener {
        void onPosterShown(String pkg, List<HotPosters.Poster> posters, int index);

        void onPosterGone();
    }

    private static final long ROTATE_MS = 20_000L;
    private static final long CLIP_DELAY_MS = 3_000L;
    private static final int FADE_IN_MS = 350;
    private static final int FADE_OUT_MS = 180;

    private static final int IDLE = 0, WAIT = 1, RESOLVING = 2, PLAYING = 3, DONE = 4;

    private final ImageView base = new ImageView(getContext());
    /** Weather loop, under the poster layer and above the still wallpaper. */
    private final TextureView weatherLayer = new SafeTextureView(getContext());
    private MediaPlayer weatherPlayer;
    private int weatherRes;
    /** A resource that failed to prepare on this box; retrying it every weather tick only spams. */
    private int weatherFailedRes;
    private boolean weatherSurfaceReady;
    private boolean weatherFrameShown;
    private final ImageView poster = new ImageView(getContext());
    private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader topScrim, bottomScrim;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Static so a destroyed/recreated activity does not spawn another downloader. */
    private static HotPosters store;
    private final PosterTrailer trailer;

    private PosterListener listener;
    private List<HotPosters.Poster> list = Collections.emptyList();
    private int posterIndex;
    private boolean browsing;
    private boolean rotatePending;
    private int videoState = IDLE;
    /** The desk is not on screen: no rotation, no downloads, no clip. */
    private boolean paused;

    private int defaultGen, posterGen;
    private Bitmap defaultBmp, posterBmp;
    private String posterPkg;

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            if (paused || posterPkg == null || list.size() < 2) return;
            // On the dots the user is choosing a poster; leave it alone until they leave the strip.
            if (browsing) return;
            if (videoState == PLAYING || videoState == RESOLVING) {
                // The clip is the thing on screen; turn the page when it ends.
                rotatePending = true;
                return;
            }
            rotateNow();
        }
    };

    private final Runnable playClip = new Runnable() {
        @Override
        public void run() {
            if (paused || posterPkg == null || list.isEmpty()) return;
            int i = Math.min(posterIndex, list.size() - 1);
            HotPosters.Poster p = list.get(i);
            // Nothing to search for (untitled legacy still): skip the lookup, keep the Ken Burns.
            if (p.title.isEmpty() && (p.bvid == null || p.bvid.isEmpty())) return;
            videoState = RESOLVING;
            trailer.play(HotPosters.sourceFor(posterPkg), list.get(i));
        }
    };

    public WallpaperStage(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (store == null) store = new HotPosters(context);
        base.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(base, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        weatherLayer.setAlpha(0f);
        addView(weatherLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        weatherLayer.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                weatherSurfaceReady = true;
                if (weatherRes != 0) playWeather(weatherRes);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                fitWeather();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                weatherSurfaceReady = false;
                stopWeatherPlayer();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                // The first frame is already in the texture; only now is it safe to show the layer
                // without flashing black over the still wallpaper.
                if (weatherPlayer != null && !weatherFrameShown) {
                    weatherFrameShown = true;
                    weatherLayer.animate().alpha(1f).setDuration(320).start();
                }
            }
        });
        // Under the poster: it prepares and shows behind an opaque still, so no black flash.
        trailer = new PosterTrailer(this, store, new PosterTrailer.Listener() {
            @Override
            public void onStarted() {
                videoState = PLAYING;
                syncWeatherPlayback();
                // The texture needs a frame or two before it reaches the screen; hiding the
                // poster right away would flash the wallpaper in between.
                main.postDelayed(() -> {
                    if (videoState == PLAYING) poster.setAlpha(0f);
                }, 150);
            }

            @Override
            public void onFinished() {
                videoState = DONE;
                syncWeatherPlayback();
                if (posterBmp != null) poster.setAlpha(1f);
                if (rotatePending) {
                    rotatePending = false;
                    main.post(WallpaperStage.this::rotateNow);
                }
            }
        });
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setAlpha(0f);
        addView(poster, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setClipChildren(true);
    }

    void setPosterListener(PosterListener l) {
        listener = l;
    }

    // ---- default wallpaper -----------------------------------------------------------------------

    /** Show this file as the backdrop, or nothing when null. */
    public void setDefault(File file) {
        final int gen = ++defaultGen;
        if (file == null || !file.isFile() || file.length() == 0) {
            base.setImageDrawable(null);
            recycleDefault();
            return;
        }
        decode(file, false, bmp -> {
            if (bmp == null) return;
            if (gen != defaultGen) {
                bmp.recycle();
                return;
            }
            Bitmap old = defaultBmp;
            defaultBmp = bmp;
            base.setImageBitmap(bmp);
            if (old != null) old.recycle();
        });
    }

    public boolean hasImage() {
        return defaultBmp != null;
    }

    // ---- weather loop ----------------------------------------------------------------------------

    /**
     * Play one of the bundled weather loops behind the desk, or 0 for the still wallpaper again.
     * The clips are silent and loop on the player itself, so nothing here needs a timer.
     */
    void setWeatherVideo(int res) {
        if (res == 0) {
            clearWeatherVideo();
            return;
        }
        if (res == weatherFailedRes) return;   // this loop cannot decode here; keep the still wallpaper
        if (res == weatherRes && (weatherPlayer != null || !weatherSurfaceReady)) return;
        playWeather(res);
    }

    /** Explicitly off (or a new reading): let a previously failed loop try once more. */
    void clearWeatherVideo() {
        weatherRes = 0;
        weatherFailedRes = 0;
        stopWeatherPlayer();
    }

    boolean hasWeather() {
        return weatherRes != 0 || weatherPlayer != null;
    }

    private void playWeather(int res) {
        weatherRes = res;
        if (!weatherSurfaceReady) return;   // the surface listener starts it when the view is ready
        stopWeatherPlayer();
        try {
            MediaPlayer mp = MediaPlayer.create(getContext(), res);
            if (mp == null) {
                weatherRes = 0;
                weatherFailedRes = res;
                return;
            }
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setSurface(new Surface(weatherLayer.getSurfaceTexture()));
            mp.setOnVideoSizeChangedListener((m, w, h) -> fitWeather());
            mp.setOnErrorListener((m, what, extra) -> {
                // Do not call clearWeatherVideo(): that resets the failed mark and the next weather
                // tick retries the same undecodable loop.
                weatherFailedRes = res;
                weatherRes = 0;
                stopWeatherPlayer();
                return true;
            });
            mp.start();
            weatherPlayer = mp;
            weatherFrameShown = false;
            fitWeather();
        } catch (Exception e) {
            weatherRes = 0;
            weatherFailedRes = res;
            stopWeatherPlayer();
            return;
        }
        syncWeatherPlayback();
    }

    private void stopWeatherPlayer() {
        weatherFrameShown = false;
        weatherLayer.setAlpha(0f);
        MediaPlayer mp = weatherPlayer;
        weatherPlayer = null;
        if (mp != null) {
            try {
                mp.setSurface(null);
            } catch (Exception ignored) {
            }
            try {
                mp.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** Paused in the background, and under a poster trailer so two decoders never fight on a 1 GB box. */
    private void syncWeatherPlayback() {
        MediaPlayer mp = weatherPlayer;
        if (mp == null) return;
        boolean play = !paused && videoState != PLAYING && videoState != RESOLVING;
        try {
            if (play) {
                if (!mp.isPlaying()) mp.start();
            } else if (mp.isPlaying()) {
                mp.pause();
            }
        } catch (IllegalStateException ignored) {
        }
    }

    /** Center-crop the loop, the same way the still wallpaper fills the screen. */
    private void fitWeather() {
        MediaPlayer mp = weatherPlayer;
        int vw = mp == null ? 0 : mp.getVideoWidth();
        int vh = mp == null ? 0 : mp.getVideoHeight();
        int w = getWidth(), h = getHeight();
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return;
        float scale = Math.max((float) w / vw, (float) h / vh);
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((w - vw * scale) / 2f, (h - vh * scale) / 2f);
        weatherLayer.setTransform(m);
    }

    // ---- hot posters -----------------------------------------------------------------------------

    /** Show the poster set for this app; apps without a source fall back to the default wallpaper. */
    public void showForPackage(String pkg) {
        final String source = HotPosters.sourceFor(pkg);
        if (source == null) {
            clearPoster();
            return;
        }
        if (pkg.equals(posterPkg)) return;
        posterPkg = pkg;
        posterIndex = 0;
        browsing = false;
        rotatePending = false;
        videoState = IDLE;
        syncWeatherPlayback();
        final int gen = ++posterGen;
        main.removeCallbacks(rotate);
        main.removeCallbacks(playClip);
        trailer.stop();
        list = store.listFor(pkg);
        if (!list.isEmpty()) {
            display(list.get(0), 0, gen);
            scheduleTimers();
        } else {
            detachPoster();
            notifyGone();
        }
        // Always ask: the daily refresh is what brings titles in, and an old cache without sidecars
        // must be refilled once. due() keeps this cheap.
        store.refreshIfDueAsync(pkg, () -> main.post(() -> afterRefresh(pkg, gen)));
    }

    private void afterRefresh(String pkg, int gen) {
        if (gen != posterGen || !pkg.equals(posterPkg)) return;
        List<HotPosters.Poster> fresh = store.listFor(pkg);
        if (fresh.isEmpty()) {
            if (list.isEmpty()) notifyGone();
            return;
        }
        boolean same = sameFiles(list, fresh);
        boolean metaChanged = same && !sameMeta(list, fresh);
        list = fresh;
        if (same) {
            // Files are the same; titles or trailer ids may have just arrived. If the clip was
            // skipped for want of a title, let it try again now that there is one.
            notifyShown();
            if (metaChanged && !browsing && videoState != PLAYING && videoState != RESOLVING) {
                scheduleTimers();
            }
            return;
        }
        main.removeCallbacks(playClip);
        trailer.stop();
        videoState = IDLE;
        if (posterBmp != null) poster.setAlpha(1f);
        posterIndex = Math.min(posterIndex, list.size() - 1);
        display(list.get(posterIndex), posterIndex, gen);
        scheduleTimers();
    }

    private static boolean sameFiles(List<HotPosters.Poster> a, List<HotPosters.Poster> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).file.equals(b.get(i).file)) return false;
        }
        return true;
    }

    private static boolean sameMeta(List<HotPosters.Poster> a, List<HotPosters.Poster> b) {
        for (int i = 0; i < a.size(); i++) {
            HotPosters.Poster x = a.get(i), y = b.get(i);
            if (!x.title.equals(y.title)) return false;
            if (x.cid != y.cid) return false;
            if (x.bvid == null ? y.bvid != null : !x.bvid.equals(y.bvid)) return false;
        }
        return true;
    }

    /**
     * Swipe to another poster from the dot strip. Resets the three-second clip and the twenty-second
     * rotation so the chosen one stays put long enough to be seen.
     */
    void selectPoster(int index) {
        if (index < 0 || index >= list.size()) return;
        if (index == posterIndex) {
            // Focusing the dot of the poster already shown cut the clip when the strip took focus;
            // restart the three-second count from here.
            scheduleTimers();
            return;
        }
        main.removeCallbacks(rotate);
        main.removeCallbacks(playClip);
        trailer.stop();
        videoState = IDLE;
        syncWeatherPlayback();
        if (posterBmp != null) poster.setAlpha(1f);
        final int gen = ++posterGen;
        display(list.get(index), index, gen);
        scheduleTimers();
    }

    List<HotPosters.Poster> postersFor(String pkg) {
        if (pkg == null) return Collections.emptyList();
        if (pkg.equals(posterPkg)) return list;
        return store.listFor(pkg);
    }

    int currentPosterCount() {
        return list.size();
    }

    int currentPosterIndex() {
        return posterIndex;
    }

    /** The dot strip has focus: stop the clock and the clip until the user has picked. */
    void setBrowsing(boolean on) {
        if (browsing == on) return;
        browsing = on;
        if (on) {
            main.removeCallbacks(playClip);
            trailer.stop();
            videoState = IDLE;
            syncWeatherPlayback();
            rotatePending = false;
            if (posterBmp != null) poster.setAlpha(1f);
        } else {
            scheduleTimers();
        }
    }

    /** The activity went to the background: silence everything until it is back on screen. */
    void setPaused(boolean on) {
        if (paused == on) return;
        paused = on;
        if (on) {
            main.removeCallbacks(rotate);
            main.removeCallbacks(playClip);
            trailer.stop();
            videoState = IDLE;
            rotatePending = false;
            if (posterBmp != null) poster.setAlpha(1f);
        } else {
            scheduleTimers();
        }
        syncWeatherPlayback();
    }

    void stopVideo() {
        main.removeCallbacks(playClip);
        trailer.stop();
        videoState = IDLE;
        if (posterBmp != null) poster.setAlpha(1f);
        syncWeatherPlayback();
    }

    /** Window focus came back while a dock tile is still selected. */
    void resumeVideo() {
        if (posterPkg == null || list.isEmpty()) return;
        if (videoState == PLAYING || videoState == RESOLVING) return;
        main.removeCallbacks(playClip);
        main.postDelayed(playClip, 1200);
        videoState = WAIT;
    }

    private void rotateNow() {
        if (browsing || posterPkg == null || list.size() < 2) return;
        main.removeCallbacks(playClip);
        trailer.stop();
        videoState = IDLE;
        syncWeatherPlayback();
        if (posterBmp != null) poster.setAlpha(1f);
        final int gen = ++posterGen;
        int next = (posterIndex + 1) % list.size();
        display(list.get(next), next, gen);
        scheduleTimers();
    }

    private void scheduleTimers() {
        main.removeCallbacks(rotate);
        main.removeCallbacks(playClip);
        if (posterPkg == null || list.isEmpty()) return;
        if (list.size() > 1) main.postDelayed(rotate, ROTATE_MS);
        // Fetch during the three seconds the poster is on its own, not after them. A dot with focus
        // still counts as looking at the poster, so the clip runs there too; switching dots resets
        // the three seconds.
        trailer.prefetch(HotPosters.sourceFor(posterPkg),
                list.get(Math.min(posterIndex, list.size() - 1)));
        main.postDelayed(playClip, CLIP_DELAY_MS);
        videoState = WAIT;
    }

    /** Focus left the tiles: let the app art go, back to the plain wallpaper. */
    public void clearPoster() {
        posterPkg = null;
        posterGen++;
        main.removeCallbacks(rotate);
        main.removeCallbacks(playClip);
        trailer.stop();
        videoState = IDLE;
        syncWeatherPlayback();
        browsing = false;
        rotatePending = false;
        list = Collections.emptyList();
        notifyGone();
        if (posterBmp == null) {
            detachPoster();
            return;
        }
        if (AppAdapter.reducedMotion || Build.VERSION.SDK_INT < 16) {
            detachPoster();
            return;
        }
        poster.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(this::detachPoster).start();
    }

    private void display(HotPosters.Poster p, int index, int gen) {
        posterIndex = index;
        notifyShown();
        showPoster(p.file, index, gen);
    }

    private void showPoster(File file, int index, int gen) {
        // Mango's 16:9 stills carry a sponsor strip across the very top, right where the clock pills
        // sit. Trim it; none of the other sources need it.
        boolean cropTop = "mango".equals(HotPosters.sourceFor(posterPkg));
        decode(file, cropTop, bmp -> {
            if (bmp == null) return;
            if (gen != posterGen) {
                bmp.recycle();
                return;
            }
            posterIndex = index;
            poster.animate().cancel();   // a pending fade-out must not clear the new picture
            poster.setScaleX(1f);
            poster.setScaleY(1f);
            poster.setTranslationX(0f);
            poster.setTranslationY(0f);
            final Bitmap old = posterBmp;
            posterBmp = bmp;
            if (AppAdapter.reducedMotion) {
                poster.setImageBitmap(bmp);
                poster.setAlpha(1f);
                if (old != null) old.recycle();
                return;
            }
            if (poster.getAlpha() == 0f) {
                poster.setImageBitmap(bmp);
                if (Build.VERSION.SDK_INT >= 16) {
                    poster.animate().alpha(1f).setDuration(FADE_IN_MS)
                            .withEndAction(this::startKenBurns).start();
                } else {
                    poster.setAlpha(1f);
                    startKenBurns();
                }
                if (old != null) old.recycle();
            } else if (Build.VERSION.SDK_INT < 16) {
                poster.setImageBitmap(bmp);
                poster.setAlpha(1f);
                if (old != null) old.recycle();
                startKenBurns();
            } else {
                // Fade the previous picture out before swapping: one ImageView, no third bitmap.
                poster.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(() -> {
                    poster.setImageBitmap(bmp);
                    poster.animate().alpha(1f).setDuration(FADE_IN_MS)
                            .withEndAction(this::startKenBurns).start();
                    if (old != null) old.recycle();
                }).start();
            }
        });
    }

    /** A slow push-in keeps a still alive when there is no clip to play. */
    private void startKenBurns() {
        if (AppAdapter.reducedMotion || posterBmp == null || posterPkg == null) return;
        poster.setPivotX(poster.getWidth() / 2f);
        poster.setPivotY(poster.getHeight() / 2f);
        float dir = (posterIndex % 2 == 0) ? 1f : -1f;
        poster.animate().scaleX(1.08f).scaleY(1.08f)
                .translationX(dir * 6 * getResources().getDisplayMetrics().density)
                .translationY(-3 * getResources().getDisplayMetrics().density)
                .setDuration(25_000)
                .setInterpolator(new LinearInterpolator())
                .start();
    }

    /** Take the poster layer off screen (and free its bitmap) without touching the default layer. */
    private void detachPoster() {
        poster.animate().cancel();
        poster.setAlpha(0f);
        poster.setImageDrawable(null);
        poster.setScaleX(1f);
        poster.setScaleY(1f);
        poster.setTranslationX(0f);
        poster.setTranslationY(0f);
        recyclePoster();
    }

    /** Free every bitmap. Called when Home is in the background and the box is short on memory. */
    public void release() {
        defaultGen++;
        posterGen++;
        main.removeCallbacks(rotate);
        main.removeCallbacks(playClip);
        clearWeatherVideo();
        trailer.release();
        posterPkg = null;
        list = Collections.emptyList();
        notifyGone();
        detachPoster();
        base.setImageDrawable(null);
        recycleDefault();
    }

    private void notifyShown() {
        if (listener != null && posterPkg != null) listener.onPosterShown(posterPkg, list, posterIndex);
    }

    private void notifyGone() {
        if (listener != null) listener.onPosterGone();
    }

    private void recycleDefault() {
        Bitmap b = defaultBmp;
        defaultBmp = null;
        if (b != null) b.recycle();
    }

    private void recyclePoster() {
        Bitmap b = posterBmp;
        posterBmp = null;
        if (b != null) b.recycle();
    }

    // ---- decode ----------------------------------------------------------------------------------

    private interface BitmapCallback {
        void onBitmap(Bitmap bmp);
    }

    private void decode(File file, boolean cropTop, BitmapCallback cb) {
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), o);
                int w = o.outWidth, h = o.outHeight;
                if (w > 0 && h > 0) {
                    int sample = WallpaperSize.sampleSize(w, h, dm.widthPixels, dm.heightPixels);
                    if (WallpaperSize.accepted(w / sample, h / sample)) {
                        o.inJustDecodeBounds = false;
                        o.inSampleSize = sample;
                        int[] scale = WallpaperSize.coverScale(w / sample, h / sample,
                                dm.widthPixels, dm.heightPixels);
                        if (scale != null) {
                            o.inScaled = true;
                            o.inDensity = scale[0];
                            o.inTargetDensity = scale[1];
                        }
                        o.inPreferredConfig = Bitmap.Config.RGB_565;
                        bmp = BitmapFactory.decodeFile(file.getPath(), o);
                    }
                }
                if (bmp != null && cropTop && bmp.getHeight() > 200) {
                    // The sponsor strip runs from about 12% to 26% down the 750x420 still.
                    int cut = Math.round(bmp.getHeight() * 0.27f);
                    Bitmap trimmed = Bitmap.createBitmap(bmp, 0, cut, bmp.getWidth(), bmp.getHeight() - cut);
                    if (trimmed != bmp) bmp.recycle();
                    bmp = trimmed;
                }
            } catch (Exception | OutOfMemoryError ignored) {
                bmp = null;
            }
            final Bitmap result = bmp;
            main.post(() -> cb.onBitmap(result));
        }, "wallpaper-decode").start();
    }

    // ---- scrims ----------------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitWeather();
        topScrim = new LinearGradient(0, 0, 0, h * 0.32f,
                0xC2000000, 0x00000000, Shader.TileMode.CLAMP);
        bottomScrim = new LinearGradient(0, h * 0.66f, 0, h,
                0x00000000, 0xA6000000, Shader.TileMode.CLAMP);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (topScrim != null) {
            scrim.setShader(topScrim);
            canvas.drawRect(0, 0, getWidth(), getHeight() * 0.32f, scrim);
        }
        if (bottomScrim != null) {
            scrim.setShader(bottomScrim);
            canvas.drawRect(0, getHeight() * 0.66f, getWidth(), getHeight(), scrim);
        }
    }
}
