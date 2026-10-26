package com.gathin.tvhome;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.content.ContentUris;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener, WallpaperStage.PosterListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    /** config.xml defaults with an optional config.json theme overlay on top. */
    private Config cfg;
    /** The wallpaper file on the TV; null until something asks for it. */
    private Wallpaper wall;
    /** The "Desktop settings" panel, so Back can close it. */
    private PopupWindow wallpaperPanel;
    /** The picture behind the desk: default wallpaper plus the focused app's hot posters. */
    private WallpaperStage stage;
    /** Draws the band behind the dock row; kept so its colour can follow the wallpaper. */
    private static final int REQ_PICK_WALLPAPER = 41;
    private static final int REQ_UNINSTALL = 42;
    private static final int REQ_INSTALL_STORAGE = 43;
    private static final int REQ_WIFI_SCAN = 44;
    private static final int REQ_READ_PHOTOS = 45;
    /** The wallpaper picker waits for the storage grant that the photo grid needs. */
    private boolean photosAfterGrant;
    /** The local picture grid, so Back can close it. */
    private PopupWindow photoPanel;
    /** APK waiting for the storage permission Android 6 needs before the installer can read it. */
    private java.io.File pendingInstall;
    /** The Wi-Fi sheet is waiting for the location grant scanning needs. */
    private boolean wifiPanelAfterGrant;
    /** Weak boxes cannot keep up with the tweens; this turns the desk's motion down to a minimum. */
    private boolean reducedMotion;
    /** User's icon-per-row choice; 0 means "whatever this screen is happy with". */
    private static final String XIAOMI_SETTINGS = "com.xiaomi.mitv.settings";
    private static final String PREFS_LAYOUT = "layout";
    private static final String KEY_COLUMNS = "columns";
    private static final String KEY_REDUCED_MOTION = "reduced_motion";
    /** The range the panel offers, in the order the row cycles through them. */
    private static final int[] COLUMN_CHOICES = {4, 6, 8};
    /**
     * Launch the desk straight into its own settings panel. The quick settings overlay
     * ({@link QuickSettingsActivity}) sends this, so the remote's Settings button can reach the wallpaper
     * and icon-per-row settings without a second settings screen.
     */
    public static final String ACTION_DESKTOP_SETTINGS = "com.gathin.tvhome.action.DESKTOP_SETTINGS";
    /** Same route for the Favourites (dock) panel, so the quick settings overlay can offer it too. */
    public static final String ACTION_DOCK_PICKER = "com.gathin.tvhome.action.DOCK_PICKER";
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    /** Clock, AM/PM and date. Plain TextViews because TextClock needs Android 4.2. */
    private TextView clock, ampm, date;
    /** Weather in the top bar, filled by {@link Weather} from an IP lookup or a picked city. */
    private LinearLayout weatherRow;
    private ImageView weatherIcon;
    private TextView weatherText, weatherStatusView;
    /** The weather city picker, so Back can close it. */
    private PopupWindow cityPicker;
    /** Province/city data from assets/cities.json, parsed once per activity. */
    private JSONArray cityData;
    /** Package whose system uninstaller we are waiting on. */
    private String pendingUninstall;
    /** The lost-accessibility hint is once per process, not once per resume. */
    private boolean a11yPromptedThisRun;
    /** The long-press menu on one app tile (pin/unpin, uninstall). */
    private PopupWindow appMenu;
    /** Show title and one focusable dot per hot poster, just above the app name. */
    private LinearLayout programBar, programDots;
    private TextView programTitle;
    private int dotCount;
    /** The control centre panel, so Back can close it. */
    private PopupWindow controlPanel;
    /** The network / Bluetooth detail sheet opened from it. */
    private PopupWindow detailPanel;
    /** The update prompt; one at a time, so a slow check cannot stack two. */
    private PopupWindow updatePanel;
    /** The unread badge on the control pill and the small notice under it. */
    private View updateDot;
    private View updateBanner;
    /** The background-found update, so the banner can reopen its details without another check. */
    private Remote.Update pendingUpdate;
    /** One banner per version per run; dismiss it and the dot is still there. */
    private int bannerShownFor = -1;
    private final Runnable hideBanner = () -> {
        if (updateBanner != null && !updateBanner.isFocused()) hideUpdateBanner();
    };
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private TextView emptyHint;
    /** The "Favourites" panel, so Back can close it. */
    private PopupWindow dockPicker;
    /** The dock slot-count chooser opened from the control centre. */
    private PopupWindow dockSizePicker;
    /** A folder's panel and the folder it is showing, so edits can reopen the refreshed list. */
    private PopupWindow folderPanel;
    private FolderStore.Folder openFolder;
    /** Folder choice / add-app pickers, so a second tap replaces rather than stacks them. */
    private PopupWindow folderPick;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    /** The dock row moved with a padding change; re-place the glass once the grid has laid out. */
    private boolean reseatPending;
    /** Width of the tile the glass was last fitted to, so a new column count refits it. */
    private int glassCardW;
    /** Scratch rects: onTileFocused runs on every focus change, so it allocates nothing. */
    private final Rect tileRect = new Rect();
    private final Rect revealRect = new Rect();
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** Dock slot focus last sat on; the grid remembers it across a trip into an app. */
    private static int lastDockIndex = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    /** Long-press on a dock tile: left/right reorder, OK removes, Back finishes. */
    private boolean dockEditing;
    private int dockEditIndex = -1;
    private String dockEditKey;
    /** Swallow the centre key that is still down when the long-press opened the edit mode. */
    private boolean dockEditSwallowCenter;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            updateGreeting();
            Weather.refreshIfDue(MainActivity.this, MainActivity.this::showWeather);
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        // Quick settings asking for one of the desk's own panels (the desk is singleTask, so it lands
        // here whenever it is already running).
        openPanelFromQuickSettings(intent.getAction());
        if (intent.getAction() != null) return;
        // Only the Home key (re-issued by HomeKeyService, or the system on remotes that send
        // KEYCODE_HOME) sends a HOME intent to an already-running desktop; treat it as "go to top".
        if (!intent.hasCategory(Intent.CATEGORY_HOME)) return;
        scrollToTop();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DeviceProfile.isPhone(this)) {
            // A phone gets its own screen, not this activity half-built: onResume assumes the desk
            // and everything below has been created. CLEAR_TASK keeps repeated Home presses from
            // stacking notices on top of each other.
            Intent notice = new Intent(this, PhoneNoticeActivity.class);
            notice.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(notice);
            finish();
            return;
        }
        // A crash is a black window on a TV. Show the saved report before building the desk, so the
        // next launch is a picture the user can photograph instead of nothing.
        if (CrashLog.has(this)) {
            startActivity(new Intent(this, CrashActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        // The desk is always on: no system display timeout. The Xiaomi gallery still gets its idle
        // broadcast, so HomeKeyService covers that separately.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // The desk draws its own clock; the vendor's status bar (GITV mark, Wi-Fi, temperature, time)
        // is another layer of chrome on top of it. The window flag is what MiTV's systemui listens to;
        // the SystemUi flags alone left the bar in place.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        live = new WeakReference<>(this);
        density = getResources().getDisplayMetrics().density;
        // config.xml defaults, then a config.json overlay pushed by tools/theme.sh if one is there.
        cfg = Config.load(this);
        reportCapabilities();
        // The user's own choice wins; otherwise fall back to what this screen can carry comfortably.
        int wanted = getSharedPreferences(PREFS_LAYOUT, MODE_PRIVATE).getInt(KEY_COLUMNS, 0);
        COLUMNS = isColumnChoice(wanted) ? wanted : recommendColumns();
        liveTvPkg = cfg.liveTvPackage.trim();

        root = findViewById(R.id.root);
        stage = findViewById(R.id.stage);
        glass = findViewById(R.id.glass);
        // A theme may paint the whole background: a colour, a wallpaper, or nothing beyond both.
        root.setBackgroundColor(cfg.backgroundColor);
        if (cfg.wallpaper != null) stage.setDefault(cfg.wallpaper);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        clock = findViewById(R.id.clock);
        ampm = findViewById(R.id.ampm);
        date = findViewById(R.id.date);
        weatherRow = findViewById(R.id.weatherRow);
        weatherIcon = findViewById(R.id.weatherIcon);
        weatherText = findViewById(R.id.weatherText);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        setBackgroundCompat(ambient, ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        emptyHint = findViewById(R.id.emptyHint);
        programBar = findViewById(R.id.programBar);
        programDots = findViewById(R.id.programDots);
        programTitle = findViewById(R.id.programTitle);
        stage.setPosterListener(this);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            /**
             * Every "bring this tile on screen" scroll lands here, both RecyclerView's own on focus and
             * onTileFocused's. The stock version treats the grid's top padding as the visible top, but that
             * padding only pushes the dock down the screen: grid rows scroll up into it. With the stock rule,
             * Up into a row above the padding dragged that row down to the padding line, cut it off at the
             * bottom of the screen and could scroll the focused tile out entirely, dropping focus into the
             * top bar. Grid tiles now scroll just enough to show the rect between the grid's top edge and its
             * bottom; dock tiles are left to onTileFocused, which parks the dock row on the padding line.
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                if (getPosition(child) == 0) return false;
                int top = child.getTop() + rect.top;
                int bottom = top + rect.height();
                int limitBottom = getHeight() - getPaddingBottom();
                int dy;
                if (top < 0) dy = top;
                else if (bottom > limitBottom) dy = Math.min(top, bottom - limitBottom);
                else dy = 0;
                if (dy == 0) return false;
                if (immediate) parent.scrollBy(0, dy);
                else parent.smoothScrollBy(0, dy);
                return true;
            }
        });
        // Position 0 is the dock, one full-width row, so its slot count can differ from COLUMNS.
        ((GridLayoutManager) grid.getLayoutManager()).setSpanSizeLookup(
                new GridLayoutManager.SpanSizeLookup() {
                    @Override
                    public int getSpanSize(int position) {
                        return position == 0 ? COLUMNS : 1;
                    }
                });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        reducedMotion = getSharedPreferences(PREFS_LAYOUT, MODE_PRIVATE)
                .getBoolean(KEY_REDUCED_MOTION, false);
        AppAdapter.setReducedMotion(reducedMotion);
        adapter = new AppAdapter(this, this, COLUMNS, cfg.dockApps);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            updateDockPadding();
            // The dock's first bind can land before this width exists; re-size it once it does.
            if (adapter != null && adapter.dockWidthStale(r - l)) {
                RecyclerView.ViewHolder dockVh = grid.findViewHolderForAdapterPosition(0);
                if (dockVh != null) {
                    adapter.resizeDockRow(dockVh.itemView);
                    reseatPending = true;
                }
            }
        });
        grid.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (reseatPending && reseatGlass()) reseatPending = false;
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }
        // A little give while it travels: a pure translation across half the grid reads as a cut.
        // Each axis stretches with its own speed, so the slab leans into the direction of travel
        // and settles back as the springs arrive.
        springX.addUpdateListener((anim, value, velocity) ->
                glass.setScaleX(1f + Math.min(0.10f, Math.abs(velocity) / 7000f)));
        springY.addUpdateListener((anim, value, velocity) ->
                glass.setScaleY(1f + Math.min(0.10f, Math.abs(velocity) / 7000f)));
        DynamicAnimation.OnAnimationEndListener settle = (anim, canceled, value, velocity) -> {
            if (!springX.isRunning() && !springY.isRunning()) {
                glass.setScaleX(1f);
                glass.setScaleY(1f);
            }
        };
        springX.addEndListener(settle);
        springY.addEndListener(settle);

        View btnControl = findViewById(R.id.btnControl);
        // An odd vendor ROM can inflate the desk without the pill. Worth knowing about, but it must
        // not take the whole launcher down on every boot.
        if (btnControl == null) Errors.record("ui", "btnControl missing");
        if (btnControl != null) btnControl.setOnClickListener(v -> {
            stopDockEdit();
            showControlCenter();
        });
        // Wi-Fi sits beside the control pill rather than inside its sheet: the sheet is the place
        // for everything else, and switching networks is worth one press less.
        View btnWifi = findViewById(R.id.btnWifi);
        if (btnWifi != null) btnWifi.setOnClickListener(v -> {
            stopDockEdit();
            showNetworkDetails();
        });

        // Keep focus on the top bar and at the top: Android's focus finder would otherwise jump to a
        // tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock slot Up came from, not wherever the focus finder happens to land.
                focusDock(lastDockIndex);
                return true;
            }
            // Left and right walk between the two pills; their next-focus attributes keep the
            // search inside the top bar when there is no pill that way.
            return false;
        };
        // The glyph sits bare on the wallpaper: focus wraps it in an iOS frosted square and
        // brightens it instead of filling it white.
        stylePill(btnWifi, findViewById(R.id.wifiGlass), pillKeys);
        stylePill(btnControl, findViewById(R.id.controlGlass), pillKeys);

        // An update found in the background leaves a dot instead of taking over the desk. The
        // banner is the one-time announcement; both are only an entry point to the same panel.
        updateDot = findViewById(R.id.updateDot);
        updateBanner = findViewById(R.id.updateBanner);
        refreshUpdateBadge();
        if (updateBanner != null) {
            updateBanner.setOnClickListener(v -> {
                hideUpdateBanner();
                openPendingUpdate();
            });
            updateBanner.setOnFocusChangeListener((v, hasFocus) -> {
                handler.removeCallbacks(hideBanner);
                if (!hasFocus) handler.postDelayed(hideBanner, 2500);
            });
        }

        // The glass follows tiles; the dots are a second place focus is allowed to sit without the
        // poster disappearing. Everywhere else the poster and its title go away.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (newF != null && isInProgramBar(newF)) {
                hideGlass();
                stage.setBrowsing(true);
                return;
            }
            stage.setBrowsing(false);
            hideGlass();
            if (onTile) return;
            if (newF != null) {
                stage.clearPoster();
                hideProgram();
            } else {
                // A transient null focus (window switch): keep the picture, just silence the clip.
                stage.stopVideo();
            }
        });

        // If the user left the Bing wallpaper on, make sure today's picture is there before they see
        // the desk. Runs in the background and re-applies only when something actually changed.
        wall = new Wallpaper(this);
        if (wall.autoDaily() && !wall.hasWallpaper()) {
            new Thread(() -> {
                final boolean done = wall.refreshBingIfDue();
                if (done) handler.post(this::applyWallpaper);
            }).start();
        }

        // Started by the quick settings overlay when the desk was not running yet (the usual case,
        // since the panel also floats over apps, not just over the desktop).
        openPanelFromQuickSettings(getIntent().getAction());

        // Cached weather shows at once; the network call only runs when the cache is over half an
        // hour old and never blocks the first frame.
        refreshWeather(false);

        Remote.startHeartbeat(this);

        // The update check is deliberately late: the desk should settle and take focus first, so the
        // prompt does not fight the tile animation. It fails silently when the TV has no network.
        handler.postDelayed(() -> checkUpdate(0), 5000);
    }

    /** Opens one of the desk's own panels, whichever route asked for it (quick settings or a cold start). */
    private void openPanelFromQuickSettings(String action) {
        if (ACTION_DESKTOP_SETTINGS.equals(action)) grid.post(this::showWallpaperPicker);
        else if (ACTION_DOCK_PICKER.equals(action)) grid.post(this::showDockPicker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awayFromDesk) {
            awayFromDesk = false;
            grid.post(this::scrollToTop);
        }
        stage.setPaused(false);
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        programBar.setAlpha(0f);
        programDots.setAlpha(0f);
        ambient.setAlpha(0f);
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused == 0 ? lastDockIndex : lastFocused;
        // The adapter was let go of when memory ran short: put it back before loading the tiles again.
        if (grid.getAdapter() == null) grid.setAdapter(adapter);
        if (wallpaperReleased) {
            wallpaperReleased = false;
            applyWallpaper();
        }
        adapter.reload();
        updateEmptyHint();
        grid.scheduleLayoutAnimation();
        updateClock();
        updateGreeting();
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(() -> {
            int pos = returnKey == null ? -1 : adapter.positionOf(returnKey);
            if (pos >= 0) focusTile(pos);
            else focusDock(returnSlot);
        });

        // Runs on every resume as well: coming back from the accessibility page clears the nag.
        checkAccessibility();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
        // Never leave a clip running — or a rotation downloading — behind another app.
        if (stage != null) stage.setPaused(true);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (stage == null) return;
        if (!hasFocus) {
            stage.stopVideo();
        } else if (grid != null && grid.findFocus() != null
                && grid.findFocus().getId() == R.id.card) {
            stage.resumeVideo();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
    }

    @Override
    protected void onStop() {
        // Another app took the screen. A translucent overlay (the panels, the quick settings)
        // only pauses us, so this really does mean the desk was left behind.
        awayFromDesk = true;
        started = false;
        super.onStop();
    }

    /**
     * The system wants memory back. The banners behind the tiles are the largest thing this launcher
     * holds, so when Home is in the background and the box is short on memory, they go: the tiles, the
     * adapter itself and the wallpaper bitmap are let go, and onResume puts them back.
     *
     * <p>Not while the desk is on screen. RUNNING_LOW and CRITICAL also arrive then, because an
     * accessibility service keeps the process in the foreground, and blanking the screen the owner is
     * looking at would be worse than the memory.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        boolean due = level >= TRIM_MEMORY_BACKGROUND
                || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL;
        if (!due || started || adapter == null) return;
        stage.stopVideo();
        adapter.clear();
        // Detach the adapter as well: with Home stopped no layout pass runs, so the tile views (and the
        // bitmaps they hold) would stay attached until Home comes back.
        grid.setAdapter(null);
        grid.getRecycledViewPool().clear();
        if (stage.hasImage()) {
            stage.release();
            wallpaperReleased = true;
        }
    }

    @Override
    protected void onDestroy() {
        live = null;
        if (stage != null) stage.dispose();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (appMenu != null && appMenu.isShowing()) {
            appMenu.dismiss();
            return;
        }
        if (folderPanel != null && folderPanel.isShowing()) {
            folderPanel.dismiss();
            return;
        }
        if (controlPanel != null && controlPanel.isShowing()) {
            controlPanel.dismiss();
            return;
        }
        if (dockPicker != null && dockPicker.isShowing()) {
            dockPicker.dismiss();
            return;
        }
        if (photoPanel != null && photoPanel.isShowing()) {
            photoPanel.dismiss();
        }
        if (cityPicker != null && cityPicker.isShowing()) {
            cityPicker.dismiss();
            return;
        }
        if (isInProgramBar(getCurrentFocus())) {
            focusDock(lastDockIndex);
            return;
        }
        // Home screen: Back goes nowhere.
    }

    /** Whether the Menu button should open quick settings from other apps too (see HomeKeyService). */
    boolean menuKeyEverywhere() {
        return getSharedPreferences(HomeKeyService.PREFS, MODE_PRIVATE)
                .getBoolean(HomeKeyService.KEY_MENU_EVERYWHERE, false);
    }

    /** Whether the tweens are turned down for a box that cannot keep up with them. */
    boolean reducedMotion() {
        return getSharedPreferences(PREFS_LAYOUT, MODE_PRIVATE)
                .getBoolean(KEY_REDUCED_MOTION, false);
    }

    /** True when config.xml names the TV's own source panel. */
    private boolean panelConfigured() {
        String service = cfg.sourcePanelService == null ? "" : cfg.sourcePanelService.trim();
        String action = cfg.sourcePanelAction == null ? "" : cfg.sourcePanelAction.trim();
        return !service.isEmpty() || !action.isEmpty();
    }

    /**
     * Open the TV's own source panel. The panel is sometimes an activity (a dialog on some sets) and
     * sometimes a service, so try it as the activity it resolves to first and fall back to a service.
     */
    private void openSourcePanel() {
        Intent panel = sourcePanelIntent();
        if (panel == null) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }
        // The vendor's picker is a PatchWall window: keep the desk from covering it, or the two
        // fight for the screen and the remote stops doing anything.
        HomeKeyService.suppressTakeover(20000);
        try {
            if (panel.getComponent() != null && getPackageManager().resolveService(panel, 0) != null) {
                startService(panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                open(panel);
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * The TV's own source-selection panel, for sets that do not report their inputs to the launcher.
     *
     * <p>Tried in order: the component named in config.xml (per vendor), then the action named there,
     * then AOSP's own input-source page — the platform has no constant for it, but TV ROMs answer this
     * action string. There is deliberately no "plain system settings" last resort: on a box with no
     * source panel at all (a Google TV stick, say) that just opens the settings the user was already
     *looking at, which reads like a bug. Null means we have nothing better to offer, and the caller
     * says so instead.
     */
    private Intent sourcePanelIntent() {
        String service = cfg.sourcePanelService == null ? "" : cfg.sourcePanelService.trim();
        String action = cfg.sourcePanelAction == null ? "" : cfg.sourcePanelAction.trim();
        if (!service.isEmpty()) {
            ComponentName cn = ComponentName.unflattenFromString(service);
            if (cn != null && installed(this, cn.getPackageName())) {
                Intent byComponent = new Intent().setComponent(cn);
                if (byComponent.resolveActivity(getPackageManager()) != null) return byComponent;
            }
        }
        if (!action.isEmpty()) {
            Intent byAction = new Intent(action);
            if (byAction.resolveActivity(getPackageManager()) != null) return byAction;
        }
        Intent tvInputs = new Intent("android.settings.INPUT_SOURCE_SETTINGS");
        return tvInputs.resolveActivity(getPackageManager()) != null ? tvInputs : null;
    }

    /**
     * True while another app is in front. Coming back then means the user asked for the desk, and the
     * desk opens at the top — the same thing the Home key does everywhere else. Inside the desk (a panel
     * closing, the volume dialog, the quick settings overlay) onStop does not fire, so nothing moves.
     */
    private boolean awayFromDesk;
    /** True between onStart and onStop: the desk is (at least partly) on screen. */
    private boolean started;
    /** Set when the wallpaper bitmap was handed back to the system; it comes back on resume. */
    private boolean wallpaperReleased;
    /** HomeKeyService cannot reach an activity directly; this is the live one, weakly held. */
    private static WeakReference<MainActivity> live;

    /** Called by {@link HomeKeyService} when Home is pressed while the desk is already in front. */
    static void onHomeKeyFromService() {
        MainActivity activity = live == null ? null : live.get();
        if (activity != null) activity.runOnUiThread(activity::scrollToTop);
    }

    /** Back to the first tile, the way the Home button behaves on a phone launcher. */
    private void scrollToTop() {
        if (adapter == null) return;
        lastFocused = 0;
        lastDockIndex = 0;
        lastFocusedKey = dockKey(0);
        // Take the glass off first. The tile it is glued to is about to be moved to another row, and
        // measuring it in between left the highlight sitting in the top-left corner of the screen.
        hideGlass();
        grid.scrollToPosition(0);
        // Focus after the layout pass that moves the rows. Straight after scrollToPosition the holder
        // found for position 0 is still the old one, and asking a view that is about to be recycled for
        // focus is how the highlight ended up nowhere at all.
        grid.post(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
            View card = dockCardAt(vh, 0);
            if (card == null) {
                focusDock(0);           // not laid out yet: focusDock waits for the scroll to settle
                return;
            }
            onDockFocused(0, adapter.dockEntryAt(0), card);   // already focused: no event fires
            card.requestFocus();
        });
    }

    /** Stable key of the app in a dock slot, or null for an empty slot. */
    private String dockKey(int index) {
        AppAdapter.Entry e = adapter == null ? null : adapter.dockEntryAt(index);
        return e == null ? null : e.component.flattenToShortString();
    }

    private View dockCardAt(RecyclerView.ViewHolder dockRow, int index) {
        if (dockRow == null) return null;
        View row = dockRow.itemView.findViewById(R.id.dockRow);
        if (!(row instanceof ViewGroup)) return null;
        ViewGroup slots = (ViewGroup) row;
        if (index < 0 || index >= slots.getChildCount()) return null;
        return slots.getChildAt(index).findViewById(R.id.card);
    }

    /** Put focus back on a dock slot, laying the row out first when it is not on screen. */
    private void focusDock(int index) {
        if (adapter == null) return;
        final int at = Math.max(0, Math.min(index, adapter.dockSize() - 1));
        View card = dockCardAt(grid.findViewHolderForAdapterPosition(0), at);
        if (card != null) {
            if (card.isFocused()) onDockFocused(at, adapter.dockEntryAt(at), card);
            else card.requestFocus();
            return;
        }
        grid.scrollToPosition(0);
        waitForDock(at, 0);
    }

    private void waitForDock(final int index, final int attempt) {
        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        View card = dockCardAt(grid.findViewHolderForAdapterPosition(0), index);
        if (card != null) {
            if (card.isFocused()) onDockFocused(index, adapter.dockEntryAt(index), card);
            else card.requestFocus();
            return;
        }
        if (attempt < 40) {
            grid.postDelayed(() -> waitForDock(index, attempt + 1), 50);
        }
    }

    /**
     * The remote's Menu (or Settings) button opens the quick settings panel, the way TV boxes with a
     * built-in launcher do it: one press for the handful of places you actually go, including this
     * launcher's own two panels. Dispatched here rather than through a key listener so it works from
     * anywhere on the desk, including when nothing is focused.
     *
     * <p>This used to open the dock editor on its own. The panel carries a Favourites row for it, so
     * nothing is lost, and the Favourites pill on the top bar still opens it in one press. A remote
     * that sends a real KEYCODE_SETTINGS or KEYCODE_HELP gets the same panel, and HomeKeyService
     * opens it from other apps; swallowing it globally would break it inside other apps.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        // A small ring buffer the console can pull when someone reports a dead button. Focus is
        // recorded as the view id so a log line can be read without the screenshot.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            View focused = getCurrentFocus();
            String focus = "";
            if (focused != null && focused.getId() != View.NO_ID) {
                try {
                    focus = getResources().getResourceEntryName(focused.getId());
                } catch (Exception ignored) {
                }
            }
            KeyLog.record(code, event.getRepeatCount(), focus);
        }
        // Rearranging the dock: left/right swaps with the neighbour, OK removes, Back finishes.
        if (dockEditing) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    dockEditSwallowCenter = false;
                    return true;
                }
                // The key that started this mode is still down: its repeats and the release that
                // follows must not read as "OK, take it off the dock" — that removed the app the
                // instant the long-press landed on a set whose remote repeats the centre key.
                if (dockEditSwallowCenter || event.getRepeatCount() > 0) return true;
                removeDockEntry();
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                moveDock(-1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                moveDock(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK) {
                stopDockEdit();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                stopDockEdit();   // focus is leaving the dock row; the arrange mode is over
            }
        }
        // Home while the desk is already in front means "take me back to the top", not "relaunch me".
        // Handling it here rather than in onNewIntent because plenty of paths (a remote that sends a
        // plain KEYCODE_HOME, a shell-injected key) never re-deliver the HOME intent at all.
        if (code == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) scrollToTop();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU
                || code == KeyEvent.KEYCODE_SETTINGS
                || code == KeyEvent.KEYCODE_HELP) {
            // Both actions are consumed: an unhandled remote key gets the system's "this app does not
            // support that button" toast on some boxes.
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                startActivity(new Intent(this, QuickSettingsActivity.class));
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (code == KeyEvent.KEYCODE_APP_SWITCH
                || code == KeyEvent.KEYCODE_GUIDE)) {
            if (dockPicker != null && dockPicker.isShowing()) {
                dockPicker.dismiss();
            } else {
                showDockPicker();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- editing the dock -----------------------------------------------------------------------

    /**
     * Long-press a tile. A dock tile opens the same menu as any other, with "rearrange" added, so
     * uninstalling from the dock lives in the place the user already knows.
     */
    @Override
    public void onTileLongPressed(AppAdapter.Entry entry, boolean alreadyPinned) {
        showAppMenu(entry, alreadyPinned, adapter.dockIndexOf(entry.pkg));
    }

    /** Left/right rearrange mode for one dock slot; OK takes the app off, Back finishes. */
    private void startDockEdit(int index, AppAdapter.Entry entry) {
        dockEditing = true;
        dockEditIndex = index;
        dockEditKey = entry.component.flattenToShortString();
        dockEditSwallowCenter = true;
        toast(getString(R.string.dock_edit_hint));
    }

    /** Move the dock app being edited one slot left or right, and keep focus on it. */
    private void moveDock(int direction) {
        List<String> apps = new ArrayList<>(adapter.dock().apps());
        int to = dockEditIndex + direction;
        if (dockEditIndex < 0 || to < 0 || to >= apps.size() || to >= adapter.dockSize()) return;
        Collections.swap(apps, dockEditIndex, to);
        adapter.dock().set(apps);
        dockEditIndex = to;
        lastFocusedKey = dockEditKey;
        refreshDock();
    }

    /** OK while rearranging: take the app off the dock and let it return to the grid. */
    private void removeDockEntry() {
        List<String> apps = new ArrayList<>(adapter.dock().apps());
        if (dockEditIndex < 0 || dockEditIndex >= apps.size()) {
            stopDockEdit();
            return;
        }
        AppAdapter.Entry entry = adapter.dockEntryAt(dockEditIndex);
        String label = entry == null ? "" : entry.label;
        String key = dockEditKey;
        apps.remove(dockEditIndex);
        adapter.dock().set(apps);
        lastFocusedKey = key;
        lastDockIndex = Math.max(0, Math.min(dockEditIndex, adapter.dockSize() - 1));
        stopDockEdit();
        refreshDock();
        toast(getString(R.string.dock_removed, label));
    }

    /**
     * Park the dock on the bottom edge. The row height follows the slot count, so this runs again
     * whenever that count changes, not only when the grid's own bounds do.
     */
    private void updateDockPadding() {
        if (grid == null || adapter == null) return;
        int h = grid.getHeight();
        int screen = grid.getWidth();
        if (h <= 0 || screen <= 0) return;
        // Measured beats calculated: the row's real height includes the tile's own 8dp padding, the
        // band and the item's padding, and those change with the slot count. The first pass has no
        // holder yet, so fall back to the estimate; the layout it triggers brings one and corrects it.
        RecyclerView.ViewHolder dockVh = grid.findViewHolderForAdapterPosition(0);
        int dockRow = dockVh != null && dockVh.itemView.getHeight() > 0
                ? dockVh.itemView.getHeight()
                : AppAdapter.dockRowHeightPx(screen, COLUMNS, adapter.dockSize(), density);
        int want = Math.max(0, h - dockRow);
        if (grid.getPaddingTop() != want) {
            grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            // The padding moves the dock row without scrolling, so the scroll listener never sees
            // it; on a cold start that leaves the glass above the tile until the next key press.
            // The rows only reach their new place on the next layout pass, so reseat from there.
            reseatPending = true;
        }
    }

    /** Sets the dock's slot count: 1..8, shrinking sends the overflow back to the grid. */
    private void applyDockSize(int next) {
        if (adapter == null) return;
        next = Math.max(DockStore.MIN_SIZE, Math.min(DockStore.MAX_SIZE, next));
        if (next == adapter.dockSize()) return;
        int removed = adapter.dock().setCapacity(next);
        adapter.reload();
        updateEmptyHint();
        updateDockPadding();
        grid.scheduleLayoutAnimation();
        if (removed > 0) toast(getString(R.string.dock_size_trimmed, removed));
    }

    private void stopDockEdit() {
        dockEditing = false;
        dockEditIndex = -1;
        dockEditKey = null;
        dockEditSwallowCenter = false;
    }

    private void showAppMenu(final AppAdapter.Entry entry, final boolean pinned, final int dockIndex) {
        if (isFinishing()) return;
        if (appMenu != null && appMenu.isShowing()) {
            appMenu.dismiss();
            return;
        }
        View panel = getLayoutInflater().inflate(R.layout.popup_app, root, false);
        ((TextView) panel.findViewById(R.id.appTitle)).setText(entry.label);
        ((TextView) panel.findViewById(R.id.pinTitle))
                .setText(pinned ? R.string.app_menu_unpin : R.string.app_menu_pin);
        View rowPin = panel.findViewById(R.id.rowPin);
        View rowMove = panel.findViewById(R.id.rowMove);
        View rowUninstall = panel.findViewById(R.id.rowUninstall);
        TextView moveTitle = panel.findViewById(R.id.moveTitle);
        ImageView moveIcon = panel.findViewById(R.id.moveIcon);
        final boolean rearrange = pinned && dockIndex >= 0;
        rowMove.setVisibility(rearrange || !pinned ? View.VISIBLE : View.GONE);
        if (rearrange) {
            moveTitle.setText(R.string.app_menu_move);
            moveIcon.setImageResource(R.drawable.ic_move);
        } else if (!pinned) {
            // Grid apps can start a folder or join one. Folders themselves are browsed by opening
            // them; this is the only way in from an app tile.
            moveTitle.setText(adapter.folders().all().isEmpty()
                    ? R.string.folder_new : R.string.folder_move_in);
            moveIcon.setImageResource(R.drawable.ic_plus);
        }
        // A preinstalled app cannot be uninstalled (the system only offers "disable"), and asking the
        // uninstaller to try is how Xiaomi boxes answer with a confirmation that then does nothing.
        // The row stays useful: it opens App info, where the remote can disable the app instead.
        if (entry.system) {
            ((TextView) panel.findViewById(R.id.uninstallTitle))
                    .setText(R.string.app_menu_info);
            ((TextView) panel.findViewById(R.id.uninstallTitle)).setTextColor(
                    getResources().getColorStateList(R.drawable.pill_text));
            ImageView icon = panel.findViewById(R.id.uninstallIcon);
            icon.setImageResource(R.drawable.ic_settings);
            if (Build.VERSION.SDK_INT >= 21) {
                icon.setImageTintList(getResources().getColorStateList(R.drawable.pill_text));
            }
        }

        final PopupWindow popup = new PopupWindow(panel,
                Math.min(dp(420), (int) (getResources().getDisplayMetrics().widthPixels * 0.7f)),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(() -> appMenu = null);
        appMenu = popup;

        rowPin.setOnClickListener(v -> {
            popup.dismiss();
            togglePin(entry, pinned);
        });
        rowMove.setOnClickListener(v -> {
            popup.dismiss();
            if (rearrange) {
                startDockEdit(dockIndex, entry);
                focusDock(dockIndex);
            } else if (adapter.folders().all().isEmpty()) {
                // Nothing to choose from: the row already says "New folder", so asking again is
                // just one more OK press on a remote.
                putInFolder(entry, null);
            } else {
                showFolderChoice(entry);
            }
        });
        rowUninstall.setOnClickListener(v -> {
            popup.dismiss();
            if (entry.system) {
                toast(getString(R.string.system_app_uninstall));
                openAppInfo(entry.pkg);
            } else {
                uninstall(entry);
            }
        });

        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        // Focus the harmless action; the uninstall row is one press down, never the default.
        panel.post(rowPin::requestFocus);
    }

    private void togglePin(AppAdapter.Entry entry, boolean alreadyPinned) {
        DockStore dock = adapter.dock();
        if (alreadyPinned) {
            dock.unpin(entry.pkg);
            // The app leaves the dock for the grid: keep its key so focus follows it there.
            lastFocused = 1;
            lastFocusedKey = entry.component.flattenToShortString();
            toast(getString(R.string.dock_removed, entry.label));
        } else {
            List<String> apps = dock.apps();
            int slot = apps.size();
            if (slot >= adapter.dockSize()) {
                // Dock is full: put the app in the slot the user is most likely to want to swap, the
                // first one, so long-pressing never silently does nothing.
                slot = 0;
            }
            dock.pinOver(entry.pkg, slot);
            // A folder and the dock cannot both own the app; pinning takes it out of the folder.
            adapter.folders().remove(entry.pkg);
            lastFocused = 0;
            lastDockIndex = Math.max(0, adapter.dockIndexOf(entry.pkg));
            lastFocusedKey = entry.component.flattenToShortString();
            toast(getString(R.string.dock_added, entry.label));
        }
        refreshDock();
    }

    /**
     * The system uninstaller. ACTION_UNINSTALL_PACKAGE with EXTRA_RETURN_RESULT is the variant that
     * reports back; some TV ROMs only register ACTION_DELETE, so that is the fallback. Whatever the
     * ROM does, we check afterwards whether the package is really gone instead of assuming the
     * confirmation screen settled it.
     */
    private void uninstall(final AppAdapter.Entry entry) {
        Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                Uri.parse("package:" + entry.pkg))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true);
        if (intent.resolveActivity(getPackageManager()) == null) {
            intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + entry.pkg));
        }
        try {
            pendingUninstall = entry.pkg;
            startActivityForResult(intent, REQ_UNINSTALL);
        } catch (RuntimeException e) {
            pendingUninstall = null;
            Errors.record("uninstall", entry.pkg + " " + e.getClass().getSimpleName());
            openAppInfo(entry.pkg);
        }
    }

    private void openAppInfo(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)));
        } catch (RuntimeException e) {
            toast(getString(R.string.not_available));
        }
    }

    /** Reload the grid so the new dock order takes effect, then put focus back where the user was. */
    private void refreshDock() {
        final String key = lastFocusedKey;
        final int dockAt = lastDockIndex;
        final boolean wasDock = lastFocused == 0;
        adapter.reload();
        updateEmptyHint();
        grid.scheduleLayoutAnimation();
        if (dockEditing) {
            focusDock(Math.min(dockEditIndex, adapter.dockSize() - 1));
            return;
        }
        if (wasDock) {
            // Pinning: the key is now a dock slot, positionOf() is -1, so stay on the dock.
            // Unpinning: the key moved into the grid, so follow it there.
            int pos = adapter.positionOf(key);
            if (pos >= 0) {
                lastFocused = pos;
                focusTile(pos);
            } else {
                focusDock(dockAt);
            }
            return;
        }
        int pos = adapter.positionOf(key);
        if (pos >= 0) {
            View v = grid.getLayoutManager().findViewByPosition(pos);
            if (v != null) v.requestFocus();
        } else {
            focusDock(dockAt);
        }
    }

    /**
     * "Favourites" button: shows every installed app, with the pinned ones ticked, so the dock can be
     * rebuilt from the sofa instead of by editing config.xml and recompiling.
     */
    private void showDockPicker() {
        if (dockPicker != null && dockPicker.isShowing()) {
            dockPicker.dismiss();
            return;
        }
        View panel = getLayoutInflater().inflate(R.layout.popup_dock, root, false);
        RecyclerView list = panel.findViewById(R.id.rows);
        list.setLayoutManager(new LinearLayoutManager(this));

        final List<ResolveInfo> found = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            found.add(ri);
        }
        // Same merge as the desk grid: apps that only publish the plain LAUNCHER category (phone
        // builds such as ZeroTier Fix, and everything on 4.x TVs) still need to be pinnable.
        Set<String> seenPackages = new HashSet<>();
        for (ResolveInfo ri : found) seenPackages.add(ri.activityInfo.packageName);
        Intent plain = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(plain, 0)) {
            if (ri.activityInfo == null) continue;
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            if (seenPackages.add(ri.activityInfo.packageName)) found.add(ri);
        }
        Collections.sort(found, (a, b) -> String.valueOf(a.loadLabel(pm))
                .compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));

        final DockStore dock = adapter.dock();
        list.setAdapter(new RecyclerView.Adapter<DockHolder>() {
            @Override
            public DockHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_dock, parent, false);
                return new DockHolder(v);
            }

            @Override
            public void onBindViewHolder(@NonNull DockHolder h, int position) {
                ResolveInfo ri = found.get(position);
                String label = String.valueOf(ri.loadLabel(pm));
                String pkg = ri.activityInfo.packageName;
                h.name.setText(label);
                h.icon.setImageDrawable(ri.loadIcon(pm));
                h.tick.setVisibility(dock.isPinned(pkg) ? View.VISIBLE : View.INVISIBLE);
                h.item.setOnClickListener(v -> {
                    // Read the state live. A flag captured at bind time goes stale after the first
                    // tap, so tapping a ticked row again kept trying to unpin an app that was
                    // already gone and the row looked dead.
                    if (dock.isPinned(pkg)) {
                        dock.unpin(pkg);
                        h.tick.setVisibility(View.INVISIBLE);
                        toast(getString(R.string.dock_removed, label));
                    } else if (dock.apps().size() >= adapter.dockSize()) {
                        // The dock has a fixed number of slots; make that clear instead of silently
                        // appending a tile the launcher would never draw.
                        toast(getString(R.string.dock_full, adapter.dockSize()));
                        return;
                    } else {
                        dock.pin(pkg, dock.apps().size());
                        adapter.folders().remove(pkg);
                        h.tick.setVisibility(View.VISIBLE);
                        toast(getString(R.string.dock_added, label));
                    }
                    refreshDock();
                });
            }

            @Override
            public int getItemCount() {
                return found.size();
            }
        });

        View anchor = findViewById(R.id.btnControl);
        // Sized from its content and hung under the button with the left edges aligned: the same
        // glass dropdown as the Inputs menu, not a settings sheet floating mid-screen.
        dockPicker = glassPanel(panel, anchor, 260);
        dropIn(dockPicker, panel, anchor, Gravity.START);
        // The rows appear one layout pass after the popup does. Without putting focus on the first
        // one the remote has nothing selected and the panel looks frozen.
        list.post(() -> {
            View first = list.getChildAt(0);
            if (first != null) first.requestFocus();
            else list.requestFocus();
        });
    }

    /**
     * Choose the dock's slot count outright. Eight rows, current one ticked; picking a row resizes
     * the dock behind the dropdown and keeps the list open so the user can try another count.
     */
    private void showDockSizePicker() {
        if (adapter == null) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_dock, root, false);
        RecyclerView list = panel.findViewById(R.id.rows);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new RecyclerView.Adapter<DockHolder>() {
            @Override
            public DockHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new DockHolder(LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_dock, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull DockHolder h, int position) {
                final int size = DockStore.MIN_SIZE + position;
                h.icon.setImageResource(R.drawable.ic_dock);
                h.name.setText(getResources().getQuantityString(
                        R.plurals.dock_size_choice, size, size));
                h.tick.setVisibility(adapter.dockSize() == size ? View.VISIBLE : View.INVISIBLE);
                h.item.setOnClickListener(v -> {
                    applyDockSize(size);
                    notifyDataSetChanged();
                });
            }

            @Override
            public int getItemCount() {
                return DockStore.MAX_SIZE - DockStore.MIN_SIZE + 1;
            }
        });

        View anchor = findViewById(R.id.btnControl);
        dockSizePicker = glassPanel(panel, anchor, 260);
        dockSizePicker.setOnDismissListener(this::focusControlPill);
        dropIn(dockSizePicker, panel, anchor, Gravity.START);
        final int at = Math.max(0, adapter.dockSize() - DockStore.MIN_SIZE);
        list.post(() -> {
            View row = list.getChildAt(at);
            if (row != null && !row.requestFocus()) row.requestFocusFromTouch();
            else if (row == null) list.requestFocus();
        });
    }

    // ---- folders ---------------------------------------------------------------------------------

    @Override
    public void onDockEmptyPressed() {
        showDockPicker();
    }

    @Override
    public void onFolderOpen(FolderStore.Folder folder) {
        showFolder(folder);
    }

    @Override
    public void onFolderLongPressed(FolderStore.Folder folder) {
        showFolderMenu(folder);
    }

    /** "移入文件夹" from a grid app: pick an existing folder or start a new one. */
    private void showFolderChoice(final AppAdapter.Entry entry) {
        final List<FolderStore.Folder> existing = adapter.folders().all();
        View panel = getLayoutInflater().inflate(R.layout.popup_dock, root, false);
        RecyclerView list = panel.findViewById(R.id.rows);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new RecyclerView.Adapter<DockHolder>() {
            @Override
            public DockHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new DockHolder(LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_dock, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull DockHolder h, int position) {
                h.tick.setVisibility(View.INVISIBLE);
                final boolean create = position == 0;
                if (create) {
                    h.icon.setImageResource(R.drawable.ic_plus);
                    h.name.setText(R.string.folder_new);
                } else {
                    FolderStore.Folder f = existing.get(position - 1);
                    h.icon.setImageResource(R.drawable.ic_apps);
                    h.name.setText(f.name + " · " + f.pkgs.size());
                }
                h.item.setOnClickListener(v -> {
                    if (folderPick != null) folderPick.dismiss();
                    putInFolder(entry, create ? null : existing.get(position - 1));
                });
            }

            @Override
            public int getItemCount() {
                return existing.size() + 1;
            }
        });
        folderPick = centeredMenu(panel, 460);
        folderPick.setOnDismissListener(() -> folderPick = null);
        list.post(() -> {
            if (list.getChildAt(0) != null) list.getChildAt(0).requestFocus();
            else list.requestFocus();
        });
    }

    /** Moves [entry] into [folder], or into a brand new one when [folder] is null. */
    private void putInFolder(AppAdapter.Entry entry, FolderStore.Folder folder) {
        FolderStore.Folder made = folder != null ? folder : adapter.folders().create(entry.pkg);
        if (folder != null) adapter.folders().add(folder.id, entry.pkg);
        adapter.reload();
        updateEmptyHint();
        grid.scheduleLayoutAnimation();
        toast(getString(R.string.folder_added, entry.label));
        lastFocusedKey = "folder:" + made.id;
        int pos = adapter.positionOf(lastFocusedKey);
        if (pos >= 0) {
            lastFocused = pos;
            focusTile(pos);
        }
    }

    /** A folder's panel: its apps with names, and one "+" tile for adding another. */
    private void showFolder(final FolderStore.Folder folder) {
        if (folder == null) return;
        if (folderPanel != null && folderPanel.isShowing()) folderPanel.dismiss();
        openFolder = folder;
        final List<AppAdapter.Entry> members = new ArrayList<>();
        for (String pkg : folder.pkgs) {
            AppAdapter.Entry e = adapter.entryForPkg(pkg);
            if (e != null) members.add(e);
        }

        View panel = getLayoutInflater().inflate(R.layout.popup_folder, root, false);
        ((TextView) panel.findViewById(R.id.folderTitle)).setText(folder.name);
        RecyclerView gridView = panel.findViewById(R.id.folderGrid);
        gridView.setLayoutManager(new GridLayoutManager(this, 4));
        gridView.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public int getItemCount() {
                return members.size() + 1;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_folder_app, parent, false)) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                ImageView icon = holder.itemView.findViewById(R.id.icon);
                TextView name = holder.itemView.findViewById(R.id.name);
                if (position == members.size()) {
                    icon.setImageResource(R.drawable.ic_plus);
                    name.setText(R.string.folder_add_app);
                    holder.itemView.setOnClickListener(v -> showFolderAppPicker(folder));
                    holder.itemView.setOnLongClickListener(v -> true);
                    return;
                }
                final AppAdapter.Entry e = members.get(position);
                icon.setImageDrawable(e.icon);
                name.setText(e.label);
                holder.itemView.setOnClickListener(v -> {
                    if (folderPanel != null) folderPanel.dismiss();
                    adapter.launchEntry(e);
                });
                holder.itemView.setOnLongClickListener(v -> {
                    showFolderAppMenu(folder, e);
                    return true;
                });
            }
        });

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // Four across only when there are four to show; a one-app folder was a wide empty pane.
        int columns = Math.min(4, members.size() + 1);
        int width = Math.min(dp(12 + 187 * columns), (int) (screenW * 0.86f));
        int rows = (members.size() + 1 + columns - 1) / columns;
        int height = Math.min((int) (screenH * 0.82f),
                dp(44) + rows * dp(140) + dp(16));
        PopupWindow popup = new PopupWindow(panel, width, height, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(() -> {
            folderPanel = null;
            openFolder = null;
        });
        folderPanel = popup;
        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        gridView.post(() -> {
            if (gridView.getChildAt(0) != null) gridView.getChildAt(0).requestFocus();
            else gridView.requestFocus();
        });
    }

    /** Long-press on a folder tile: add apps, or dissolve the folder (its apps return to the grid). */
    private void showFolderMenu(final FolderStore.Folder folder) {
        View panel = getLayoutInflater().inflate(R.layout.popup_app, root, false);
        ((TextView) panel.findViewById(R.id.appTitle)).setText(folder.name);
        ((TextView) panel.findViewById(R.id.pinTitle)).setText(R.string.folder_add_app);
        ((ImageView) panel.findViewById(R.id.pinIcon)).setImageResource(R.drawable.ic_plus);
        View rowMove = panel.findViewById(R.id.rowMove);
        TextView moveTitle = panel.findViewById(R.id.moveTitle);
        ((ImageView) panel.findViewById(R.id.moveIcon)).setImageResource(R.drawable.ic_trash);
        moveTitle.setText(R.string.folder_dissolve);
        rowMove.setVisibility(View.VISIBLE);
        View rowUninstall = panel.findViewById(R.id.rowUninstall);
        rowUninstall.setVisibility(View.GONE);

        final PopupWindow popup = centeredMenu(panel, 420);
        appMenu = popup;
        popup.setOnDismissListener(() -> appMenu = null);
        panel.findViewById(R.id.rowPin).setOnClickListener(v -> {
            popup.dismiss();
            showFolderAppPicker(folder);
        });
        rowMove.setOnClickListener(v -> {
            popup.dismiss();
            adapter.folders().dissolve(folder.id);
            adapter.reload();
            updateEmptyHint();
            grid.scheduleLayoutAnimation();
            toast(getString(R.string.folder_dissolved));
            focusDock(lastDockIndex);
        });
        panel.post(() -> panel.findViewById(R.id.rowPin).requestFocus());
    }

    /** Long-press on an app inside a folder: take it out, or uninstall it. */
    private void showFolderAppMenu(final FolderStore.Folder folder, final AppAdapter.Entry entry) {
        View panel = getLayoutInflater().inflate(R.layout.popup_app, root, false);
        ((TextView) panel.findViewById(R.id.appTitle)).setText(entry.label);
        ((TextView) panel.findViewById(R.id.pinTitle)).setText(R.string.folder_remove);
        ((ImageView) panel.findViewById(R.id.pinIcon)).setImageResource(R.drawable.ic_move);
        View rowMove = panel.findViewById(R.id.rowMove);
        rowMove.setVisibility(View.GONE);
        View rowUninstall = panel.findViewById(R.id.rowUninstall);
        if (entry.system) {
            ((TextView) panel.findViewById(R.id.uninstallTitle)).setText(R.string.app_menu_info);
            ImageView icon = panel.findViewById(R.id.uninstallIcon);
            icon.setImageResource(R.drawable.ic_settings);
            if (Build.VERSION.SDK_INT >= 21) {
                icon.setImageTintList(getResources().getColorStateList(R.drawable.pill_text));
            }
        }

        final PopupWindow popup = centeredMenu(panel, 420);
        appMenu = popup;
        popup.setOnDismissListener(() -> appMenu = null);
        panel.findViewById(R.id.rowPin).setOnClickListener(v -> {
            popup.dismiss();
            if (folderPanel != null) folderPanel.dismiss();
            adapter.folders().remove(entry.pkg);
            adapter.reload();
            updateEmptyHint();
            grid.scheduleLayoutAnimation();
            FolderStore.Folder fresh = adapter.folders().byId(folder.id);
            if (fresh != null) showFolder(fresh);
        });
        rowUninstall.setOnClickListener(v -> {
            popup.dismiss();
            if (folderPanel != null) folderPanel.dismiss();
            if (entry.system) {
                toast(getString(R.string.system_app_uninstall));
                openAppInfo(entry.pkg);
            } else {
                uninstall(entry);
            }
        });
        panel.post(() -> panel.findViewById(R.id.rowPin).requestFocus());
    }

    /** Pick another installed app (not docked, not already in a folder) to put into this folder. */
    private void showFolderAppPicker(final FolderStore.Folder folder) {
        final List<AppAdapter.Entry> pool = new ArrayList<>();
        Set<String> docked = new HashSet<>(adapter.dock().apps());
        for (AppAdapter.Entry e : adapter.entries()) {
            if (docked.contains(e.pkg)) continue;
            if (adapter.folders().folderOf(e.pkg) != null) continue;
            pool.add(e);
        }
        if (pool.isEmpty()) {
            toast(getString(R.string.folder_empty));
            return;
        }
        Collections.sort(pool, (a, b) -> a.label.compareToIgnoreCase(b.label));
        View panel = getLayoutInflater().inflate(R.layout.popup_dock, root, false);
        RecyclerView list = panel.findViewById(R.id.rows);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new RecyclerView.Adapter<DockHolder>() {
            @Override
            public DockHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new DockHolder(LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_dock, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull DockHolder h, int position) {
                AppAdapter.Entry e = pool.get(position);
                h.icon.setImageDrawable(e.icon);
                h.name.setText(e.label);
                h.tick.setVisibility(View.INVISIBLE);
                h.item.setOnClickListener(v -> {
                    if (folderPick != null) folderPick.dismiss();
                    adapter.folders().add(folder.id, e.pkg);
                    adapter.reload();
                    updateEmptyHint();
                    grid.scheduleLayoutAnimation();
                    FolderStore.Folder fresh = adapter.folders().byId(folder.id);
                    if (fresh != null) showFolder(fresh);
                });
            }

            @Override
            public int getItemCount() {
                return pool.size();
            }
        });
        folderPick = centeredMenu(panel, 460);
        folderPick.setOnDismissListener(() -> folderPick = null);
        list.post(() -> {
            if (list.getChildAt(0) != null) list.getChildAt(0).requestFocus();
            else list.requestFocus();
        });
    }

    /** A centred glass menu of the app menu's width; the caller keeps the returned popup. */
    private PopupWindow centeredMenu(View panel, int widthDp) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        PopupWindow popup = new PopupWindow(panel,
                Math.min(dp(widthDp), (int) (screenW * 0.82f)),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        return popup;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ---- wallpaper ------------------------------------------------------------------------------

    /**
     * "Wallpaper" panel: turn the Bing daily picture on or off, pick a picture already on the TV, or
     * drop the wallpaper entirely. The panel is a popup because it is a one-off setting, not a place
     * the user browses.
     */
    private void showWallpaperPicker() {
        if (wallpaperPanel != null && wallpaperPanel.isShowing()) {
            wallpaperPanel.dismiss();
            return;
        }
        wall = new Wallpaper(this);

        View panel = getLayoutInflater().inflate(R.layout.popup_wallpaper, root, false);
        ImageView bingTick = panel.findViewById(R.id.bingTick);
        TextView status = panel.findViewById(R.id.bingStatus);
        TextView columnsStatus = panel.findViewById(R.id.columnsStatus);
        View rowBing = panel.findViewById(R.id.rowBing);
        View rowColumns = panel.findViewById(R.id.rowColumns);
        View rowPick = panel.findViewById(R.id.rowPick);
        View rowOff = panel.findViewById(R.id.rowOff);

        bingTick.setVisibility(wall.autoDaily() ? View.VISIBLE : View.INVISIBLE);
        status.setText(wallpaperStatus());
        columnsStatus.setText(columnsStatus());

        rowBing.setOnClickListener(v -> {
            boolean on = !wall.autoDaily();
            wall.setAutoDaily(on);
            bingTick.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            if (on) {
                status.setText(getString(R.string.bing_fetching));
                toast(getString(R.string.bing_fetching));
                // Off the main thread: this hits the network and must never stall the home screen.
                new Thread(() -> {
                    boolean done = wall.refreshBingIfDue();
                    handler.post(() -> {
                        status.setText(wallpaperStatus());
                        if (done) {
                            toast(getString(R.string.bing_updated));
                            applyWallpaper();
                        } else {
                            toast(getString(R.string.bing_failed));
                        }
                    });
                }).start();
            } else {
                // Only stop the daily change; whatever is on screen now stays.
                status.setText(wallpaperStatus());
                toast(getString(R.string.bing_stopped));
            }
        });

        weatherStatusView = panel.findViewById(R.id.weatherStatus);
        final View rowWeather = panel.findViewById(R.id.rowWeather);
        weatherStatusView.setText(weatherStatus());
        rowWeather.setOnClickListener(v -> {
            boolean on = !Weather.videoOn(this);
            Weather.setVideoOn(this, on);
            weatherStatusView.setText(weatherStatus());
            applyWeatherVideo();
            if (on) refreshWeather(true);
        });

        final TextView cityStatusView = panel.findViewById(R.id.cityStatus);
        cityStatusView.setText(cityStatus());
        panel.findViewById(R.id.rowCity).setOnClickListener(v -> {
            if (wallpaperPanel != null) wallpaperPanel.dismiss();
            showCityPicker();
        });

        final TextView forecastStatusView = panel.findViewById(R.id.forecastStatus);
        forecastStatusView.setText(forecastStatus());
        panel.findViewById(R.id.rowForecast).setOnClickListener(v -> {
            Weather.setForecastDays(this, Weather.forecastDays(this) % 3 + 1);
            forecastStatusView.setText(forecastStatus());
            Weather.Now now = Weather.cached(this);
            if (now != null) showWeather(now);
            // A cache written before multi-day support has no forecast; fetch instead of waiting.
            if (now == null || now.days.length < Weather.forecastDays(this)) refreshWeather(true);
        });

        rowColumns.setOnClickListener(v -> cycleColumns(columnsStatus));

        // The TV's own settings live here too, so the one settings entry people look for is complete.
        panel.findViewById(R.id.rowTvNetwork).setOnClickListener(v -> {
            if (wallpaperPanel != null) wallpaperPanel.dismiss();
            openPage("network", systemIntent(Settings.ACTION_WIFI_SETTINGS));
        });
        panel.findViewById(R.id.rowTvPicture).setOnClickListener(v -> {
            if (wallpaperPanel != null) wallpaperPanel.dismiss();
            openPage("Picture", pictureIntent(this));
        });
        panel.findViewById(R.id.rowTvApps).setOnClickListener(v -> {
            if (wallpaperPanel != null) wallpaperPanel.dismiss();
            // No deep link to the app list on TCL; its settings top page is the closest page.
            openPage("Main", systemIntent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        });
        panel.findViewById(R.id.rowTvSystem).setOnClickListener(v -> {
            if (wallpaperPanel != null) wallpaperPanel.dismiss();
            openPage("Main", tvSettingsIntent(this));
        });

        final TextView menuKeyStatus = panel.findViewById(R.id.menuKeyStatus);
        final View rowMenuKey = panel.findViewById(R.id.rowMenuKey);
        menuKeyStatus.setText(menuKeyEverywhere()
                ? getString(R.string.menu_key_scope_on) : getString(R.string.menu_key_scope_off));
        rowMenuKey.setOnClickListener(v -> {
            boolean on = !menuKeyEverywhere();
            getSharedPreferences(HomeKeyService.PREFS, MODE_PRIVATE)
                    .edit().putBoolean(HomeKeyService.KEY_MENU_EVERYWHERE, on).apply();
            menuKeyStatus.setText(on
                    ? getString(R.string.menu_key_scope_on) : getString(R.string.menu_key_scope_off));
            toast(getString(on ? R.string.menu_key_on : R.string.menu_key_off));
        });

        final TextView motionStatus = panel.findViewById(R.id.motionStatus);
        final View rowMotion = panel.findViewById(R.id.rowMotion);
        motionStatus.setText(reducedMotion()
                ? getString(R.string.reduced_motion_on) : getString(R.string.reduced_motion_off));
        rowMotion.setOnClickListener(v -> {
            boolean on = !reducedMotion();
            getSharedPreferences(PREFS_LAYOUT, MODE_PRIVATE).edit().putBoolean(KEY_REDUCED_MOTION, on).apply();
            motionStatus.setText(on ? getString(R.string.reduced_motion_on) : getString(R.string.reduced_motion_off));
            toast(getString(on ? R.string.reduced_motion_turned_on : R.string.reduced_motion_turned_off));
            // The tile size maths reads the zoom factor, so the desk has to be rebuilt for it to apply.
            recreate();
        });

        View rowRestore = panel.findViewById(R.id.rowRestore);
        rowRestore.setOnClickListener(v -> uninstallSelf());

        rowPick.setOnClickListener(v -> {
            wallpaperPanel.dismiss();
            openPhotoPicker();
        });

        rowOff.setOnClickListener(v -> {
            boolean removed = wall.clear();
            bingTick.setVisibility(View.INVISIBLE);
            wall.setAutoDaily(false);
            // The weather loop is a wallpaper too: removing the wallpaper removes it as well.
            Weather.setVideoOn(this, false);
            stage.clearWeatherVideo();
            if (weatherStatusView != null) weatherStatusView.setText(weatherStatus());
            status.setText(wallpaperStatus());
            // Drop the picture from the desk straight away; a wallpaper that stays on screen after
            // the user asked to remove it looks like the button did nothing.
            removeWallpaper();
            toast(getString(removed ? R.string.wallpaper_removed : R.string.wallpaper_none_toast));
        });

        View anchor = findViewById(R.id.btnControl);
        // Sized from its content and hung under the button with the left edges aligned, exactly like
        // the Inputs menu. No row scaling: a scaled row is what used to need clipping turned off, and
        // the focus pill alone is enough to see where you are.
        wallpaperPanel = glassPanel(panel, anchor, 300);
        dropIn(wallpaperPanel, panel, anchor, Gravity.START);
    }

    /**
     * One tap back to the stock launcher. The stock launcher was never disabled or removed, so
     * uninstalling this app is the whole restore; the system uninstaller's own confirmation is the
     * only step between the row and the TV going back to PatchWall.
     */
    private void uninstallSelf() {
        HomeKeyService.suppressTakeover(60_000);
        if (wallpaperPanel != null) wallpaperPanel.dismiss();
        if (controlPanel != null) controlPanel.dismiss();
        try {
            startActivity(new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            toast(getString(R.string.not_available));
        }
    }

    /** One line telling the user what the wallpaper is right now. */
    private String wallpaperStatus() {
        if (wall == null) wall = new Wallpaper(this);
        if (wall.hasWallpaper()) {
            long kb = Math.max(1, wall.sizeBytes() / 1024);
            return getString(R.string.wallpaper_on, kb);
        }
        return getString(R.string.wallpaper_none);
    }

    /** The user picked a file in the system picker. Copy it in as the one and only wallpaper. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UNINSTALL) {
            final String pkg = pendingUninstall;
            pendingUninstall = null;
            if (pkg == null) return;
            // The package manager removes the app a moment after the uninstaller returns, so check
            // the result rather than the return code alone: several TV ROMs ignore the latter.
            handler.postDelayed(() -> {
                if (isFinishing() || adapter == null) return;
                if (!installed(this, pkg)) {
                    // An uninstalled app must leave every owner: the dock, any folder, then the grid.
                    if (dockEditing && dockEditKey != null && dockEditKey.startsWith(pkg + "/")) {
                        stopDockEdit();
                    }
                    adapter.dock().unpin(pkg);
                    adapter.folders().remove(pkg);
                    adapter.reload();
                    updateEmptyHint();
                    grid.scheduleLayoutAnimation();
                } else if (resultCode != RESULT_CANCELED) {
                    Errors.record("uninstall", pkg + " still installed, result " + resultCode);
                    toast(getString(R.string.uninstall_failed));
                    openAppInfo(pkg);
                }
            }, 800);
            return;
        }
        if (requestCode != REQ_PICK_WALLPAPER || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        if (wall == null) wall = new Wallpaper(this);
        if (wall.importFrom(uri)) {
            toast(getString(R.string.wallpaper_set));
            applyWallpaper();
        } else {
            toast(getString(R.string.wallpaper_bad));
        }
    }

    /**
     * A photo grid of our own, because the system picker on an Android 14 TV is a touch UI — the
     * remote cannot put focus on a picture — and Xiaomi's OPEN_DOCUMENT handler is an AOSP stub.
     * The nine most recent pictures are enough to find the one just copied onto the set.
     */
    private void openPhotoPicker() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(
                Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            photosAfterGrant = true;
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_READ_PHOTOS);
            return;
        }
        showPhotoChooser();
    }

    private void showPhotoChooser() {
        final List<Uri> photos = new ArrayList<>();
        Cursor c = null;
        try {
            c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID},
                    null, null, MediaStore.Images.Media.DATE_ADDED + " DESC");
            while (c != null && c.moveToNext() && photos.size() < 9) {
                photos.add(ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
            }
        } catch (RuntimeException ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (photos.isEmpty()) {
            toast(getString(R.string.no_photos));
            return;
        }
        if (photoPanel != null && photoPanel.isShowing()) return;

        View panel = getLayoutInflater().inflate(R.layout.popup_photos, root, false);
        ((TextView) panel.findViewById(R.id.photosTitle)).setText(R.string.choose_photo);
        LinearLayout rows = panel.findViewById(R.id.photoRows);
        LinearLayout line = null;
        final List<ImageView> thumbs = new ArrayList<>();
        for (int i = 0; i < photos.size(); i++) {
            if (i % 3 == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER);
                rows.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final Uri uri = photos.get(i);
            FrameLayout cell = new FrameLayout(this);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setPadding((int) (2 * density), (int) (2 * density),
                    (int) (2 * density), (int) (2 * density));
            cell.setBackgroundResource(R.drawable.photo_cell_bg);
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.addView(thumb, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) (98 * density), (int) (56 * density));
            lp.setMargins((int) (3 * density), (int) (3 * density),
                    (int) (3 * density), (int) (3 * density));
            line.addView(cell, lp);
            cell.setOnClickListener(v -> {
                photoPanel.dismiss();
                if (wall == null) wall = new Wallpaper(this);
                if (wall.importFrom(uri)) {
                    toast(getString(R.string.wallpaper_set));
                    applyWallpaper();
                } else {
                    toast(getString(R.string.wallpaper_bad));
                }
            });
            thumbs.add(thumb);
        }
        View anchor = findViewById(R.id.btnControl);
        photoPanel = glassPanel(panel, anchor, 340);
        dropIn(photoPanel, panel, anchor, Gravity.END);
        final LinearLayout firstLine = line;
        panel.post(() -> firstLine.getChildAt(0).requestFocus());
        loadPhotoThumbs(photos, thumbs);
    }

    /** Decoded off the main thread: a 4K photo takes long enough to drop frames. */
    private void loadPhotoThumbs(final List<Uri> photos, final List<ImageView> thumbs) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            for (int i = 0; i < photos.size(); i++) {
                final Bitmap thumb = photoThumb(photos.get(i));
                if (thumb == null) continue;
                final ImageView view = thumbs.get(i);
                ui.post(() -> {
                    if (!isFinishing()) view.setImageBitmap(thumb);
                });
            }
        }, "photo-thumbs").start();
    }

    private Bitmap photoThumb(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return getContentResolver().loadThumbnail(uri,
                        new android.util.Size((int) (196 * density), (int) (112 * density)), null);
            }
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 196 * density) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            in = getContentResolver().openInputStream(uri);
            Bitmap b = in == null ? null : BitmapFactory.decodeStream(in, null, opts);
            if (in != null) in.close();
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** The system picker, for sets where it is remote-friendly. */
    private void startSystemPicker() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        if (!resolvesReal(this, pick) && Build.VERSION.SDK_INT >= 19) {
            pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*");
        }
        try {
            startActivityForResult(pick, REQ_PICK_WALLPAPER);
        } catch (RuntimeException e) {
            toast(getString(R.string.not_available));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_READ_PHOTOS) {
            if (!photosAfterGrant) return;
            photosAfterGrant = false;
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showPhotoChooser();
            } else {
                startSystemPicker();   // a set whose picker handles the remote after all
            }
            return;
        }
        if (requestCode == REQ_WIFI_SCAN) {
            if (!wifiPanelAfterGrant) return;
            wifiPanelAfterGrant = false;
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showNetworkDetails();
            } else {
                // Without the grant there is no list of neighbours, but the saved networks and
                // the way out to Settings still are.
                buildWifiPanel(wm);
            }
            return;
        }
        if (requestCode != REQ_INSTALL_STORAGE) return;
        final java.io.File apk = pendingInstall;
        pendingInstall = null;
        if (apk == null) return;
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.not_available));
            return;
        }
        if (!Remote.install(this, apk)) toast(getString(R.string.not_available));
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     * Kept as a field so a wallpaper can be added or dropped in place, without rebuilding the activity.
     */
    private void showWallpaper(java.io.File file) {
        // Decoding and downsampling live in WallpaperStage, off the UI thread.
        stage.setDefault(file);
    }

    private void removeWallpaper() {
        stage.setDefault(null);
    }

