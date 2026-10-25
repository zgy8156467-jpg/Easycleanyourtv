package com.gathin.tvhome;

import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The clip that plays over a poster three seconds after it appears.
 *
 * <p>One TextureView, one MediaPlayer, reused for every poster. A TextureView rather than a
 * VideoView on purpose: this TV's media stack renders SurfaceView video into a separate hardware
 * plane that the compositor can drop, so the clip ran but never reached the panel. A TextureView
 * is a normal view whose frames are drawn into the desk's own window.
 *
 * <p>Every MediaPlayer call runs on its own thread. On this firmware {@code reset()} can block in
 * the media server for as long as it likes; calling it from {@code onPause} froze the main thread
 * and got the whole desk ANR-killed — which is how a reboot ended up back on PatchWall. The main
 * thread now only touches views and schedules, and the player queue is the only writer to the
 * player.
 *
 * <p>A generation counter drops the results of a resolve that the user has already navigated away
 * from. The clip plays at 20% volume; it is capped at {@link #MAX_PLAY_MS} so the desk keeps
 * moving even if {@code onCompletion} never fires.
 */
final class PosterTrailer {

    interface Listener {
        /** The first frame is up and the clip is running. */
        void onStarted();

        /** The clip ended, failed, or timed out. */
        void onFinished();
    }

    private static final String TAG = "TvHome.Trailers";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final long PREPARE_TIMEOUT_MS = 25_000;
    private static final int MAX_PLAY_MS = 15_000;
    // Only the first fifteen seconds are ever shown, and Bilibili's mp4s put moov at the front, so a
    // head slice plays fine. Full episodes are hundreds of MB; never pull more than this.
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int KEEP_FILES = 8;

    private final HotPosters store;
    private final TextureView video;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler playerHandler;
    private final Listener listener;
    /** Serialises downloads so a prefetch and a play never write the same .part at once. */
    private final Object fetchLock = new Object();

    // Touched by the player thread only.
    private MediaPlayer player;
    private Surface surface;

    // Main thread writes, player thread reads.
    private volatile int gen;
    private volatile boolean active;

    private HotPosters.Poster poster;
    private Runnable prepareTimeout;
    /**
     * Clips this player already failed on. TV media stacks choke on some Bilibili slices (malformed
     * slice, media server death); without this the same clip is retried every time its poster is
     * focused and the console fills with the same error.
     */
    private final Set<String> failedClips = Collections.synchronizedSet(new HashSet<String>());
    private volatile String playingKey;
    private volatile File playingFile;
    /** Set once the activity is going away; no handler posts are made after this. */
    private volatile boolean disposed;
    /** The poster whose clip has been (or is being) fetched ahead of the three-second mark. */
    private String prefetched;

    private final Runnable cap = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "cap " + (poster == null ? "" : poster.title));
            finish();
        }
    };

    PosterTrailer(ViewGroup parent, HotPosters store, Listener listener) {
        this.store = store;
        this.listener = listener;
        HandlerThread thread = new HandlerThread("trailer-player");
        thread.start();
        playerHandler = new Handler(thread.getLooper());
        video = new SafeTextureView(parent.getContext());
        // No background on a TextureView: setBackgroundColor throws UnsupportedOperationException
        // on every Android version ("TextureView doesn't support displaying a background drawable"),
        // which took down MainActivity while activity_main was being inflated. The poster behind it
        // is opaque, so nothing is lost.
        video.setAlpha(0f);   // laid out and ready from the start; alpha keeps the surface alive
        video.setFocusable(false);
        video.setFocusableInTouchMode(false);
        video.setClickable(false);
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            video.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                stop();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        parent.addView(video, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Resolve and download this poster's clip now, while the poster is still being looked at, so the
     * three-second mark is not spent waiting on the network. Runs off the main thread, never shows
     * anything, and ignores a poster it has already started on.
     */
    void prefetch(String source, HotPosters.Poster poster) {
        if (poster.title.isEmpty() && (poster.bvid == null || poster.bvid.isEmpty())) return;
        final String key = poster.bvid + "|" + poster.cid + "|" + poster.title;
        if (key.equals(prefetched)) return;
        prefetched = key;
        new Thread(() -> {
            BiliApi.Clip clip = BiliApi.resolve(source, poster);
            if (clip == null || failedClips.contains(keyOf(clip))) return;
            synchronized (fetchLock) {
                fetch(clip);
            }
        }, "trailer-prefetch").start();
    }

    /** Start (or restart) the clip for this poster. Safe to call on the main thread only. */
    void play(String source, HotPosters.Poster poster) {
        if (disposed) return;
        stop();
        playingKey = null;   // the previous clip is done; a timeout below must not blame it
        playingFile = null;
        this.poster = poster;
        final int g = gen;
        prepareTimeout = () -> {
            if (g != gen) return;
            Log.w(TAG, "prepare timeout " + poster.title);
            clipFailed(true);
            finish();
        };
        main.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
        final long startedAt = System.currentTimeMillis();
        new Thread(() -> {
            final BiliApi.Clip clip = BiliApi.resolve(source, poster);
            if (clip == null || clip.urls.isEmpty()) {
                main.post(() -> {
                    if (g != gen) return;
                    Log.i(TAG, "no clip for " + poster.title);
                    finish();
                });
                return;
            }
            if (poster.bvid == null || poster.bvid.isEmpty()) {
                // Persist off the main thread; a failure only costs another search tomorrow.
                store.saveResolvedTrailer(source, poster.index, clip.bvid, clip.cid);
            }
            if (failedClips.contains(keyOf(clip))) {
                main.post(() -> {
                    if (g != gen) return;
                    finish();
                });
                return;
            }
            // The TV's media stack forwards the URL to VLC, which drops our Referer and gets a 403
            // from Bilibili's CDN. Fetching the mp4 here, with the header, is the only way in.
            final File file;
            synchronized (fetchLock) {   // a prefetch may still be writing this file
                file = fetch(clip);
            }
            if (file != null) {
                Log.i(TAG, "ready " + poster.title + " in "
                        + (System.currentTimeMillis() - startedAt) + "ms");
            }
            final String key = keyOf(clip);
            main.post(() -> {
                if (g != gen) return;
                if (file == null) {
                    Log.i(TAG, "no clip for " + poster.title);
                    finish();
                    return;
                }
                startPlayback(file, key, g);
            });
        }, "trailer-resolve").start();
    }

    private static String keyOf(BiliApi.Clip clip) {
        return clip.bvid + "-" + clip.cid;
    }

    /**
     * Forget the clip that just failed: skip it for the rest of this run, and drop the cached slice
     * when the file itself is the suspect. A dead media server (100) is the TV, not the file, so
     * that download is kept.
     */
    private void clipFailed(boolean dropFile) {
        String key = playingKey;
        if (key != null) failedClips.add(key);
        File file = playingFile;
        if (dropFile && file != null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        playingKey = null;
        playingFile = null;
    }

    /** Main thread: take the surface, then hand the file to the player thread. */
    private void startPlayback(File file, String key, int g) {
        SurfaceTexture st = video.getSurfaceTexture();
        if (st == null) {
            Log.w(TAG, "no surface");
            finish();
            return;
        }
        final Surface s = new Surface(st);
        active = true;
        playingKey = key;
        playingFile = file;
        playerHandler.post(() -> {
            if (g != gen || !active) {
                s.release();
                return;
            }
            try {
                if (player == null) {
                    player = new MediaPlayer();
                    player.setOnPreparedListener(this::onPrepared);
                    player.setOnCompletionListener(mp -> main.post(this::finish));
                    player.setOnErrorListener((mp, what, extra) -> {
                        Log.w(TAG, "player error " + what + "/" + extra);
                        Errors.record("trailer", "player " + what + "/" + extra);
                        clipFailed(what != MediaPlayer.MEDIA_ERROR_SERVER_DIED);
                        main.post(this::finish);
                        return true;
                    });
                } else {
                    player.reset();
                }
                if (surface != null) surface.release();
                surface = s;
                player.setSurface(surface);
                player.setDataSource(file.getPath());
                player.prepareAsync();
            } catch (Exception e) {
                Log.w(TAG, "play: " + e);
                Errors.record("trailer", "play " + e.getClass().getSimpleName() + ": " + e.getMessage());
                clipFailed(true);
                main.post(this::finish);
            }
        });
    }

    /** Download the clip to cache; a file that is already there is reused until it is evicted. */
    private File fetch(BiliApi.Clip clip) {
        File dir = new File(video.getContext().getCacheDir(), "trailers");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        // The slice size is part of the name so a cached clip from an older, smaller cap is not
        // reused as if it were complete.
        File out = new File(dir, clip.bvid + "-" + clip.cid + "-" + (MAX_BYTES >> 20) + "m.mp4");
        if (out.isFile() && out.length() > 0) {
            repairTruncatedMp4(out);   // slices cached before the repair shipped
            return out;
        }
        for (String url : clip.urls) {
            File part = new File(dir, out.getName() + ".part");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                Tls12.apply(c);
                c.setConnectTimeout(6_000);
                c.setReadTimeout(10_000);
                c.setRequestProperty("Referer", REFERER);
                c.setRequestProperty("User-Agent", HotPosters.UA);
                c.setRequestProperty("Range", "bytes=0-" + (MAX_BYTES - 1));
                int code = c.getResponseCode();
                if (code != 200 && code != 206) {
                    Log.w(TAG, "fetch " + code + " " + c.getURL().getHost());
                    Errors.record("trailer", "fetch " + c.getURL().getHost() + " HTTP " + code);
                    c.disconnect();
                    continue;
                }
                long total = 0;
                try (InputStream in = c.getInputStream(); FileOutputStream os = new FileOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_BYTES) break;
                        os.write(buf, 0, n);
                    }
                }
                c.disconnect();
                if (total > 0) {
                    repairTruncatedMp4(part);
                    if (part.renameTo(out)) {
                        trim(dir);
                        Log.i(TAG, "fetched " + out.getName() + " " + total / 1024 + " KB");
                        return out;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "fetch " + e.getMessage());
            }
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
        return null;
    }

    /**
     * A head slice ends in the middle of the mdat box, whose header still claims the full episode.
     * Some TV media stacks reject that as malformed (the MiTV4 reports -10005 right after the first
     * frame); shorten the header so the file ends where its last box says it does.
     */
    private static void repairTruncatedMp4(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long len = raf.length();
            long pos = 0;
            while (pos + 8 <= len) {
                raf.seek(pos);
                long size = raf.readInt() & 0xffffffffL;
                byte[] type = new byte[4];
                raf.readFully(type);
                long header = 8;
                if (size == 1) {
                    size = raf.readLong();
                    header = 16;
                } else if (size == 0) {
                    return;   // already runs to EOF
                }
                if (type[0] == 'm' && type[1] == 'd' && type[2] == 'a' && type[3] == 't') {
                    if (size > len - pos) {
                        long actual = len - pos;
                        raf.seek(pos + (header == 16 ? 8 : 0));
                        if (header == 16) raf.writeLong(actual);
                        else raf.writeInt((int) actual);
                        Log.i(TAG, "trimmed mdat " + size + " -> " + actual);
                    }
                    return;
                }
                if (size < header) return;
                pos += size;
            }
        } catch (Exception e) {
            Log.w(TAG, "repair " + e.getMessage());
        }
    }

    private static void trim(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files == null || files.length <= KEEP_FILES) return;
        java.util.Arrays.sort(files, (a, b) -> {
            long ma = a.lastModified(), mb = b.lastModified();
            return ma < mb ? -1 : (ma == mb ? 0 : 1);
        });
        for (int i = 0; i < files.length - KEEP_FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    /** Player thread. Callbacks arrive on the thread that created the player, which is this one. */
    private void onPrepared(MediaPlayer mp) {
        if (!active) return;
        if (prepareTimeout != null) main.removeCallbacks(prepareTimeout);
        try {
            // A background trailer, not foreground viewing: quiet, and without touching the TV's
            // own volume setting.
            mp.setVolume(0.2f, 0.2f);
        } catch (Exception ignored) {
        }
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            try {
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            } catch (Exception ignored) {
            }
        }
        try {
            mp.start();
        } catch (Exception e) {
            Log.w(TAG, "start: " + e);
            main.post(this::finish);
            return;
        }
        main.post(() -> {
            if (!active) return;
            video.setAlpha(1f);
            Log.i(TAG, "playing " + (poster == null ? "" : poster.title));
            if (listener != null) listener.onStarted();
            main.removeCallbacks(cap);
            main.postDelayed(cap, MAX_PLAY_MS);
        });
    }

    /** Hide the clip immediately. Pending resolves are invalidated and no callback fires. */
    void stop() {
        if (disposed) return;
        gen++;
        if (prepareTimeout != null) main.removeCallbacks(prepareTimeout);
        main.removeCallbacks(cap);
        stopPlayer();
        video.setAlpha(0f);
    }

    /** Queue a reset; never blocks the caller, which may well be the main thread. */
    private void stopPlayer() {
        active = false;
        if (disposed) return;
        playerHandler.post(() -> {
            if (player != null) {
                try {
                    player.reset();
                } catch (Exception ignored) {
                }
            }
            if (surface != null) {
                surface.release();
                surface = null;
            }
        });
    }

    private void finish() {
        gen++;
        if (prepareTimeout != null) main.removeCallbacks(prepareTimeout);
        main.removeCallbacks(cap);
        stopPlayer();
        video.setAlpha(0f);
        if (listener != null) listener.onFinished();
    }

    /**
     * Free the player but keep the thread and the view: called when Home is trimmed in the
     * background and comes back to the same stage. Quitting the looper here used to leave every
     * later {@link #stop()} posting to a dead thread.
     */
    void release() {
        if (disposed) return;
        stop();
        playerHandler.post(() -> {
            if (player != null) {
                try {
                    player.release();
                } catch (Exception ignored) {
                }
                player = null;
            }
        });
    }

    /** Shut down for good, when the activity is destroyed. The trailer cannot play after this. */
    void dispose() {
        if (disposed) return;
        stop();
        playerHandler.post(() -> {
            if (player != null) {
                try {
                    player.release();
                } catch (Exception ignored) {
                }
                player = null;
            }
            if (android.os.Build.VERSION.SDK_INT >= 18) playerHandler.getLooper().quitSafely();
            else playerHandler.getLooper().quit();
        });
        // Set before detaching: releaseSurfaceTexture calls stop(), which must not post again.
        disposed = true;
        ViewParent parent = video.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(video);
    }
}