/** Whether a stored column count is one the panel offers (anything else means "automatic"). */
    private boolean isColumnChoice(int n) {
        for (int c : COLUMN_CHOICES) if (c == n) return true;
        return false;
    }

    /**
     * How many icons fit on this screen without them becoming postage stamps: the most columns that still
     * leave each tile at (near) its design size. It reuses the tile sizer's own arithmetic instead of a
     * table of width thresholds, so "automatic" and "what the user sees" can never disagree — and a screen
     * the table had never heard of gets a sensible answer rather than the smallest option. Automatic
     * stops at six: eight is small enough that asking for it should be deliberate.
     */
    private int recommendColumns() {
        int innerDp = getResources().getConfiguration().screenWidthDp;   // the grid spans the window
        int design = Math.round(AppAdapter.DESIGN_W_DP * 0.95f);        // a shade under the design size
        for (int i = COLUMN_CHOICES.length - 1; i >= 0; i--) {
            int c = COLUMN_CHOICES[i];
            if (c > 6) continue;
            if (AppAdapter.tileWidthPx(innerDp, c, 1f) >= design) return c;
        }
        return COLUMN_CHOICES[0];
    }

    /** Whether the current column count came from the user's choice or from {@link #recommendColumns()}. */
    private boolean columnsAreAutomatic() {
        return !isColumnChoice(getSharedPreferences(PREFS_LAYOUT, MODE_PRIVATE).getInt(KEY_COLUMNS, 0));
    }

    /** One line saying how many icons per row the desk is using, and where that number came from. */
    private String columnsStatus() {
        return columnsAreAutomatic()
                ? getString(R.string.columns_auto, COLUMNS)
                : getString(R.string.columns_manual, COLUMNS);
    }

    /**
     * Tapping the row steps through auto → 4 → 6 → 8 → auto: each tap is a size step smaller in the
     * app grid, nothing above it changes. A submenu on a remote would cost more presses than it
     * saves, and the current value is printed under the row either way.
     */
    private void cycleColumns(TextView status) {
        int current = getSharedPreferences(PREFS_LAYOUT, MODE_PRIVATE).getInt(KEY_COLUMNS, 0);
        int at = -1;
        for (int i = 0; i < COLUMN_CHOICES.length; i++) {
            if (COLUMN_CHOICES[i] == current) { at = i; break; }
        }
        int next = (at >= 0 && at + 1 < COLUMN_CHOICES.length) ? COLUMN_CHOICES[at + 1] : 0;
        getSharedPreferences(PREFS_LAYOUT, MODE_PRIVATE).edit().putInt(KEY_COLUMNS, next).apply();
        COLUMNS = next > 0 ? next : recommendColumns();
        if (status != null) status.setText(columnsStatus());
        toast(next > 0 ? getString(R.string.columns_set, COLUMNS)
                : getString(R.string.columns_auto_toast, COLUMNS));
        // The row count changes the grid layout and the dock split, so the desk has to be rebuilt.
        // The panel is closed first: rebuilding underneath an open popup is what left a stuck
        // invisible window on this TV a moment ago.
        if (wallpaperPanel != null) wallpaperPanel.dismiss();
        handler.postDelayed(this::recreate, 150);
    }

    /** Re-apply whatever wallpaper is on disk now, adding or dropping it as needed. */
    private void applyWallpaper() {
        if (wall == null) wall = new Wallpaper(this);
        java.io.File f = wall.file();
        if (f != null && f.isFile() && f.length() > 0) showWallpaper(f);
        else removeWallpaper();
        applyWeatherVideo();
    }

    /** The weather loop is a separate layer; it is on only when the user asked and a reading exists. */
    private void applyWeatherVideo() {
        Weather.Now now = Weather.cached(this);
        if (Weather.videoOn(this) && now != null) stage.setWeatherVideo(Weather.video(now.kind));
        else stage.clearWeatherVideo();
    }

    /** Whether a picture is currently behind the desk, from the theme, a file, or the weather loop. */
    private boolean wallpaperPresent() {
        return (stage != null && (stage.hasImage() || stage.hasWeather())) || (cfg != null && cfg.wallpaper != null);
    }

    // ---- weather ---------------------------------------------------------------------------------

    private void refreshWeather(boolean force) {
        Weather.Callback cb = this::showWeather;
        if (force) Weather.fetch(this, cb);
        else Weather.refreshIfDue(this, cb);
    }

    private void showWeather(Weather.Now now) {
        if (now == null) {
            weatherRow.setVisibility(View.GONE);
            return;
        }
        weatherIcon.setImageResource(Weather.icon(now.kind));
        weatherText.setText(weatherLine(now));
        weatherRow.setVisibility(View.VISIBLE);
        if (weatherStatusView != null) weatherStatusView.setText(weatherStatus());
        if (Weather.videoOn(this)) stage.setWeatherVideo(Weather.video(now.kind));
    }

    /** “杭州 18° 多云 · 明天 16° 晴 · 后天 14° 雨”, cut to the number of days the user chose. */
    private String weatherLine(Weather.Now now) {
        StringBuilder line = new StringBuilder();
        if (now.city != null && !now.city.isEmpty()) line.append(now.city).append(' ');
        line.append(now.temp).append("° ").append(getString(Weather.label(now.kind)));
        int want = Weather.forecastDays(this);
        for (int i = 1; i < want && i < now.days.length; i++) {
            Weather.Day day = now.days[i];
            line.append(" · ")
                    .append(getString(i == 1 ? R.string.weather_tomorrow : R.string.weather_day_after))
                    .append(' ').append(day.max).append("° ")
                    .append(getString(Weather.label(day.kind)));
        }
        return line.toString();
    }

    private String cityStatus() {
        String picked = Weather.place(this);
        if (!picked.isEmpty()) return getString(R.string.weather_city_manual, picked);
        Weather.Now now = Weather.cached(this);
        String auto = now == null || now.city.isEmpty()
                ? getString(R.string.weather_fetching) : now.city;
        return getString(R.string.weather_city_auto, auto);
    }

    private String forecastStatus() {
        switch (Weather.forecastDays(this)) {
            case 2:
                return getString(R.string.forecast_2);
            case 3:
                return getString(R.string.forecast_3);
            default:
                return getString(R.string.forecast_1);
        }
    }

    // ---- weather city picker --------------------------------------------------------------------

    /** Provinces first, then the cities of the province, then the districts of the city. */
    private void showCityPicker() {
        if (cityData == null) cityData = loadCities();
        if (cityData == null) {
            toast(getString(R.string.not_available));
            return;
        }
        final List<String> rows = new ArrayList<>();
        rows.add(getString(R.string.weather_city_auto_row));
        for (int i = 0; i < cityData.length(); i++) {
            rows.add(cityData.optJSONObject(i).optString("p"));
        }
        showCityList(getString(R.string.weather_city_title), rows, position -> {
            if (position == 0) {
                Weather.clearPlace(this);
                refreshWeather(true);
                if (cityPicker != null) cityPicker.dismiss();
                return;
            }
            showProvince(position - 1);
        });
    }

    private void showProvince(final int provinceIndex) {
        final JSONObject province = cityData.optJSONObject(provinceIndex);
        final JSONArray cities = province == null ? null : province.optJSONArray("c");
        if (cities == null) return;
        // 北京/天津/上海/重庆 hold one city that is the province: its list is the districts.
        if (cities.length() == 1
                && province.optString("p").equals(cities.optJSONObject(0).optString("n"))) {
            showDistricts(provinceIndex, 0, true);
            return;
        }
        List<String> rows = new ArrayList<>();
        rows.add(getString(R.string.weather_city_back));
        for (int i = 0; i < cities.length(); i++) {
            rows.add(cities.optJSONObject(i).optString("n"));
        }
        showCityList(province.optString("p"), rows, position -> {
            if (position == 0) {
                showCityPicker();
                return;
            }
            showDistricts(provinceIndex, position - 1, false);
        });
    }

    /**
     * The third level: the city itself (its centre) and its districts and counties. Cities that
     * have no districts in the data — the Taiwan entries and the county-level cities provinces run
     * directly — are set straight from the previous level.
     */
    private void showDistricts(final int provinceIndex, final int cityIndex, final boolean fromTop) {
        final JSONObject province = cityData.optJSONObject(provinceIndex);
        final JSONArray cities = province == null ? null : province.optJSONArray("c");
        if (cities == null || cityIndex < 0 || cityIndex >= cities.length()) return;
        final JSONObject city = cities.optJSONObject(cityIndex);
        final JSONArray districts = city.optJSONArray("d");
        if (districts == null || districts.length() == 0) {
            setWeatherCity(city.optString("n"), city.optDouble("x", 0), city.optDouble("y", 0));
            return;
        }
        final List<String> rows = new ArrayList<>();
        rows.add(getString(R.string.weather_city_back));
        rows.add(city.optString("n"));
        for (int i = 0; i < districts.length(); i++) {
            rows.add(districts.optJSONArray(i).optString(0));
        }
        showCityList(city.optString("n"), rows, position -> {
            if (position == 0) {
                if (fromTop) showCityPicker();
                else showProvince(provinceIndex);
                return;
            }
            if (position == 1) {
                setWeatherCity(city.optString("n"), city.optDouble("x", 0), city.optDouble("y", 0));
                return;
            }
            JSONArray district = districts.optJSONArray(position - 2);
            if (district == null) return;
            setWeatherCity(district.optString(0), district.optDouble(1, 0), district.optDouble(2, 0));
        });
    }

    private void setWeatherCity(String name, double lat, double lon) {
        Weather.setPlace(this, name, lat, lon);
        refreshWeather(true);
        toast(getString(R.string.weather_city_set, name));
        if (cityPicker != null) cityPicker.dismiss();
    }

    private void showCityList(CharSequence title, List<String> rows, CityAdapter.OnPick pick) {
        if (cityPicker != null) cityPicker.dismiss();
        View panel = getLayoutInflater().inflate(R.layout.popup_city, root, false);
        ((TextView) panel.findViewById(R.id.cityTitle)).setText(title);
        RecyclerView list = panel.findViewById(R.id.cityRows);
        list.setLayoutManager(new LinearLayoutManager(this));
        final CityAdapter adapter = new CityAdapter();
        adapter.setRows(rows, pick);
        list.setAdapter(adapter);

        View anchor = findViewById(R.id.btnControl);
        final PopupWindow popup = glassPanel(panel, anchor, 300);
        popup.setOnDismissListener(() -> {
            if (cityPicker == popup) cityPicker = null;
        });
        cityPicker = popup;
        dropIn(popup, panel, anchor, Gravity.START);
        list.post(() -> {
            View first = list.getChildAt(0);
            if (first != null) first.requestFocus();
            else list.requestFocus();
        });
    }

    private JSONArray loadCities() {
        InputStream in = null;
        try {
            in = getAssets().open("cities.json");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONArray(new String(out.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            Errors.record("weather", "cities " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Plain text rows for the city picker; the popup has one focusable row per entry. */
    private static final class CityAdapter extends RecyclerView.Adapter<CityAdapter.Holder> {

        interface OnPick {
            void onPick(int position);
        }

        private final List<String> rows = new ArrayList<>();
        private OnPick pick;

        void setRows(List<String> names, OnPick onPick) {
            rows.clear();
            rows.addAll(names);
            pick = onPick;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_city, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.name.setText(rows.get(position));
            holder.itemView.setOnClickListener(v -> {
                int at = holder.getAdapterPosition();
                if (pick != null && at != RecyclerView.NO_POSITION) pick.onPick(at);
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final TextView name;

            Holder(View v) {
                super(v);
                name = v.findViewById(R.id.cityName);
            }
        }
    }

    private String weatherStatus() {
        if (!Weather.videoOn(this)) return getString(R.string.weather_wallpaper_off);
        Weather.Now now = Weather.cached(this);
        if (now == null) return getString(R.string.weather_fetching);
        return getString(R.string.weather_wallpaper_on, getString(Weather.label(now.kind)));
    }

    static final class DockHolder extends RecyclerView.ViewHolder {
        final View item;
        final ImageView icon;
        final TextView name;
        final ImageView tick;

        DockHolder(View v) {
            super(v);
            item = v.findViewById(R.id.item);
            icon = v.findViewById(R.id.icon);
            name = v.findViewById(R.id.name);
            tick = v.findViewById(R.id.tick);
        }
    }

    // ---- glass highlight -------------------------------------------------------------------------

    /** Put the highlight back on the focused tile after a layout change moved the tile. */
    /** @return true once the glass was re-placed; false while no tile has focus yet. */
    private boolean reseatGlass() {
        if (grid.findFocus() == null) return false;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(lastFocused);
        if (vh == null) return false;
        if (lastFocused == 0) {
            View card = dockCardAt(vh, lastDockIndex);
            if (card == null) return false;
            onDockFocused(lastDockIndex, adapter.dockEntryAt(lastDockIndex), card);
            return true;
        }
        View card = vh.itemView.findViewById(R.id.card);
        if (card == null) return false;
        onTileFocused(lastFocused, card);
        return true;
    }

    @Override
    public void onTileFocused(int position, View card) {
        // Cold start, or a column count the grid has not laid out yet: the tile has no size, and fitting
        // the glass to it would leave it at whatever the XML says until the next key press. Run again as
        // soon as the tile has been measured.
        if (card.getWidth() == 0 || card.getHeight() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) onTileFocused(position, card);
            });
            return;
        }
        // Grid tiles start at 1: position 0 is the dock row as a whole.
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        if (dockEditing) stopDockEdit();
        // Video apps get their platform's hot posters behind the desk; everything else keeps the
        // default wallpaper. Same app again is a no-op inside the stage.
        if (stage != null) stage.showForPackage(adapter.pkgAt(position));
        seatGlass(card);
        hideHero();
        hideProgram();
        // Scroll just enough so the whole glass (not only the tile) is visible.
        View item = (View) card.getParent();
        Rect want = revealRect;
        want.set(0, -(int) (12 * density), item.getWidth(), item.getHeight() + (int) (6 * density));
        grid.requestChildRectangleOnScreen(item, want, false);
    }

    /** Focus on one dock slot. [entry] is null on an empty "+" slot. */
    @Override
    public void onDockFocused(int index, AppAdapter.Entry entry, View card) {
        if (card.getWidth() == 0 || card.getHeight() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) onDockFocused(index, entry, card);
            });
            return;
        }
        lastFocused = 0;
        lastDockIndex = Math.max(0, Math.min(index, adapter.dockSize() - 1));
        lastFocusedKey = entry == null ? null : entry.component.flattenToShortString();
        if (stage != null) stage.showForPackage(entry == null ? null : entry.pkg);
        seatGlass(card);
        if (entry == null) {
            hideHero();
            hideProgram();
        }
        // Park the dock on the padding line: the band's bottom edge is the screen's bottom edge.
        RecyclerView.ViewHolder dockVh = grid.findViewHolderForAdapterPosition(0);
        View dockItem = dockVh == null ? (View) card.getParent() : dockVh.itemView;
        int decoratedTop = dockItem.getTop() - (int) (6 * density);
        int dy = decoratedTop - grid.getPaddingTop();
        if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
        if (entry != null) {
            Rect r = tileRect;
            r.set(0, 0, card.getWidth(), card.getHeight());
            root.offsetDescendantRectToMyCoords(card, r);
            showHero(entry.label, adapter.ambientColorOf(entry), r.top - dy);
            // The posters were just read for this package; show their title and dots right away.
            updateProgram(stage.postersFor(entry.pkg), stage.currentPosterIndex());
        }
    }

    /** Fit and spring the glass highlight to [card], wherever the card lives. */
    private void seatGlass(View card) {
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        // The tile width changes with the column count and with the screen; refitting on the width alone
        // catches both without measuring anything twice per frame.
        if (lp.width != (int) w || lp.height != (int) h || card.getWidth() != glassCardW) {
            lp.width = (int) w;
            lp.height = (int) h;
            glassCardW = card.getWidth();
            glass.setLayoutParams(lp);
            // GlassView draws its slab inset by pad, i.e. exactly over the zoomed tile: same corners as
            // the tile, which is itself proportionally rounder when a narrow grid made it smaller.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * AppAdapter.FOCUS_SCALE);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.setScaleX(1f);
            glass.setScaleY(1f);
            if (reducedMotion) glass.setAlpha(1f);
            else glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else if (reducedMotion) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.setScaleX(1f);
            glass.setScaleY(1f);
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(String label, int color, float dockTopInRoot) {
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            setAmbient(color);
            if (reducedMotion) {
                heroName.setAlpha(1f);
                ambient.setAlpha(1f);
            } else {
                heroName.animate().alpha(1f).setDuration(260).start();
                ambient.animate().alpha(1f).setDuration(600).start();
            }
            heroShown = true;
        } else if (reducedMotion) {
            // No cross-fade: swap the label and the glow where they stand.
            if (changed) {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(0f);
            }
            if (color != ambientColor) setAmbient(color);
            heroName.setAlpha(1f);
            ambient.setAlpha(1f);
        } else if (changed) {
            if (Build.VERSION.SDK_INT >= 16) {
                heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                    heroName.setText(label);
                    heroName.setY(y);
                    heroName.setTranslationX(12 * density);
                    heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
                }).start();
                if (color != ambientColor) {
                    ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                        setAmbient(color);
                        ambient.animate().alpha(1f).setDuration(500).start();
                    }).start();
                }
            } else {
                // Android 4.0/4.1 has no chained end action: swap in place.
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(0f);
                if (color != ambientColor) setAmbient(color);
            }
        } else {
            heroName.setY(y);
        }
        positionProgramBar();
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        if (Build.VERSION.SDK_INT >= 16) {
            ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
        } else {
            ambientGlow.setColor(0x5C000000 | c);   // 4.0 has no multi-colour gradient setter
        }
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        if (reducedMotion) {
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
            return;
        }
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        if (reducedMotion) {
            glass.setAlpha(0f);
            return;
        }
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- program shelf ---------------------------------------------------------------------------

    @Override
    public void onPosterShown(String pkg, List<HotPosters.Poster> posters, int index) {
        if (adapter == null || lastFocused != 0) return;
        AppAdapter.Entry e = adapter.dockEntryAt(lastDockIndex);
        if (e == null || !pkg.equals(e.pkg)) return;
        updateProgram(posters, index);
    }

    @Override
    public void onPosterGone() {
        hideProgram();
    }

    @Override
    public void onTileLaunching() {
        if (stage != null) stage.stopVideo();
    }

    private boolean isInProgramBar(View v) {
        View p = v;
        while (p != null) {
            if (p == programBar || p == programDots) return true;
            android.view.ViewParent parent = p.getParent();
            p = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /** Refresh the show title, the dot count and which dot is selected. */
    private void updateProgram(List<HotPosters.Poster> posters, int index) {
        if (posters == null || posters.isEmpty()) {
            hideProgram();
            return;
        }
        index = Math.max(0, Math.min(index, posters.size() - 1));
        int count = posters.size();
        buildDots(count);
        String title = posters.get(index).title;
        if (title.isEmpty()) {
            programTitle.setVisibility(View.GONE);
        } else {
            programTitle.setVisibility(View.VISIBLE);
            if (!title.contentEquals(programTitle.getText())) programTitle.setText(title);
        }
        int width = getResources().getDisplayMetrics().widthPixels - dp(136);
        programTitle.setMaxWidth(Math.max(dp(120), width));
        for (int i = 0; i < programDots.getChildCount(); i++) {
            TextView dot = (TextView) programDots.getChildAt(i);
            boolean selected = i == index;
            dot.setSelected(selected);
            animateDot(dot, selected ? 15 : 9);
        }
        programBar.setVisibility(View.VISIBLE);
        programDots.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
        if (heroShown) {
            if (programBar.getAlpha() < 1f) {
                programBar.animate().alpha(1f).setDuration(180).start();
            }
            if (programDots.getAlpha() < 1f) {
                programDots.animate().alpha(1f).setDuration(180).start();
            }
        } else {
            programBar.setAlpha(0f);
            programDots.setAlpha(0f);
        }
        positionProgramBar();
        positionProgramDots();
    }

    private void buildDots(int count) {
        if (count == dotCount && programDots.getChildCount() == count) return;
        dotCount = count;
        programDots.removeAllViews();
        for (int i = 0; i < count; i++) {
            final int at = i;
            TextView dot = new TextView(this);
            dot.setText("●");
            dot.setGravity(Gravity.CENTER);
            dot.setTextSize(9);
            dot.setIncludeFontPadding(false);
            dot.setTextColor(getResources().getColorStateList(R.color.dot_text));
            dot.setBackgroundResource(R.drawable.dot_bg);
            dot.setFocusable(true);
            dot.setFocusableInTouchMode(false);
            dot.setClickable(true);
            dot.setOnKeyListener(dotKeys);
            dot.setOnClickListener(v -> {
                if (adapter == null) return;
                if (lastFocused == 0) {
                    AppAdapter.Entry e = adapter.dockEntryAt(lastDockIndex);
                    if (e != null) adapter.launchEntry(e);
                } else {
                    adapter.launchAt(lastFocused);
                }
            });
            dot.setOnFocusChangeListener((v, focused) -> {
                if (!focused || stage == null) return;
                stage.setBrowsing(true);
                stage.selectPoster(at);
            });
            programDots.addView(dot, new LinearLayout.LayoutParams(dp(36), dp(36)));
        }
    }

    /**
     * The selected dot swells and the previous one shrinks, with a slight overshoot so the step
     * between posters reads as movement. The 36dp cells are fixed, so nothing around them moves.
     */
    private void animateDot(TextView dot, float sp) {
        Object tag = dot.getTag();
        if (tag instanceof ValueAnimator) ((ValueAnimator) tag).cancel();
        if (reducedMotion) {
            dot.setTextSize(sp);
            dot.setTag(null);
            return;
        }
        float from = dot.getTextSize() / getResources().getDisplayMetrics().scaledDensity;
        if (Math.abs(from - sp) < 0.5f) {
            dot.setTextSize(sp);
            dot.setTag(null);
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(from, sp);
        a.setDuration(200);
        a.setInterpolator(new OvershootInterpolator(1.2f));
        a.addUpdateListener(v -> dot.setTextSize((float) v.getAnimatedValue()));
        a.start();
        dot.setTag(a);
    }

    /** D-pad inside the dot strip: left/right step, down returns to the tile, up to the top bar. */
    private final View.OnKeyListener dotKeys = (v, keyCode, event) -> {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int count = programDots.getChildCount();
        int index = programDots.indexOfChild(v);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return index == 0;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return index < 0 || index == count - 1;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                focusDock(lastDockIndex);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                findViewById(R.id.btnControl).requestFocus();
                return true;
            default:
                return false;
        }
    };

    private void hideProgram() {
        if (programBar == null) return;
        if (isInProgramBar(getCurrentFocus())) return;
        programBar.setVisibility(View.GONE);
        programBar.setAlpha(0f);
        programDots.setVisibility(View.GONE);
        programDots.setAlpha(0f);
        programDots.removeAllViews();
        dotCount = 0;
    }

    private void positionProgramBar() {
        if (programBar == null || programBar.getVisibility() != View.VISIBLE) return;
        int h = programBar.getHeight() > 0 ? programBar.getHeight() : dp(36);
        programBar.setY(heroName.getY() - dp(8) - h);
    }

    /**
     * The dot strip sits in the gap between the app name and the dock, its left edge on the glass
     * band's left edge.
     */
    private void positionProgramDots() {
        if (programDots == null || programDots.getVisibility() != View.VISIBLE) return;
        RecyclerView.ViewHolder dock = grid.findViewHolderForAdapterPosition(0);
        if (dock == null) return;
        View band = dock.itemView.findViewById(R.id.dockBand);
        if (band == null) return;
        // Layout coordinates, not the visual ones: a focused dock tile is scaled up around its centre.
        int[] at = new int[2];
        band.getLocationInWindow(at);
        int bandLeft = at[0];
        int bandRight = at[0] + band.getWidth();
        int top = at[1];
        int w = programDots.getWidth() > 0
                ? programDots.getWidth() : programDots.getChildCount() * dp(36);
        int h = programDots.getHeight() > 0 ? programDots.getHeight() : dp(36);
        programDots.setX((bandLeft + bandRight) / 2f - w / 2f);
        // Just above the band, clear of the tiles even when one is scaled.
        programDots.setY(top - dp(10) - h);
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    // ---- navigation ------------------------------------------------------------------------------

    /** Up/Down inside the dock row. */
    @Override
    public boolean onDockVerticalKey(int index, int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (adapter.contentCount() > 0) focusTile(1);   // first app/folder row
            return true;                                     // empty grid: stay on the dock
        }
        // Up from the dock goes into the dot strip when there is more than one show.
        if (programDots.getVisibility() == View.VISIBLE && programDots.getChildCount() > 1) {
            int at = Math.min(stage.currentPosterIndex(), programDots.getChildCount() - 1);
            View dot = programDots.getChildAt(at);
            if (dot != null) {
                dot.requestFocus();
                return true;
            }
        }
        return false;                                        // otherwise let focus move to the top bar
    }

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position <= COLUMNS) {                       // first grid row: back to the dock
                focusDock(lastDockIndex);
                return true;
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = 1 + ((count - 2) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;       // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet (cold start): wait for the first layout to produce the holder. The scroll
        // listener below is not enough on its own — on a cold start the grid is usually already at
        // position 0, so no scroll ever starts, it never fires, and the system's own focus search
        // parks the highlight on whatever comes first.
        waitForTile(pos, 0);
    }

    private void waitForTile(final int pos, final int attempt) {
        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Wait for a layout only when there has never been one (cold start). Once the grid is laid
        // out, a missing holder means the tile is off screen — under the dock — and waiting the full
        // two seconds would turn one press of Down into a two-second pause.
        if (attempt < 40 && grid.getChildCount() == 0) {
            grid.postDelayed(() -> waitForTile(pos, attempt + 1), 50);
            return;
        }
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateEmptyHint() {
        if (emptyHint == null) return;
        emptyHint.setVisibility(adapter != null && adapter.contentCount() == 0
                ? View.VISIBLE : View.GONE);
    }

    /** Same three strings TextClock produced, without the API 17 class. */
    private void updateClock() {
        if (clock == null) return;
        long now = System.currentTimeMillis();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(android.text.format.DateFormat.format(h24 ? "HH:mm" : "h:mm", now));
        ampm.setText(h24 ? "" : android.text.format.DateFormat.format("a", now));
        date.setText(android.text.format.DateFormat.format(getString(R.string.date_format), now));
    }

    private static void setBackgroundCompat(View view, Drawable drawable) {
        if (Build.VERSION.SDK_INT >= 16) view.setBackground(drawable);
        else view.setBackgroundDrawable(drawable);
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    /** Switching to a TV input or channel usually lands in PatchWall's own TV window — stay out of its way. */
    private void openTv(Intent i) {
        HomeKeyService.suppressTakeover(20000);
        open(i);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            // Letv's Android 4.3 ActivityManager throws a NullPointerException out of
            // Parcel.readException when the settings intent cannot start, so catching only
            // ActivityNotFoundException let the first-run "go to settings" prompt take the desk
            // down. Any failure to start means the same thing here: nothing to open.
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * China-market MIUI TV firmware answers no standard Settings intent at all — both
     * android.settings.SETTINGS and WIFI_SETTINGS resolve to nothing on those sets — so the Settings
     * and Wi-Fi pills dead-end there. Open Xiaomi's own settings app (com.xiaomi.mitv.settings) instead.
     * On firmware that has a standard handler this returns the standard intent unchanged.
     */
    private Intent systemIntent(String standardAction) {
        return systemIntent(this, standardAction);
    }

    /**
     * A system settings screen that is not always there. China-market TV firmware ignores the
     * standard action, so the vendor's own settings app is tried as well — whose it is comes from
     * {@link DeviceProfile}. See {@code <queries>} in the manifest, without which resolveActivity
     * cannot even see a vendor app on Android 11+.
     */
    static Intent systemIntent(android.content.Context ctx, String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(ctx.getPackageManager()) != null) return i;
        Intent vendor = vendorSettingsIntent(ctx);
        if (vendor != null) return vendor;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /**
     * Whether the package is really on this set. {@code resolveActivity()} answers yes for an
     * explicit component belonging to a package that was never installed — on a Sony BRAVIA the
     * 电视设置 row "resolved" to Xiaomi's settings, the launch went nowhere, and the row read as dead.
     */
    static boolean installed(android.content.Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {   // NameNotFoundException, or SecurityException on 11+
            return false;
        }
    }

    /**
     * The TV vendor's own settings, first: on Xiaomi TVs the ADB switch and 账号与安全 live in
     * com.xiaomi.mitv.settings, while AOSP's Settings app — which the standard action resolves to —
     * only has the bare Android pages. Falls back to AOSP when no vendor app is installed.
     */
    static Intent tvSettingsIntent(android.content.Context ctx) {
        Intent vendor = vendorSettingsIntent(ctx);
        return vendor != null ? vendor : new Intent(Settings.ACTION_SETTINGS);
    }

    /**
     * Picture settings. Xiaomi HyperOS 3 (MiTV-MFFU0, Android 14) has no handler for
     * {@code android.settings.DISPLAY_SETTINGS}, and its own {@code entry.DisplaySoundActivity} is
     * declared in the manifest but missing from the APK — launching it throws ClassNotFoundException
     * in the settings process and lands back on the desk. AOSP TV's display &amp; sound page is the
     * one that actually opens there.
     */
    static Intent pictureIntent(android.content.Context ctx) {
        Intent standard = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
        if (resolvesReal(ctx, standard)) return standard;
        try {
            ctx.getPackageManager().getPackageInfo("com.android.tv.settings", 0);
            return new Intent().setComponent(new ComponentName("com.android.tv.settings",
                    "com.android.tv.settings.device.displaysound.DisplaySoundActivity"));
        } catch (Exception ignored) {
        }
        return tvSettingsIntent(ctx);
    }

    /** Resolved to something other than the AOSP TV stub screens, which are dead ends. */
    static boolean resolvesReal(android.content.Context ctx, Intent intent) {
        ResolveInfo ri = ctx.getPackageManager().resolveActivity(intent, 0);
        return ri != null && ri.activityInfo != null
                && !"com.android.tv.frameworkpackagestubs".equals(ri.activityInfo.packageName);
    }

    /**
     * Launcher entry of the vendor settings app for this profile, or null. MIUI TV's settings app
     * only opens through its explicit main activity; the others are ordinary launch intents.
     */
    /**
     * A vendor settings page, with the ordinary activity intent as the fallback. TCL's settings is
     * an overlay service and its one launcher activity finishes itself a few seconds after starting
     * (see {@link TclSource}), so on those sets the service has to be started instead of an
     * activity. Everywhere else the fallback opens as before. False means nothing opened.
     */
    static boolean openPage(android.content.Context ctx, String tclCategory, Intent fallback) {
        if (TclSource.openSettings(ctx, tclCategory)) return true;
        if (fallback == null) return false;
        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(fallback);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void openPage(String tclCategory, Intent fallback) {
        if (!openPage(this, tclCategory, fallback)) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private static Intent vendorSettingsIntent(android.content.Context ctx) {
        for (String pkg : DeviceProfile.get(ctx).settingsPackages) {
            // TCL's only launchable settings activity starts the overlay service and then
            // finishes; starting it looks like a dead row. TclSource.openSettings is the way in.
            if (TclSource.SETTINGS.equals(pkg)) continue;
            try {
                if (XIAOMI_SETTINGS.equals(pkg)) {
                    Intent i = new Intent().setComponent(new ComponentName(
                            pkg, "com.xiaomi.mitv.settings.entry.MainActivity"));
                    if (i.resolveActivity(ctx.getPackageManager()) != null) return i;
                }
                Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) return i;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** The vendor's screen-cast UI, when it exposes one; null means no row is shown. */
    static Intent castIntent(android.content.Context ctx) {
        for (String pkg : new String[]{"com.xiaomi.mitv.smartshare", "com.miui.cast",
                "com.xiaomi.mitv.cast", "com.tcl.MultiScreenInteraction_TV"}) {
            try {
                Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null && Build.VERSION.SDK_INT >= 21) {
                    i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                }
                if (i != null) return i;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Dropdown menu under the control-centre pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnControl);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        if (Build.VERSION.SDK_INT >= 21) popup.setElevation(0f);

        TvInputs.Listing listing = TvInputs.supported() ? TvInputs.list(this) : null;
        boolean sawNonHdmi = false;
        String tunerId = listing == null ? null : listing.tunerId;
        if (listing != null) {
            for (TvInputs.Item info : listing.items) {
                boolean hdmi = info.hdmi;
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, info.label,
                        info.state, () -> openTv(TvInputs.viewIntent(info.id)));
            }
        }
        Intent liveTv = null;
        if (!liveTvPkg.isEmpty()) {
            if (Build.VERSION.SDK_INT >= 21) {
                liveTv = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
            }
            if (liveTv == null) liveTv = getPackageManager().getLaunchIntentForPackage(liveTvPkg);
            if (liveTv != null && tunerId != null && Build.VERSION.SDK_INT >= 21) {
                liveTv = TvInputs.viewIntent(tunerId).setPackage(liveTvPkg);
            }
        }
        // A vendor panel configured in config.xml, with no Live TV row to sit beside it: the panel is
        // the whole menu. Sets like the BYINTEK U4 report HDMI ports they do not have, and their own
        // picker is the honest one.
        if (!panelConfigured() && liveTv == null) {
            if (rows.getChildCount() == 0 && TclSource.present(this)) {
                // TCL sets report no ports either; its Live TV app publishes the table through its
                // source provider and switches when handed the id.
                java.util.List<TclSource.Item> ports = TclSource.list(this);
                for (TclSource.Item port : ports) {
                    addInputRow(rows, popup, port.hdmi ? R.drawable.ic_input : R.drawable.ic_av,
                            port.label, -1, () -> openTv(TclSource.viewIntent(port.id)));
                }
                if (ports.isEmpty()) {
                    Errors.record("inputs", Build.MANUFACTURER + " " + Build.MODEL
                            + ": TCL source provider listed nothing");
                    Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
                    return;
                }
            } else if (rows.getChildCount() == 0) {
                // Xiaomi sets report no ports at all, but their player can still switch them when
                // asked with the port id. Listing the ports here is what makes "HDMI 1" work for a
                // Switch instead of dumping the user into the vendor panel.
                java.util.List<XiaomiSource.Item> ports = XiaomiSource.list(this);
                for (XiaomiSource.Item port : ports) {
                    addInputRow(rows, popup, port.id == XiaomiSource.AV
                                    ? R.drawable.ic_av : R.drawable.ic_input,
                            port.label, -1, () -> openTv(XiaomiSource.viewIntent(port.id)));
                }
                // The vendor's own source list, last. A port the fixed id table does not cover (an
                // HDMI 4, or a console the firmware only names there) is still one press away.
                if (!ports.isEmpty()) {
                    addInputRow(rows, popup, R.drawable.ic_av,
                            getString(R.string.input_source_settings), -1,
                            () -> openTv(XiaomiSource.panelIntent()));
                }
                if (ports.isEmpty()) {
                    Errors.record("inputs", Build.MANUFACTURER + " " + Build.MODEL
                            + ": no tv inputs, no vendor ports");
                    // Nothing to list either (some Chinese ROMs do not report their ports).
                    Intent own = sourcePanelIntent();
                    if (own == null) {
                        Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    addInputRow(rows, popup, R.drawable.ic_av, getString(R.string.input_source_settings), -1,
                            this::openSourcePanel);
                }
            }
        } else if (panelConfigured() && liveTv == null) {
            openSourcePanel();
            return;
        }
        // Xiaomi's tvplayer declares no launcher category, so the app-level Live TV lookup above
        // finds nothing even when the set has a tuner. Its DTMB page is the live TV screen.
        if (liveTv == null && tunerId != null) liveTv = XiaomiSource.liveTvIntent(this);
        // TCL's Live TV app has no launcher category either; its tuner source id is the page.
        if (liveTv == null) liveTv = TclSource.liveTvIntent(this);
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> openTv(liveTvIntent));
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        dropIn(popup, panel, anchor, Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    /**
     * A glass dropdown built the way the Inputs menu is: as wide as its widest row but never most of
     * the screen, and no taller than the room under the button, so a long list scrolls inside the
     * panel instead of running off the bottom of the TV.
     */
    private PopupWindow glassPanel(View panel, View anchor, int minWidthDp) {
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int width = Math.max(Math.max(anchor.getWidth(), (int) (minWidthDp * density)),
                panel.getMeasuredWidth());
        width = Math.min(width, (int) (getResources().getDisplayMetrics().widthPixels * 0.72f));
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        int height = Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density)));
        PopupWindow popup = new PopupWindow(panel, width, height, true);
        // Focusable alone is not enough: without a background some firmwares send Back nowhere,
        // which is exactly how a dropdown turns into a panel you cannot leave.
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        if (Build.VERSION.SDK_INT >= 21) popup.setElevation(12 * density);
        return popup;
    }

    /**
     * Dropdown under the pill. The desk behind it stays sharp — the frost lives in the card itself,
     * sampled from the desk pixels it covers, so it reads as glass without turning the whole screen
     * into a blurred screenshot. The window is still full-screen so a tap anywhere can close it.
     */
    private PopupWindow floatingOver(View content, View anchor, int minWidthDp) {
        return floatingOver(content, anchor, minWidthDp, 0, 0);
    }

    private PopupWindow floatingOver(View content, View anchor, int minWidthDp,
                                     int tintTop, int tintBottom) {
        // A notice mid-flight would be baked into the glass behind the panel; the dot keeps the state.
        if (updateBanner != null) updateBanner.setVisibility(View.GONE);
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(Math.max(anchor.getWidth(), (int) (minWidthDp * density)),
                (int) (screenW * 0.72f));

        FrameLayout frame = new FrameLayout(this);

        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int top = at[1] + anchor.getHeight() + (int) (10 * density);
        int right = Math.max(0, screenW - at[0] - anchor.getWidth());
        int room = screenH - top - (int) (22 * density);
        int height = Math.min(content.getMeasuredHeight(), Math.max(room, (int) (120 * density)));

        if (tintTop != 0) {
            Bitmap crop = deskCrop(screenW - right - width, top, width, height);
            content.setBackground(new FrostDrawable(crop, 22 * density, tintTop, tintBottom));
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height,
                Gravity.TOP | Gravity.END);
        lp.topMargin = top;
        lp.rightMargin = right;
        frame.addView(content, lp);

        final PopupWindow popup = new PopupWindow(frame,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        // Taps land "inside" a full-screen popup, so outside-touch never fires; the scrim is the
        // thing that closes it instead. The scrim must not take focus itself, or the D-pad lands on
        // it and the rows inside never get the highlight.
        frame.setFocusable(false);
        frame.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        frame.setOnClickListener(v -> popup.dismiss());
        return popup;
    }

    /** The desk is sampled at a sixth of its size; glass only ever sees this blurred copy. */
    private static final int BLUR_SCALE = 6;

    private Bitmap blurredDesk() {
        try {
            int w = Math.max(1, root.getWidth() / BLUR_SCALE);
            int h = Math.max(1, root.getHeight() / BLUR_SCALE);
            Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(small);
            canvas.scale(1f / BLUR_SCALE, 1f / BLUR_SCALE);
            root.draw(canvas);
            boxBlur(small, 5);
            return small;
        } catch (Throwable t) {
            // A firmware that refuses to draw the frame just gets the tint without the blur.
            return null;
        }
    }

    /** The part of the blurred desk that sits under a panel, in panel pixel coordinates. */
    private Bitmap deskCrop(int left, int top, int width, int height) {
        Bitmap back = blurredDesk();
        if (back == null || width <= 0 || height <= 0) return null;
        try {
            int x = Math.max(0, left / BLUR_SCALE);
            int y = Math.max(0, top / BLUR_SCALE);
            int w = Math.min(back.getWidth() - x, Math.max(1, width / BLUR_SCALE));
            int h = Math.min(back.getHeight() - y, Math.max(1, height / BLUR_SCALE));
            return Bitmap.createBitmap(back, x, y, w, h);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A panel's own pane: the blurred desk pixels it covers, a tint over them, and a hairline rim.
     * Drawing the shader through a round rect keeps the corners antialiased on every API level,
     * where clipPath would leave them ragged.
     */
    private static final class FrostDrawable extends Drawable {

        private final Paint pane = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius;
        private final float stroke;
        private final int tintTop, tintBottom;
        private final BitmapShader shader;
        private final int cropW, cropH;

        FrostDrawable(Bitmap crop, float radius, int tintTop, int tintBottom) {
            this.radius = radius;
            this.tintTop = tintTop;
            this.tintBottom = tintBottom;
            stroke = Math.max(1f, radius / 16f);
            if (crop != null) {
                cropW = crop.getWidth();
                cropH = crop.getHeight();
                shader = new BitmapShader(crop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                pane.setShader(shader);
            } else {
                cropW = cropH = 0;
                shader = null;
            }
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(stroke);
            rim.setColor(0x33000000);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            rect.set(getBounds());
            if (shader != null) {
                Matrix scale = new Matrix();
                scale.setScale(rect.width() / cropW, rect.height() / cropH);
                shader.setLocalMatrix(scale);
                canvas.drawRoundRect(rect, radius, radius, pane);
            }
            tint.setShader(new LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                    tintTop, tintBottom, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, tint);
            rect.inset(stroke / 2f, stroke / 2f);
            canvas.drawRoundRect(rect, radius, radius, rim);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static void boxBlur(Bitmap bmp, int radius) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] src = new int[w * h], dst = new int[w * h];
        bmp.getPixels(src, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            slide(src, dst, w, h, radius, true);
            slide(dst, src, w, h, radius, false);
        }
        bmp.setPixels(src, 0, w, 0, 0, w, h);
    }

    /** One separable box pass, horizontal or vertical, straight average into dst. */
    private static void slide(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int step = horizontal ? 1 : w;
        for (int line = 0; line < lines; line++) {
            int start = horizontal ? line * w : line;
            for (int i = 0; i < len; i++) {
                int from = Math.max(0, i - r), to = Math.min(len - 1, i + r);
                int a = 0, rr = 0, g = 0, b = 0, n = to - from + 1;
                for (int j = from; j <= to; j++) {
                    int p = src[start + j * step];
                    a += p >>> 24;
                    rr += (p >> 16) & 0xFF;
                    g += (p >> 8) & 0xFF;
                    b += p & 0xFF;
                }
                dst[start + i * step] = (a / n) << 24 | (rr / n) << 16 | (g / n) << 8 | (b / n);
            }
        }
    }

    /** Drop down from under the pill. The content is placed by the full-screen popup's layout, so
     *  this only slides and fades it; the desk around it never moves or dims. */
    private void riseIn(PopupWindow popup, View panel, View anchor) {
        if (!reducedMotion) {
            panel.setAlpha(0f);
            panel.setTranslationY(-20 * density);
        }
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, 0, 0);
        if (reducedMotion) return;
        panel.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        if (reducedMotion) {
            // Weak boxes: the unfold costs frames the user notices as lag, so just put it on screen.
            showUnder(popup, anchor, gravity);
            return;
        }
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        showUnder(popup, anchor, gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    /** The four-argument showAsDropDown arrived in 19; older boxes get the offset worked out here. */
    private void showUnder(PopupWindow popup, View anchor, int gravity) {
        int y = (int) (10 * density);
        if (Build.VERSION.SDK_INT >= 19) {
            popup.showAsDropDown(anchor, 0, y, gravity);
            return;
        }
        int width = popup.getWidth() > 0 ? popup.getWidth() : popup.getContentView().getMeasuredWidth();
        int x = gravity == Gravity.END ? anchor.getWidth() - width : 0;
        popup.showAsDropDown(anchor, x, y);
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- update and feedback ---------------------------------------------------------------------

    /**
     * The update prompt, centred the way the TV's own dialogs are. The desk keeps running behind it;
     * Back or "Not now" dismisses, and "Not now" remembers the version so it does not ask again until
     * the next release.
     */
    /**
     * The boot-time check, retried a couple of times when it fails. On a cold start the desk is up
     * before the TV's Wi-Fi has associated, so the first fetch dies with ENETUNREACH — 10 devices
     * reported exactly that while their heartbeats, 3 s later and every 5 min, got through. Waiting
     * for the next boot to ask again is what left them on old builds.
     */
    private void checkUpdate(final int attempt) {
        Remote.checkUpdate(this, update -> {
            if (!isFinishing()) notifyUpdate(update);
        }, (ok, message) -> {
            if (ok || attempt >= 3 || isFinishing()) return;
            handler.postDelayed(() -> checkUpdate(attempt + 1), attempt == 0 ? 20000L : 120000L);
        });
    }

    /** The top-bar pill: an on-demand check instead of waiting for the next boot. */
    private void checkUpdates() {
        toast(getString(R.string.update_checking));
        Remote.checkUpdateNow(this, update -> {
            if (!isFinishing()) showUpdate(update);
        }, (ok, message) -> {
            // Asked by hand and nothing newer: a dot left from an older check is stale.
            if (ok) clearUpdateUnread();
            toast(getString(ok
                    ? R.string.update_latest : R.string.update_check_failed,
                    Remote.versionName(this)));
        });
    }

    // ---- update notice ---------------------------------------------------------------------------

    /** A published build newer than this one that the user has not opened yet. */
    private boolean updateUnread() {
        int unread = getSharedPreferences("update", MODE_PRIVATE).getInt("unread", 0);
        return unread > Remote.versionCode(this);
    }

    private void refreshUpdateBadge() {
        if (updateDot != null) updateDot.setVisibility(updateUnread() ? View.VISIBLE : View.GONE);
    }

    private void clearUpdateUnread() {
        getSharedPreferences("update", MODE_PRIVATE).edit().remove("unread").apply();
        refreshUpdateBadge();
    }

    /**
     * A background check found a build: badge it and, if the desk is in front and nothing is open,
     * say so once with the small banner. Never a dialog — the user is in the middle of something.
     */
    private void notifyUpdate(Remote.Update update) {
        pendingUpdate = update;
        getSharedPreferences("update", MODE_PRIVATE).edit().putInt("unread", update.code).apply();
        refreshUpdateBadge();
        boolean clear = hasWindowFocus() && bannerShownFor != update.code
                && (controlPanel == null || !controlPanel.isShowing())
                && (updatePanel == null || !updatePanel.isShowing());
        if (clear) showUpdateBanner(update);
    }

    private void showUpdateBanner(Remote.Update update) {
        if (updateBanner == null) return;
        bannerShownFor = update.code;
        placeUpdateBanner();
        updateBanner.setVisibility(View.VISIBLE);
        updateBanner.setAlpha(0f);
        updateBanner.setTranslationY(-dp(8));
        updateBanner.animate().alpha(1f).translationY(0f).setDuration(220).start();
        handler.removeCallbacks(hideBanner);
        handler.postDelayed(hideBanner, 6000);
    }

    /** Under the control pill, flush with its right edge, wherever that ended up on this screen. */
    private void placeUpdateBanner() {
        View anchor = findViewById(R.id.btnControl);
        if (anchor == null || updateBanner == null) return;
        ViewGroup.LayoutParams raw = updateBanner.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = at[1] + anchor.getHeight() + dp(10);
        lp.rightMargin = Math.max(dp(16), getResources().getDisplayMetrics().widthPixels
                - at[0] - anchor.getWidth());
        updateBanner.setLayoutParams(lp);
    }

    private void hideUpdateBanner() {
        handler.removeCallbacks(hideBanner);
        if (updateBanner == null || updateBanner.getVisibility() != View.VISIBLE) return;
        updateBanner.animate().alpha(0f).translationY(-dp(8)).setDuration(160)
                .withEndAction(() -> updateBanner.setVisibility(View.GONE)).start();
    }

    /** The banner or the dot asked for the details: reuse the normal update panel, no new fetch. */
    private void openPendingUpdate() {
        if (pendingUpdate != null && !isFinishing()) showUpdate(pendingUpdate);
        else checkUpdates();
    }

    /**
     * Tell the console what this TV looks like to the launcher, once per install. A brand nobody
     * has seen yet is described by its own probe results here, so its profile can be written
     * without its owner finding an ADB switch first.
     */
    private void reportCapabilities() {
        android.content.SharedPreferences prefs = getSharedPreferences("desk", MODE_PRIVATE);
        if (prefs.getInt("caps_reported", 0) >= BuildConfig.VERSION_CODE) return;
        prefs.edit().putInt("caps_reported", BuildConfig.VERSION_CODE).apply();
        Errors.record("caps", DeviceProfile.get(this).describe()
                + " sdk=" + android.os.Build.VERSION.SDK_INT
                + " device=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
    }

    /** Whether the desk's accessibility service is in the system's enabled list. */
    private boolean accessibilityEnabled() {
        String list;
        try {
            list = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (Exception e) {
            return true;   // unreadable: never nag on a reading failure
        }
        if (list == null) return false;
        return list.contains(getPackageName() + "/" + HomeKeyService.class.getName())
                || list.contains(getPackageName() + "/.HomeKeyService");
    }

    /**
     * Replacing the package makes Android switch its accessibility service off, so every update
     * quietly costs the Home-key takeover. Remember that it was on; when it goes away, say so and
     * point at the settings page rather than letting people think the desk is broken. A fresh
     * install has never had the service on, and needs the same pointer: without it Home keeps going
     * to PatchWall, which reads as "the desk did not start".
     *
     * <p>Once-ever is only enough for someone who never had the takeover. Someone who had it loses
     * Home after every update, and if they tapped "later" last time they would never hear of it
     * again — so for them the hint returns on the next launch, at most once per run.
     */
    private void checkAccessibility() {
        android.content.SharedPreferences prefs = getSharedPreferences("desk", MODE_PRIVATE);
        if (accessibilityEnabled()) {
            prefs.edit().putBoolean("a11y_was_on", true).putBoolean("a11y_prompted", false)
                    .putBoolean("a11y_jumped", false).apply();
            return;
        }
        if (a11yPromptedThisRun) return;
        boolean wasOn = prefs.getBoolean("a11y_was_on", false);
        if (!wasOn && prefs.getBoolean("a11y_prompted", false)) return;
        a11yPromptedThisRun = true;
        prefs.edit().putBoolean("a11y_prompted", true).apply();
        if (!wasOn) {
            showAccessibilityLost(false);
            return;
        }
        Errors.record("a11y", "accessibility service turned off");
        // Every update turns the service off. Go straight to the switch once instead of asking;
        // a second jump would yank the owner out of whatever they opened the desk for, so after
        // that the popup returns and they can pick "later" without being overruled.
        if (!prefs.getBoolean("a11y_jumped", false)
                && DeviceProfile.get(this).accessibilitySettings) {
            prefs.edit().putBoolean("a11y_jumped", true).apply();
            open(systemIntent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        showAccessibilityLost(true);
    }

    private void showAccessibilityLost(boolean wasOn) {
        if (!DeviceProfile.get(this).accessibilitySettings) {
            showAccessibilityGuide();
            return;
        }
        if (isFinishing() || (updatePanel != null && updatePanel.isShowing())) return;
        // onResume runs before the decor window is attached; a popup shown right there has no token
        // and WindowManager kills the activity (BadTokenException on API 23). Wait one turn.
        if (root.getWindowToken() == null) {
            root.post(() -> showAccessibilityLost(wasOn));
            return;
        }
        View panel = getLayoutInflater().inflate(R.layout.popup_update, root, false);
        ((TextView) panel.findViewById(R.id.updateTitle))
                .setText(wasOn ? R.string.a11y_lost_title : R.string.a11y_first_title);
        ((TextView) panel.findViewById(R.id.updateNotes))
                .setText(wasOn ? R.string.a11y_lost_notes : R.string.a11y_first_notes);
        panel.findViewById(R.id.updateStatus).setVisibility(View.GONE);
        TextView go = panel.findViewById(R.id.updateGo);
        TextView later = panel.findViewById(R.id.updateLater);
        go.setText(R.string.a11y_lost_go);
        later.setText(R.string.update_later);

        final PopupWindow popup = new PopupWindow(panel,
                Math.min(dp(520), (int) (getResources().getDisplayMetrics().widthPixels * 0.8f)),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        go.setOnClickListener(v -> {
            popup.dismiss();
            open(systemIntent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        later.setOnClickListener(v -> popup.dismiss());
        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        panel.post(go::requestFocus);
    }

    /**
     * The set has no Accessibility page (Skyworth 8R710, see {@link DeviceProfile}), so the usual
     * "turn it on" button would open a dead AOSP stub. Show the adb lines instead — someone at the
     * TV reads them off and runs them once from a computer.
     */
    private void showAccessibilityGuide() {
        if (isFinishing() || (updatePanel != null && updatePanel.isShowing())) return;
        if (root.getWindowToken() == null) {
            root.post(this::showAccessibilityGuide);
            return;
        }
        View panel = getLayoutInflater().inflate(R.layout.popup_update, root, false);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(R.string.a11y_guide_title);
        TextView notes = panel.findViewById(R.id.updateNotes);
        notes.setText(getString(R.string.a11y_guide_notes, getPackageName()));
        notes.setMaxLines(Integer.MAX_VALUE);
        notes.setEllipsize(null);
        panel.findViewById(R.id.updateStatus).setVisibility(View.GONE);
        TextView go = panel.findViewById(R.id.updateGo);
        go.setText(R.string.a11y_guide_ok);
        panel.findViewById(R.id.updateLater).setVisibility(View.GONE);

        final PopupWindow popup = new PopupWindow(panel,
                Math.min(dp(560), (int) (getResources().getDisplayMetrics().widthPixels * 0.82f)),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        go.setOnClickListener(v -> popup.dismiss());
        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        panel.post(go::requestFocus);
    }

    private void showUpdate(final Remote.Update update) {
        // Seeing the details is what "reads" the notice; the dot and the banner go away.
        pendingUpdate = update;
        clearUpdateUnread();
        hideUpdateBanner();
        String notes = update.notes.isEmpty() ? getString(R.string.update_no_notes) : update.notes;
        showInstall(update, getString(R.string.update_title, update.name), notes, false);
    }

    /** Ask the server for the published stable build, then reuse the normal update dialog for it. */
    private void switchToStable() {
        toast(getString(R.string.stable_checking));
        Remote.checkStable(update -> {
            if (isFinishing()) return;
            // Same package, so the installer refuses anything older than the running Beta.
            if (update.code < BuildConfig.VERSION_CODE) {
                toast(getString(R.string.stable_older, update.name));
                return;
            }
            update.notes = getString(R.string.stable_notes);
            showInstall(update, getString(R.string.stable_title), update.notes, true);
        }, (ok, message) -> toast(getString(R.string.stable_unavailable)));
    }

    private void showInstall(final Remote.Update update, final CharSequence title,
                             final CharSequence notesText, final boolean switching) {
        if (isFinishing() || (updatePanel != null && updatePanel.isShowing())) return;
        if (root.getWindowToken() == null) {
            root.post(() -> showInstall(update, title, notesText, switching));
            return;
        }
        View panel = getLayoutInflater().inflate(R.layout.popup_update, root, false);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(title);
        TextView notes = panel.findViewById(R.id.updateNotes);
        notes.setText(notesText);
        final TextView status = panel.findViewById(R.id.updateStatus);
        String size = update.size > 0
                ? android.text.format.Formatter.formatShortFileSize(this, update.size) : "";
        status.setText(getString(R.string.update_meta, Remote.versionName(this), size));
        final TextView go = panel.findViewById(R.id.updateGo);
        TextView later = panel.findViewById(R.id.updateLater);

        final PopupWindow popup = new PopupWindow(panel,
                Math.min(dp(520), (int) (getResources().getDisplayMetrics().widthPixels * 0.8f)),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        // A focused popup needs a background to treat Back and outside taps as dismiss.
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(() -> updatePanel = null);
        updatePanel = popup;

        go.setOnClickListener(v -> {
            go.setEnabled(false);
            status.setText(R.string.update_preparing);
            Remote.download(this, update,
                    pct -> status.setText(getString(R.string.update_downloading, pct)),
                    (ok, path) -> {
                        if (!ok) {
                            go.setEnabled(true);
                            status.setText(R.string.update_download_failed);
                            return;
                        }
                        popup.dismiss();
                        final java.io.File apk = new java.io.File(path);
                        // Android 6 MIUI denies external-storage writes until this is granted, and
                        // the file:// install path needs somewhere the installer can read.
                        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 24
                                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                != PackageManager.PERMISSION_GRANTED) {
                            pendingInstall = apk;
                            requestPermissions(new String[]{
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                    REQ_INSTALL_STORAGE);
                            return;
                        }
                        if (!Remote.canSelfInstall(MainActivity.this)) {
                            showManualInstall(update, apk);
                        } else if (!Remote.install(MainActivity.this, apk)) {
                            toast(getString(R.string.not_available));
                        } else if (switching) {
                            toast(getString(R.string.stable_started));
                        }
                    });
        });
        later.setOnClickListener(v -> {
            // Skipping a channel switch must not silence the next beta update: skip is per versionCode.
            if (!switching) {
                getSharedPreferences("update", MODE_PRIVATE).edit()
                        .putString("skip", String.valueOf(update.code)).apply();
            }
            popup.dismiss();
        });

        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        panel.post(go::requestFocus);
    }

    /**
     * The ROM's installer cannot be started by an app (see {@link Remote#canSelfInstall}), so park
     * the APK in Downloads and tell the user to install it with the firmware's file manager.
     */
    private void showManualInstall(final Remote.Update update, final java.io.File apk) {
        String name = "tvhome-"
                + (update.name.isEmpty() ? String.valueOf(update.code) : update.name) + ".apk";
        String saved = Remote.exportToDownloads(this, apk, name);
        if (saved == null) {
            toast(getString(R.string.update_manual_failed));
            return;
        }
        showManualSaved(saved);
    }

    private void showManualSaved(final String saved) {
        if (isFinishing() || (updatePanel != null && updatePanel.isShowing())) return;
        if (root.getWindowToken() == null) {
            root.post(() -> showManualSaved(saved));
            return;
        }
        View panel = getLayoutInflater().inflate(R.layout.popup_update, root, false);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(R.string.update_manual_title);
        TextView notes = panel.findViewById(R.id.updateNotes);
        notes.setText(getString(R.string.update_manual_notes, saved));
        notes.setMaxLines(Integer.MAX_VALUE);
        notes.setEllipsize(null);
        panel.findViewById(R.id.updateStatus).setVisibility(View.GONE);
        TextView go = panel.findViewById(R.id.updateGo);
        go.setText(R.string.update_manual_ok);
        panel.findViewById(R.id.updateLater).setVisibility(View.GONE);

        final PopupWindow popup = new PopupWindow(panel,
                Math.min(dp(560), (int) (getResources().getDisplayMetrics().widthPixels * 0.82f)),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        go.setOnClickListener(v -> popup.dismiss());
        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        panel.post(go::requestFocus);
    }

    /** One tap sends device info, the version and a logcat tail to the console; nothing to type. */
    private void sendFeedback() {
        toast(getString(R.string.feedback_sending));
        Remote.sendReport(this, "feedback", "", "",
                (ok, message) -> toast(getString(
                        ok ? R.string.feedback_sent : R.string.feedback_failed)));
    }

    // ---- control centre --------------------------------------------------------------------------

    /** A top-bar pill: bare glyph at rest, frosted square and full brightness while focused. */
    private void stylePill(final View pill, final View glass, View.OnKeyListener keys) {
        if (pill == null) return;
        pill.setOnKeyListener(keys);
        pill.setAlpha(0.8f);
        pill.setOnFocusChangeListener((v, hasFocus) -> {
            pill.setAlpha(hasFocus ? 1f : 0.8f);
            if (glass == null) return;
            glass.animate().cancel();
            if (hasFocus) {
                glass.setScaleX(0.92f);
                glass.setScaleY(0.92f);
                if (reducedMotion) {
                    glass.setAlpha(1f);
                    glass.setScaleX(1f);
                    glass.setScaleY(1f);
                } else {
                    glass.animate().alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(200)
                            .setInterpolator(new OvershootInterpolator(1.4f)).start();
                }
            } else if (reducedMotion) {
                glass.setAlpha(0f);
            } else {
                glass.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f)
                        .setDuration(140).start();
            }
        });
    }

    /**
     * The macOS-style panel: network and Bluetooth switches, the volume slider, and the
     * three places this remote otherwise buries (inputs, TV settings, casting).
     */
    private void showControlCenter() {
        if (controlPanel != null && controlPanel.isShowing()) {
            controlPanel.dismiss();
            return;
        }
        View panel = getLayoutInflater().inflate(R.layout.popup_control, root, false);
        LinearLayout tiles = panel.findViewById(R.id.tiles);
        LinearLayout rows = panel.findViewById(R.id.rows);
        LinearLayout sliders = panel.findViewById(R.id.sliders);
        LinearLayout shortcuts = panel.findViewById(R.id.shortcuts);
        GlideBackdrop tilesGlide = panel.findViewById(R.id.tilesGlide);
        tilesGlide.setOval();
        tilesGlide.setLight();

        // The four places people actually go, as round glass tiles. These used to be three separate
        // pills in the top bar; one glass entry that opens this sheet is the tvOS arrangement.
        // Wi-Fi is its own pill beside this one, so it is not repeated here.
        addTile(tiles, R.drawable.ic_input, getString(R.string.inputs), this::showInputs);
        addTile(tiles, R.drawable.ic_wallpaper, getString(R.string.wallpaper), this::showWallpaperPicker);
        addTile(tiles, R.drawable.ic_dock, getString(R.string.favourites), this::showDockPicker);
        addTile(tiles, R.drawable.ic_settings, getString(R.string.tv_settings),
                () -> openPage("Main", tvSettingsIntent(this)));

        // Bluetooth opens a detail sheet instead of flipping the radio in place: on a TV remote a
        // stray OK used to take the whole house off Bluetooth.
        if (bluetoothAdapter() != null) {
            addControlRow(rows, R.drawable.ic_bluetooth, getString(R.string.bluetooth),
                    bluetoothSubtitle(), () -> {
                        if (controlPanel != null) controlPanel.dismiss();
                        showBluetoothDetails();
                    }, true);
        }

        // Dock size: the count row opens a chooser. The old pair of +/− pills sat below the volume
        // slider where the remote did not naturally reach, and stepping 1..8 one press at a time
        // was the wrong shape for a value with eight choices.
        addControlRow(rows, R.drawable.ic_dock, getString(R.string.dock_size_title),
                getResources().getQuantityString(R.plurals.dock_size_value, adapter.dockSize(), adapter.dockSize()),
                () -> {
                    if (controlPanel != null) controlPanel.dismiss();
                    root.post(this::showDockSizePicker);
                });

        addControlRow(rows, R.drawable.ic_apps, getString(R.string.running_title),
                getString(R.string.running_subtitle), () -> {
                    if (controlPanel != null) controlPanel.dismiss();
                    // Showing the next window from inside the previous one's dismiss leaves the new
                    // popup without input focus: its rows never take the D-pad. One frame later is enough.
                    root.post(this::showRunningPanel);
                });

        // The vendor already owns a cleaner that is allowed to touch other apps' caches; hand the
        // job to it instead of pretending a third-party desk can clean anything itself.
        addControlRow(rows, R.drawable.ic_clean, getString(R.string.clean_title),
                getString(R.string.clean_subtitle), () -> {
                    if (controlPanel != null) controlPanel.dismiss();
                    root.post(() -> {
                        if (!Cleaner.open(this)) toast(getString(R.string.clean_failed));
                    });
                });

        // Volume only: the panel backlight on this class of TV is driven by the vendor's own chip
        // service, so SCREEN_BRIGHTNESS writes move the setting without changing the picture.
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            addSlider(sliders, R.drawable.ic_volume, getString(R.string.volume), max, current);
        }

        // Two rows of two: four across squeezed the labels down to "检…" on a 720p set.
        LinearLayout pillRowOne = addPillRow(shortcuts);
        Intent cast = castIntent(this);
        if (cast != null) {
            addShortcutPill(pillRowOne, R.drawable.ic_cast, getString(R.string.quick_cast),
                    () -> open(cast));
        }
        // With a build already found, this opens its details straight away; otherwise it is a check.
        addShortcutPill(pillRowOne, R.drawable.ic_update, getString(R.string.check_update),
                this::openPendingUpdate, updateUnread());
        LinearLayout pillRowTwo = addPillRow(shortcuts);
        // Only a beta install carries the way back to stable; the stable desk never needs it.
        if (BuildConfig.CHANNEL.equals("beta")) {
            addShortcutPill(pillRowTwo, R.drawable.ic_update, getString(R.string.stable_switch),
                    this::switchToStable);
        }
        addShortcutPill(pillRowTwo, R.drawable.ic_send, getString(R.string.feedback_send),
                this::sendFeedback);

        // The items go transparent and hand their disc to the backdrop; the backdrop also draws
        // the selection, so focus can travel instead of popping between items.
        List<View> tileIcons = new ArrayList<>(tiles.getChildCount());
        for (int i = 0; i < tiles.getChildCount(); i++) {
            View tile = tiles.getChildAt(i);
            tile.setTag(tilesGlide);
            ImageView icon = tile.findViewById(R.id.icon);
            icon.setBackground(null);
            tileIcons.add(icon);
        }
        watchGlide((ViewGroup) tiles.getParent(), tilesGlide, tileIcons);
        for (int i = 0; i < shortcuts.getChildCount(); i++) {
            FrameLayout box = (FrameLayout) shortcuts.getChildAt(i);
            LinearLayout row = (LinearLayout) box.getChildAt(1);
            GlideBackdrop glide = (GlideBackdrop) row.getTag();
            List<View> items = new ArrayList<>(row.getChildCount());
            boolean pair = row.getChildCount() > 1;
            for (int j = 0; j < row.getChildCount(); j++) {
                View pill = row.getChildAt(j);
                pill.setBackground(null);
                pill.setTag(glide);
                if (pair) {
                    // Two pills share the row evenly; a lone pill keeps its natural width instead
                    // of stretching across the whole sheet.
                    LinearLayout.LayoutParams lp =
                            (LinearLayout.LayoutParams) pill.getLayoutParams();
                    lp.width = 0;
                    lp.weight = 1f;
                    pill.setLayoutParams(lp);
                }
                items.add(pill);
            }
            watchGlide(box, glide, items);
        }

        View anchor = findViewById(R.id.btnControl);
        controlPanel = floatingOver(panel, anchor, 340, 0x73FFFFFF, 0x59FFFFFF);
        riseIn(controlPanel, panel, anchor);
        panel.post(() -> {
            if (tiles.getChildCount() > 0) tiles.getChildAt(0).requestFocus();
            else if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
            else if (shortcuts.getChildCount() > 1) shortcuts.getChildAt(1).requestFocus();
        });
    }

    /**
     * Keep [glide]'s slots in step with [items], expressed in [box]'s coordinates. Layout rather
     * than post(): the panel has not been measured when the sheet is built, and the rows change
     * height once subtitles arrive.
     */
    private void watchGlide(ViewGroup box, GlideBackdrop glide, List<View> items) {
        box.addOnLayoutChangeListener((v, left, top, right, bottom,
                                       oldLeft, oldTop, oldRight, oldBottom) -> {
            List<RectF> rects = new ArrayList<>(items.size());
            Rect at = new Rect();
            for (View item : items) {
                if (item.getWidth() == 0) return;   // not laid out yet; the next pass will do
                at.set(0, 0, item.getWidth(), item.getHeight());
                box.offsetDescendantRectToMyCoords(item, at);
                rects.add(new RectF(at));
            }
            glide.setSlots(rects);
        });
    }

    /**
     * Report an item's focus to its group's backdrop, if the control centre tagged it with one.
     * Tags keep the shared row and pill builders free of control-centre arguments: the network
     * and Bluetooth sheets use the same row layout and must not draw a sliding selection.
     */
    private void glideFocus(View item, boolean hasFocus, ViewGroup group) {
        Object tag = item.getTag();
        if (!(tag instanceof GlideBackdrop)) return;
        GlideBackdrop glide = (GlideBackdrop) tag;
        int at = group.indexOfChild(item);
        if (hasFocus) glide.focus(at, !reducedMotion);
        else if (glide.focused() == at) glide.unfocus();
    }

    /** A wide pill ending in a detail sheet: glyph in a disc, title, one line of state. */
    private View addControlRow(LinearLayout rows, int iconRes, String title, String subtitle,
                               Runnable action) {
        return addControlRow(rows, iconRes, title, subtitle, action, false);
    }

    /** [light] rows use the Wi-Fi sheet's language: grey wash on focus, ink text on the pane. */
    private View addControlRow(LinearLayout rows, int iconRes, String title, String subtitle,
                               Runnable action, boolean light) {
        View row = getLayoutInflater().inflate(R.layout.item_control_row, rows, false);
        ImageView icon = row.findViewById(R.id.icon);
        icon.setImageResource(iconRes);
        TextView titleView = row.findViewById(R.id.title);
        titleView.setText(title);
        TextView sub = row.findViewById(R.id.subtitle);
        if (subtitle == null || subtitle.isEmpty()) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setText(subtitle);
        }
        ImageView chevron = row.findViewById(R.id.chevron);
        if (light) {
            row.setBackgroundResource(R.drawable.wifi_row_bg);
            row.findViewById(R.id.iconDisc).setBackgroundResource(R.drawable.wifi_disc);
            icon.setColorFilter(0xFF111114, PorterDuff.Mode.SRC_IN);
            titleView.setTextColor(0xFF111114);
            sub.setTextColor(0xFF6E6E73);
            chevron.setColorFilter(0xFF9A9AA0, PorterDuff.Mode.SRC_IN);
        } else {
            icon.setColorFilter(0xFF1A1A1A, PorterDuff.Mode.SRC_IN);
        }
        if (action == null) {
            // A plain fact line: the remote skips it and there is nowhere to go.
            row.setFocusable(false);
            row.setClickable(false);
            chevron.setVisibility(View.GONE);
        } else {
            if (!light) chevron.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
            row.setOnFocusChangeListener((v, hasFocus) -> {
                if (!light) {
                    chevron.setColorFilter(hasFocus ? 0xFF111111 : 0xFFFFFFFF,
                            PorterDuff.Mode.SRC_IN);
                }
                glideFocus(v, hasFocus, rows);
            });
            row.setOnClickListener(v -> action.run());
        }
        rows.addView(row);
        return row;
    }

    /** A light row that closes the panel and goes somewhere. */
    private void addLightShortcutRow(LinearLayout rows, int iconRes, String title, Runnable action) {
        addControlRow(rows, iconRes, title, null, () -> {
            if (controlPanel != null) controlPanel.dismiss();
            action.run();
        }, true);
    }

    private static String ssidOf(WifiInfo info) {
        if (info == null) return null;
        String ssid = info.getSSID();
        if (ssid == null) return null;
        ssid = ssid.replace("\"", "").trim();
        if (ssid.isEmpty() || ssid.contains("unknown") || "0x".equals(ssid)) return null;
        return ssid;
    }

    /** The light sheet on screen, so a scan that lands late can refill it instead of closing it. */
    private LinearLayout wifiRows;
    private ToggleView wifiToggle;
    private View wifiSheet;

    /**
     * Wi-Fi sheet: what is connected, personal hotspots, saved networks, everything else. Scanning
     * needs the location grant on Android 6 and up; without it the sheet still lists the saved
     * networks and offers the system page, because a TV in a wall does not always get asked nicely.
     */
    private void showNetworkDetails() {
        final WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            toast(getString(R.string.not_available));
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            wifiPanelAfterGrant = true;
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_WIFI_SCAN);
            return;
        }
        boolean empty = scanResults(wm).isEmpty();
        buildWifiPanel(wm);
        try {
            wm.startScan();
        } catch (Exception ignored) {
        }
        handler.removeCallbacks(wifiRescan);
        // Redraw once the radio has answered, but only on a first open where there was nothing
        // to show: reopening the sheet under someone who already moved focus is the worse trade.
        if (empty) handler.postDelayed(wifiRescan, 2000);
    }

    private final Runnable wifiRescan = () -> {
        // Refill the rows in place: dismissing and rebuilding would close the sheet and lose focus.
        if (detailPanel == null || !detailPanel.isShowing()) return;
        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null || wifiRows == null) return;
        boolean onSwitch = wifiToggle != null && wifiToggle.isFocused();
        fillWifiRows(wm, wifiRows);
        if (wifiToggle != null) wifiToggle.setOn(wifiEnabled(wm));
        // Replacing the rows drops whatever row had focus; put it back where the user was.
        if (onSwitch) {
            if (wifiToggle != null) wifiToggle.requestFocus();
        } else {
            View first = firstFocusable(wifiRows);
            if (first != null) first.requestFocus();
        }
    };

    /** Visible networks, one entry per SSID (the strongest/duplicated rows say nothing new). */
    @SuppressLint("MissingPermission")
    private List<ScanResult> scanResults(WifiManager wm) {
        List<ScanResult> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            for (ScanResult r : wm.getScanResults()) {
                if (r == null || r.SSID == null || r.SSID.isEmpty()) continue;
                if (seen.add(r.SSID)) out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * A light frosted pane pinned under the Wi-Fi pill: switch in the header, then hotspots, saved
     * networks and everything else, each row a glyph disc, a name and a lock or signal bars.
     */
    private void buildWifiPanel(final WifiManager wm) {
        if (detailPanel != null && detailPanel.isShowing()) {
            detailPanel.dismiss();
            return;
        }
        View panel = getLayoutInflater().inflate(R.layout.popup_wifi, root, false);
        wifiSheet = panel;
        wifiRows = panel.findViewById(R.id.wifiRows);
        wifiToggle = panel.findViewById(R.id.wifiToggle);
        wifiToggle.setOn(wifiEnabled(wm));
        wifiToggle.setOnClickListener(v -> {
            final boolean want = !wifiToggle.isOn();
            wifiToggle.setOn(want);
            boolean ok = false;
            try {
                ok = wm.setWifiEnabled(want);
            } catch (Exception ignored) {
            }
            // Android 10+ refuses for anything targeting it; there the system page is the only way.
            if (!ok) {
                wifiToggle.setOn(!want);
                openWifiSettings();
                return;
            }
            final LinearLayout rows = wifiRows;
            handler.postDelayed(() -> {
                if (detailPanel != null && detailPanel.isShowing() && rows == wifiRows) {
                    fillWifiRows(wm, rows);
                    wifiToggle.setOn(wifiEnabled(wm));
                }
            }, 1500);
        });

        // The dark icons in the layout are tinted here: android:tint on a drawable is API 21.
        panel.<ImageView>findViewById(R.id.wifiFooterIcon)
                .setColorFilter(0xFF111114, PorterDuff.Mode.SRC_IN);
        panel.<ImageView>findViewById(R.id.wifiFooterChevron)
                .setColorFilter(0xFF9A9AA0, PorterDuff.Mode.SRC_IN);
        panel.findViewById(R.id.wifiFooter).setOnClickListener(v -> openWifiSettings());

        // A scan on demand: some ROMs throttle the scan that runs when the sheet opens, and a
        // network that appeared since then would otherwise never show up without reopening.
        final TextView scanLabel = panel.findViewById(R.id.wifiScanLabel);
        panel.<ImageView>findViewById(R.id.wifiScanIcon)
                .setColorFilter(0xFF111114, PorterDuff.Mode.SRC_IN);
        panel.findViewById(R.id.wifiScanRow).setOnClickListener(v -> {
            if (!wifiEnabled(wm)) {
                toast(getString(R.string.wifi_off));
                return;
            }
            scanLabel.setText(R.string.wifi_scanning);
            try {
                wm.startScan();
            } catch (Exception ignored) {
            }
            handler.postDelayed(() -> {
                if (detailPanel == null || !detailPanel.isShowing() || panel != wifiSheet) return;
                fillWifiRows(wm, wifiRows);
                scanLabel.setText(R.string.wifi_scan);
                if (scanResults(wm).isEmpty()) toast(getString(R.string.wifi_no_new));
            }, 2500);
        });

        fillWifiRows(wm, wifiRows);

        View anchor = findViewById(R.id.btnWifi);
        detailPanel = floatingOver(panel, anchor, 330, 0x73FFFFFF, 0x59FFFFFF);
        riseIn(detailPanel, panel, anchor);
        panel.post(() -> {
            View first = firstFocusable(wifiRows);
            View target = first != null ? first : wifiToggle;
            // In touch mode a plain focusable refuses focus; the touch request still aims the remote.
            if (!target.requestFocus()) target.requestFocusFromTouch();
        });
    }

    private void openWifiSettings() {
        dismissDetail();
        openPage("network", systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }

    private static boolean wifiEnabled(WifiManager wm) {
        try {
            return wm.isWifiEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private static View firstFocusable(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View v = group.getChildAt(i);
            if (v.isFocusable()) return v;
            if (v instanceof ViewGroup) {
                View inner = firstFocusable((ViewGroup) v);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    private void fillWifiRows(final WifiManager wm, LinearLayout rows) {
        rows.removeAllViews();
        if (!wifiEnabled(wm)) {
            addWifiHint(rows, getString(R.string.wifi_off));
            return;
        }
        String ssid = null;
        int rssi = -127;
        try {
            WifiInfo info = wm.getConnectionInfo();
            ssid = ssidOf(info);
            if (info != null) rssi = info.getRssi();
        } catch (Exception ignored) {
        }

        // Saved networks by name, with the id the switch needs.
        Map<String, Integer> saved = new LinkedHashMap<>();
        try {
            List<WifiConfiguration> cfg = wm.getConfiguredNetworks();
            if (cfg != null) {
                for (WifiConfiguration c : cfg) {
                    String n = c.SSID == null ? null : c.SSID.replace("\"", "").trim();
                    if (n != null && !n.isEmpty()) saved.put(n, c.networkId);
                }
            }
        } catch (Exception ignored) {
        }
        // Android 10+ hides the saved list, even the network in use. The connection is the truth.
        if (ssid != null && !saved.containsKey(ssid)) saved.put(ssid, -1);

        List<ScanResult> hotspots = new ArrayList<>();
        List<ScanResult> others = new ArrayList<>();
        for (ScanResult r : scanResults(wm)) {
            // The network in use is in the saved section; some ROMs also list it in the scan.
            if (r.SSID.equals(ssid) || saved.containsKey(r.SSID)) continue;
            if (looksLikeHotspot(r.SSID)) hotspots.add(r);
            else others.add(r);
        }

        // A phone's hotspot is what people reach for when the house Wi-Fi is down, so it comes first.
        if (!hotspots.isEmpty()) {
            addWifiSection(rows, getString(R.string.wifi_hotspots));
            for (ScanResult r : hotspots) {
                addWifiRow(rows, R.drawable.ic_link, r.SSID, r.level, false, false,
                        this::openWifiSettings);
            }
        }
        if (!saved.isEmpty()) {
            addWifiSection(rows, getString(R.string.wifi_known));
            for (final Map.Entry<String, Integer> e : saved.entrySet()) {
                final String name = e.getKey();
                final int netId = e.getValue();
                boolean here = name.equals(ssid);
                addWifiRow(rows, R.drawable.ic_wifi, name, here ? rssi : -127, true, here,
                        netId >= 0 && !here ? () -> connectSaved(wm, netId, name)
                                : this::openWifiSettings);
            }
        }
        if (!others.isEmpty()) {
            addWifiSection(rows, getString(R.string.wifi_other));
            for (ScanResult r : others) {
                addWifiRow(rows, R.drawable.ic_wifi, r.SSID, r.level, false, false,
                        this::openWifiSettings);
            }
        }
        if (hotspots.isEmpty() && saved.isEmpty() && others.isEmpty()) {
            addWifiHint(rows, getString(R.string.wifi_no_networks));
        }
        wireWifiFocus(rows);
    }

    /**
     * One column of rows between the switch and the settings footer. Focus search alone walks out
     * of the popup on UP, so the ends of the column are wired by hand.
     */
    private void wireWifiFocus(LinearLayout rows) {
        View first = null, last = null;
        int id = 4000;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View v = rows.getChildAt(i);
            if (!v.isFocusable()) continue;
            v.setId(++id);
            v.setNextFocusLeftId(v.getId());
            v.setNextFocusRightId(v.getId());
            if (first == null) first = v;
            last = v;
        }
        View footer = wifiSheet == null ? null : wifiSheet.findViewById(R.id.wifiFooter);
        View scan = wifiSheet == null ? null : wifiSheet.findViewById(R.id.wifiScanRow);
        int afterRows = scan != null ? R.id.wifiScanRow : R.id.wifiFooter;
        if (wifiToggle != null) {
            wifiToggle.setNextFocusDownId(first != null ? first.getId() : afterRows);
        }
        if (first != null && wifiToggle != null) first.setNextFocusUpId(wifiToggle.getId());
        if (last != null) last.setNextFocusDownId(afterRows);
        if (scan != null) {
            scan.setNextFocusUpId(last != null ? last.getId()
                    : wifiToggle != null ? wifiToggle.getId() : R.id.wifiScanRow);
            scan.setNextFocusDownId(R.id.wifiFooter);
        }
        if (footer != null) {
            footer.setNextFocusUpId(scan != null ? R.id.wifiScanRow
                    : last != null ? last.getId()
                    : wifiToggle != null ? wifiToggle.getId() : R.id.wifiFooter);
        }
    }

    /** A heading inside the sheet: not focusable, so the remote skips straight to the rows. */
    private void addWifiSection(LinearLayout rows, String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextColor(0xFF6E6E73);
        h.setTextSize(13);
        h.setPadding(dp(14), dp(12), dp(14), dp(4));
        h.setFocusable(false);
        rows.addView(h);
    }

    private void addWifiHint(LinearLayout rows, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF8E8E93);
        t.setTextSize(14);
        t.setPadding(dp(14), dp(16), dp(14), dp(16));
        t.setFocusable(false);
        rows.addView(t);
    }

    /** One network line: disc glyph, name, then a lock or the signal bars. The whole row is the button. */
    private void addWifiRow(LinearLayout rows, int iconRes, String name, int rssi,
                            boolean savedNet, boolean connected, final Runnable action) {
        // Hairline between neighbours in the same section, inset to start after the glyph.
        if (rows.getChildCount() > 0
                && rows.getChildAt(rows.getChildCount() - 1).isFocusable()) {
            View line = new View(this);
            line.setBackgroundColor(0x14000000);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(dp(54), 0, 0, 0);
            rows.addView(line, lp);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        row.setBackgroundResource(R.drawable.wifi_row_bg);
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnClickListener(v -> action.run());
        rows.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setBackgroundResource(connected ? R.drawable.wifi_disc_on : R.drawable.wifi_disc);
        int pad = dp(7);
        icon.setPadding(pad, pad, pad, pad);
        icon.setColorFilter(connected ? Color.WHITE : 0xFF111114, PorterDuff.Mode.SRC_IN);
        row.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(0xFF111114);
        label.setTextSize(15);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(12), 0, dp(8), 0);
        row.addView(label, lp);

        if (savedNet) {
            ImageView lock = new ImageView(this);
            lock.setImageResource(R.drawable.ic_lock);
            lock.setColorFilter(0xFF9A9AA0, PorterDuff.Mode.SRC_IN);
            row.addView(lock, new LinearLayout.LayoutParams(dp(14), dp(14)));
        } else {
            SignalBars bars = new SignalBars(this);
            bars.setLevel(signalBars(rssi));
            row.addView(bars, new LinearLayout.LayoutParams(dp(20), dp(14)));
        }
    }

    private static int signalBars(int rssi) {
        if (rssi >= -55) return 4;
        if (rssi >= -67) return 3;
        if (rssi >= -78) return 2;
        if (rssi >= -88) return 1;
        return 0;
    }

    private void connectSaved(WifiManager wm, int netId, String name) {
        dismissDetail();
        try {
            wm.disconnect();
            boolean ok = wm.enableNetwork(netId, true);
            wm.reconnect();
            toast(ok ? getString(R.string.wifi_connecting, name)
                    : getString(R.string.not_available));
        } catch (Exception e) {
            toast(getString(R.string.not_available));
        }
    }

    /** Phone and pocket-router names. The heuristic is deliberately broad; a miss is only a row
     *  in the next section, not a broken list. */
    private static final java.util.regex.Pattern HOTSPOT = java.util.regex.Pattern.compile(
            "(?i).*(iphone|ipad|android|hotspot|personal|随身|口袋|热点|mifi|"
                    + "redmi|huawei|honor|vivo|oppo|xiaomi|oneplus|realme|meizu).*");

    private static boolean looksLikeHotspot(String ssid) {
        return ssid != null && HOTSPOT.matcher(ssid).matches();
    }

    private BluetoothAdapter bluetoothAdapter() {
        if (Build.VERSION.SDK_INT >= 18) {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            return bm == null ? null : bm.getAdapter();
        }
        return BluetoothAdapter.getDefaultAdapter();
    }

    private String bluetoothSubtitle() {
        BluetoothAdapter bt = bluetoothAdapter();
        if (bt == null) return null;
        try {
            if (!bt.isEnabled()) return getString(R.string.bluetooth_off);
            int n = connectedBtAddresses(bt).size();
            return n > 0 ? getString(R.string.devices_connected, n) : getString(R.string.on);
        } catch (Exception e) {
            return null;
        }
    }

    /** Addresses of devices the TV is actually connected to, across the classic and BLE profiles. */
    @SuppressLint("MissingPermission")
    private Set<String> connectedBtAddresses(BluetoothAdapter bt) {
        Set<String> out = new HashSet<>();
        if (Build.VERSION.SDK_INT < 18) return out;
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) return out;
        for (int profile : new int[]{BluetoothProfile.A2DP, BluetoothProfile.HEADSET,
                BluetoothProfile.GATT}) {
            try {
                for (BluetoothDevice d : bm.getConnectedDevices(profile)) {
                    if (d != null) out.add(d.getAddress());
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Paired remotes, headsets and speakers, with which of them are connected right now. */
    @SuppressLint("MissingPermission")
    private void showBluetoothDetails() {
        showDetail(R.string.bluetooth, rows -> {
            BluetoothAdapter bt = bluetoothAdapter();
            if (bt == null) {
                addControlRow(rows, R.drawable.ic_bluetooth, getString(R.string.bluetooth),
                        getString(R.string.not_available), null);
                return;
            }
            boolean on;
            try {
                on = bt.isEnabled();
            } catch (Exception e) {
                on = false;
            }
            if (!on) {
                addControlRow(rows, R.drawable.ic_bluetooth, getString(R.string.bluetooth),
                        getString(R.string.bluetooth_off), null);
            } else {
                Set<String> connected = connectedBtAddresses(bt);
                Set<BluetoothDevice> bonded = null;
                try {
                    bonded = bt.getBondedDevices();
                } catch (Exception ignored) {
                }
                if (bonded == null || bonded.isEmpty()) {
                    addControlRow(rows, R.drawable.ic_bluetooth, getString(R.string.bluetooth),
                            getString(R.string.no_paired_devices), null);
                } else {
                    for (BluetoothDevice d : bonded) {
                        String name = d.getName();
                        if (name == null || name.isEmpty()) name = d.getAddress();
                        addControlRow(rows, R.drawable.ic_bluetooth, name,
                                connected.contains(d.getAddress())
                                        ? getString(R.string.connected) : getString(R.string.paired),
                                null);
                    }
                }
            }
            addControlRow(rows, R.drawable.ic_settings, getString(R.string.open_bluetooth_settings), null,
                    () -> {
                        dismissDetail();
                        open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                    });
        });
    }

    private interface DetailBuilder {
        void build(LinearLayout rows);
    }

    /** A second glass sheet under the pill, for a device list or a few facts. */
    private void showDetail(int titleRes, DetailBuilder builder) {
        if (detailPanel != null && detailPanel.isShowing()) {
            detailPanel.dismiss();
            return;
        }
        View panel = getLayoutInflater().inflate(R.layout.popup_detail, root, false);
        ((TextView) panel.findViewById(R.id.detailTitle)).setText(titleRes);
        LinearLayout rows = panel.findViewById(R.id.detailRows);
        builder.build(rows);
        View anchor = findViewById(R.id.btnControl);
        detailPanel = floatingOver(panel, anchor, 340, 0x73FFFFFF, 0x59FFFFFF);
        riseIn(detailPanel, panel, anchor);
        detailPanel.setOnDismissListener(this::focusControlPill);
        focusFirstRow(rows, 25);
    }

    /** Back on the pill that opened a sheet, so the D-pad has somewhere to go again. */
    private void focusControlPill() {
        View pill = findViewById(R.id.btnControl);
        if (!pill.requestFocus()) pill.requestFocusFromTouch();
    }

    /**
     * Gives the D-pad a starting point once the popup window is up. A requestFocus queued before the
     * window has focus is dropped, and closing the panel underneath can steal focus back a frame
     * later, so keep watch for a moment — but only step in while nothing in the sheet has focus, or
     * this would yank the highlight back from the viewer's own D-pad press.
     */
    private void focusFirstRow(LinearLayout rows, int tries) {
        if (rows.hasWindowFocus() && rows.findFocus() == null) {
            for (int i = 0; i < rows.getChildCount(); i++) {
                View v = rows.getChildAt(i);
                if (v.isFocusable()) {
                    // After a tap the desk is in touch mode, where plain requestFocus refuses a
                    // view that is only remote-focusable; the touch-mode call still seats it.
                    if (!v.requestFocus()) v.requestFocusFromTouch();
                    break;
                }
            }
        }
        if (tries > 0) rows.postDelayed(() -> focusFirstRow(rows, tries - 1), 80);
    }

    private void dismissDetail() {
        if (detailPanel != null) detailPanel.dismiss();
    }

    /**
     * The control centre's running list. Android does not hand a home screen other apps' live
     * processes, so the top half is the system's record of what was recently used (honest label),
     * and the bottom half is this desk's own services, which we can actually see.
     */
    private void showRunningPanel() {
        if (detailPanel != null && detailPanel.isShowing()) detailPanel.dismiss();
        View panel = getLayoutInflater().inflate(R.layout.popup_detail, root, false);
        ((TextView) panel.findViewById(R.id.detailTitle)).setText(R.string.running_title);
        LinearLayout rows = panel.findViewById(R.id.detailRows);

        addSectionHeader(rows, getString(R.string.running_recent_header));
        if (Build.VERSION.SDK_INT < 21) {
            addControlRow(rows, R.drawable.ic_apps, getString(R.string.running_api_old), null, null);
        } else if (!usageAccessGranted()) {
            addControlRow(rows, R.drawable.ic_settings, getString(R.string.running_grant_title),
                    getString(R.string.running_grant_subtitle), () -> {
                        dismissDetail();
                        open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    });
        } else {
            PackageManager pm = getPackageManager();
            long dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
            int shown = 0;
            for (UsageScan.Recent r : UsageScan.recent(this, dayAgo, 20)) {
                if (shown >= 8) break;   // the sheet scrolls, but 20 stops on a remote is a slog
                if (r.pkg.equals(getPackageName()) || pm.getLaunchIntentForPackage(r.pkg) == null) {
                    continue;   // not launchable from here, so "app info" is the only thing it offers
                }
                String label;
                Drawable icon;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(r.pkg, 0);
                    label = String.valueOf(pm.getApplicationLabel(ai));
                    icon = pm.getApplicationIcon(ai);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                String when = DateUtils.getRelativeTimeSpanString(r.ts, System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS).toString();
                addAppRow(rows, icon, label, when, () -> {
                    // Leaving the sheet up over the desk we come back to would strand focus outside it.
                    dismissDetail();
                    openAppInfo(r.pkg);
                });
                shown++;
            }
            if (shown == 0) {
                addControlRow(rows, R.drawable.ic_apps, getString(R.string.running_no_recent), null, null);
            }
        }

        addSectionHeader(rows, getString(R.string.running_services_header));
        boolean a11y = homeKeyEnabled();
        addControlRow(rows, R.drawable.ic_settings, getString(R.string.home_key_name),
                getString(a11y ? R.string.running_a11y_on : R.string.running_a11y_off), () -> {
                    dismissDetail();
                    open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                });
        boolean dream = runningService("ClockDream");
        addControlRow(rows, R.drawable.ic_settings, getString(R.string.dream_name),
                getString(dream ? R.string.running_service_running : R.string.running_service_stopped),
                () -> {
                    dismissDetail();
                    openAppInfo(getPackageName());
                });

        addFootnote(rows, getString(R.string.running_platform_note));

        View anchor = findViewById(R.id.btnControl);
        detailPanel = floatingOver(panel, anchor, 360, 0x73FFFFFF, 0x59FFFFFF);
        riseIn(detailPanel, panel, anchor);
        detailPanel.setOnDismissListener(this::focusControlPill);
        focusFirstRow(rows, 25);
    }

    /** Whether the user allowed usage access; without it the recent list reads as empty. */
    private boolean usageAccessGranted() {
        if (Build.VERSION.SDK_INT < 21) return false;
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The Home-key accessibility service is bound by the system, so its switch is the truth. */
    private boolean homeKeyEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String on = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED);
        if (enabled == null || !"1".equals(on)) return false;
        ComponentName cn = new ComponentName(this, HomeKeyService.class);
        return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString());
    }

    /** Whether one of this app's own declared services is listed as running. */
    private boolean runningService(String simpleName) {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        try {
            List<ActivityManager.RunningServiceInfo> list = am.getRunningServices(60);
            if (list == null) return false;
            for (ActivityManager.RunningServiceInfo si : list) {
                if (si.service != null && getPackageName().equals(si.service.getPackageName())
                        && si.service.getClassName().endsWith("." + simpleName)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            Errors.record("running", "services " + e.getClass().getSimpleName());
        }
        return false;
    }

    /** A small caps-ish section label inside a detail sheet; never takes focus. */
    private void addSectionHeader(LinearLayout rows, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(0x99FFFFFF);
        tv.setPadding(dp(16), dp(10), dp(16), dp(4));
        rows.addView(tv);
    }

    /** An explanatory line at the bottom of a sheet; the remote skips it. */
    private void addFootnote(LinearLayout rows, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(0x80FFFFFF);
        tv.setLineSpacing(dp(2), 1f);
        tv.setPadding(dp(16), dp(8), dp(16), dp(4));
        rows.addView(tv);
    }

    /** A row with a real app icon (not a tinted glyph), for the recent-apps list. */
    private void addAppRow(LinearLayout rows, Drawable icon, String title, String subtitle,
                           Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_control_row, rows, false);
        ImageView iconView = row.findViewById(R.id.icon);
        if (icon != null) iconView.setImageDrawable(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.subtitle)).setText(subtitle);
        ImageView chevron = row.findViewById(R.id.chevron);
        row.setOnFocusChangeListener((v, hasFocus) -> {
            chevron.setColorFilter(hasFocus ? 0xFF111111 : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
            glideFocus(v, hasFocus, rows);
        });
        chevron.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        row.setOnClickListener(v -> action.run());
        rows.addView(row);
    }

    /** A round glass tile that closes the sheet and goes somewhere. */
    private void addTile(LinearLayout tiles, int iconRes, String title, Runnable action) {
        View tile = getLayoutInflater().inflate(R.layout.item_control_tile, tiles, false);
        ImageView icon = tile.findViewById(R.id.icon);
        icon.setImageResource(iconRes);
        ((TextView) tile.findViewById(R.id.title)).setText(title);
        // On the light sheet the selection is ink, so the glyph goes from ink to white. Tinting by
        // hand works on the old Android versions where a state-list tint is ignored.
        tile.setOnFocusChangeListener((v, hasFocus) -> {
            icon.setColorFilter(hasFocus ? 0xFFFFFFFF : 0xFF111114, PorterDuff.Mode.SRC_IN);
            glideFocus(v, hasFocus, tiles);
        });
        icon.setColorFilter(0xFF111114, PorterDuff.Mode.SRC_IN);
        tile.setOnClickListener(v -> {
            if (controlPanel != null) controlPanel.dismiss();
            action.run();
        });
        tiles.addView(tile);
    }

    /** One row of shortcut pills, with its own sliding selection pane; returns the row to fill. */
    private LinearLayout addPillRow(LinearLayout parent) {
        GlideBackdrop glide = new GlideBackdrop(this, null);
        glide.setRadiusDp(19);
        glide.setLight();
        FrameLayout box = new FrameLayout(this);
        box.addView(glide, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setTag(glide);
        box.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** The compact bottom pill. Icon and label flip to white with the ink focus fill. */
    private void addShortcutPill(LinearLayout row, int iconRes, String title, Runnable action) {
        addShortcutPill(row, iconRes, title, action, false);
    }

    /** Same pill, with an unread dot after the label ("Check for updates" while a build is waiting). */
    private void addShortcutPill(LinearLayout row, int iconRes, String title, Runnable action,
                                 boolean unread) {
        addShortcutPill(row, iconRes, title, action, unread, true);
    }

    /** Same pill; [dismiss] false keeps the panel open (the dock size pills need that). */
    private void addShortcutPill(LinearLayout row, int iconRes, String title, Runnable action,
                                 boolean unread, final boolean dismiss) {
        View pill = getLayoutInflater().inflate(R.layout.item_shortcut_pill, row, false);
        ImageView icon = pill.findViewById(R.id.icon);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFF111114, PorterDuff.Mode.SRC_IN);
        ((TextView) pill.findViewById(R.id.label)).setText(title);
        if (unread && pill instanceof LinearLayout) {
            View dot = new View(this);
            dot.setBackgroundResource(R.drawable.dot_unread);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
            lp.leftMargin = dp(5);
            lp.gravity = Gravity.CENTER_VERTICAL;
            ((LinearLayout) pill).addView(dot, lp);
        }
        pill.setOnFocusChangeListener((v, hasFocus) -> {
            icon.setColorFilter(hasFocus ? 0xFFFFFFFF : 0xFF111114, PorterDuff.Mode.SRC_IN);
            glideFocus(v, hasFocus, row);
        });
        pill.setOnClickListener(v -> {
            if (dismiss && controlPanel != null) controlPanel.dismiss();
            action.run();
        });
        row.addView(pill);
    }

    /** A row that closes the panel and goes somewhere. */
    private void addShortcutRow(LinearLayout rows, int iconRes, String title, Runnable action) {
        addControlRow(rows, iconRes, title, null, () -> {
            if (controlPanel != null) controlPanel.dismiss();
            action.run();
        });
    }

    /**
     * One iOS-style capsule slider. It writes live through the audio service. The icon and the
     * value sit on top of the bar; only the bar itself takes focus, so left/right adjusts it.
     */
    private SeekBar addSlider(LinearLayout parent, int iconRes, String label,
                              final int max, int value) {
        final int range = Math.max(1, max);

        FrameLayout capsule = new FrameLayout(this);

        SeekBar bar = new ThickSeekBar(this);
        bar.setMax(range);
        bar.setProgress(Math.max(0, Math.min(range, value)));
        bar.setContentDescription(label);
        capsule.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFF111114); // setImageTintList is API 21; this works everywhere.
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(24), dp(24),
                Gravity.START | Gravity.CENTER_VERTICAL);
        iconLp.leftMargin = dp(20);
        capsule.addView(icon, iconLp);

        final TextView valueView = new TextView(this);
        valueView.setTextColor(0xFF111114);
        valueView.setTextSize(13);
        valueView.setText(
                Math.round(Math.max(0, Math.min(range, value)) * 100f / range) + "%");
        FrameLayout.LayoutParams valueLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        valueLp.rightMargin = dp(22);
        capsule.addView(valueView, valueLp);

        // The level is ink until the slider takes focus, then white. Whichever it is, the glyph
        // and the value have to sit on the opposite side of it, so flip them once the fill has
        // covered most of where they are drawn.
        final int ink = 0xFF111114, white = 0xFFFFFFFF;
        final Runnable tint = () -> {
            float w = bar.getWidth();
            if (w <= 0) return;
            float f = bar.getProgress() / (float) range;
            boolean levelInk = !bar.isFocused();
            float iconEdge = (icon.getLeft() + icon.getWidth() * 0.5f) / w;
            float textEdge = (valueView.getLeft() + valueView.getWidth() * 0.6f) / w;
            icon.setColorFilter(levelInk && f > iconEdge ? white : ink);
            valueView.setTextColor(levelInk && f > textEdge ? white : ink);
        };
        capsule.post(tint);
        bar.setOnFocusChangeListener((v, hasFocus) -> tint.run());
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                valueView.setText(Math.round(progress * 100f / range) + "%");
                tint.run();
                if (!fromUser) return;
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    try {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                    } catch (Exception ignored) {
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        LinearLayout.LayoutParams capsuleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        capsuleLp.setMargins(dp(4), dp(3), dp(4), dp(3));
        parent.addView(capsule, capsuleLp);
        return bar;
    }

    /** Four ascending bars, the familiar Wi-Fi signal reading; drawn because it is 20×14dp. */
    public static class SignalBars extends View {
        private static final float[] HEIGHTS = {0.32f, 0.56f, 0.78f, 1f};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int level = 2;

        public SignalBars(Context context) {
            super(context);
        }

        void setLevel(int value) {
            level = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float bar = w * 0.16f;
            float gap = (w - bar * 4) / 3f;
            for (int i = 0; i < 4; i++) {
                float bh = h * HEIGHTS[i];
                float left = i * (bar + gap);
                paint.setColor(i < level ? 0xFF111114 : 0x2E000000);
                canvas.drawRoundRect(new RectF(left, h - bh, left + bar, h), bar / 2, bar / 2, paint);
            }
        }
    }

}
