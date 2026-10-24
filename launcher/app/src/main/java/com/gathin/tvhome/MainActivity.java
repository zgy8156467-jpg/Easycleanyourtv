package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
import java.util.List;
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
    private DockDecoration dockDecoration;
    private static final int REQ_PICK_WALLPAPER = 41;
    private static final int REQ_UNINSTALL = 42;
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
                if (getPosition(child) < COLUMNS) return false;
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
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        dockDecoration = new DockDecoration(COLUMNS, density, wallpaperPresent());
        grid.addItemDecoration(dockDecoration);
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        reducedMotion = getSharedPreferences(PREFS_LAYOUT, MODE_PRIVATE)
                .getBoolean(KEY_REDUCED_MOTION, false);
        AppAdapter.setReducedMotion(reducedMotion);
        adapter = new AppAdapter(this, this, COLUMNS, cfg.dockApps);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // The item height follows the tile, and the tile follows the column count, so this cannot be
            // a constant: at six columns the tiles are smaller and the dock row sits lower.
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int tile = AppAdapter.tileWidthPx(inner, COLUMNS, density);
            int dockRow = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tile), density)
                    + (int) ((6 + 12) * density);   // item height plus DockDecoration offsets
            // 6dp, not the old 30: the glass band is drawn 14dp beyond the cards and the row already
            // carries 12dp of bottom offset plus 8dp of item padding, so this puts the band's bottom
            // edge exactly on the screen edge instead of floating it 36dp up.
            int want = Math.max(0, h - dockRow - (int) (6 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
                // The padding moves the dock row without scrolling, so the scroll listener never sees
                // it; on a cold start that leaves the glass above the tile until the next key press.
                // The rows only reach their new place on the next layout pass, so reseat from there.
                reseatPending = true;
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

        // Keep focus on the top bar and at the top: Android's focus finder would otherwise jump to a
        // tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder happens to land.
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            // It is the only pill left, so neither side has anywhere else to go.
            return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        };
        if (btnControl != null) btnControl.setOnKeyListener(pillKeys);

        // The glyph sits bare on the wallpaper: focus wraps it in an iOS frosted square and
        // brightens it instead of filling it white.
        final View controlGlass = findViewById(R.id.controlGlass);
        if (btnControl != null) {
            final View pill = btnControl;
            pill.setAlpha(0.8f);
            pill.setOnFocusChangeListener((v, hasFocus) -> {
                pill.setAlpha(hasFocus ? 1f : 0.8f);
                if (controlGlass == null) return;
                controlGlass.animate().cancel();
                if (hasFocus) {
                    controlGlass.setScaleX(0.92f);
                    controlGlass.setScaleY(0.92f);
                    if (reducedMotion) {
                        controlGlass.setAlpha(1f);
                        controlGlass.setScaleX(1f);
                        controlGlass.setScaleY(1f);
                    } else {
                        controlGlass.animate().alpha(1f).scaleX(1f).scaleY(1f)
                                .setDuration(200)
                                .setInterpolator(new OvershootInterpolator(1.4f)).start();
                    }
                } else if (reducedMotion) {
                    controlGlass.setAlpha(0f);
                } else {
                    controlGlass.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f)
                            .setDuration(140).start();
                }
            });
        }

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
        final int returnSlot = lastFocused;
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
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
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
        if (stage != null) stage.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (appMenu != null && appMenu.isShowing()) {
            appMenu.dismiss();
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
        if (cityPicker != null && cityPicker.isShowing()) {
            cityPicker.dismiss();
            return;
        }
        if (isInProgramBar(getCurrentFocus())) {
            focusTile(lastFocused % COLUMNS);
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
        if (adapter == null || adapter.getItemCount() == 0) return;
        lastFocused = 0;
        lastFocusedKey = adapter.keyAt(0);
        // Take the glass off first. The tile it is glued to is about to be moved to another row, and
        // measuring it in between left the highlight sitting in the top-left corner of the screen.
        hideGlass();
        grid.scrollToPosition(0);
        // Focus after the layout pass that moves the rows. Straight after scrollToPosition the holder
        // found for position 0 is still the old one, and asking a view that is about to be recycled for
        // focus is how the highlight ended up nowhere at all.
        grid.post(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
            if (vh == null) {
                focusTile(0);           // not laid out yet: focusTile waits for the scroll to settle
                return;
            }
            View card = vh.itemView.findViewById(R.id.card);
            onTileFocused(0, card);    // already focused: no focus event will fire, so place it by hand
            card.requestFocus();
        });
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
     * Long-press a tile: on the dock it starts the rearrange mode (left/right moves, OK takes the
     * app off the dock, Back finishes), anywhere else it opens the pin/uninstall menu.
     */
    @Override
    public void onTileLongPressed(AppAdapter.Entry entry, boolean alreadyPinned) {
        int position = adapter.positionOf(entry.component.flattenToShortString());
        if (alreadyPinned && position >= 0 && position < adapter.dockSize()) {
            dockEditing = true;
            dockEditIndex = position;
            dockEditKey = entry.component.flattenToShortString();
            dockEditSwallowCenter = true;
            toast(getString(R.string.dock_edit_hint));
            return;
        }
        showAppMenu(entry, alreadyPinned);
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

    /** OK while rearranging: take the app off the dock and let focus follow it into the grid. */
    private void removeDockEntry() {
        List<String> apps = new ArrayList<>(adapter.dock().apps());
        if (dockEditIndex < 0 || dockEditIndex >= apps.size()) {
            stopDockEdit();
            return;
        }
        String label = adapter.labelAt(dockEditIndex);
        String key = dockEditKey;
        apps.remove(dockEditIndex);
        adapter.dock().set(apps);
        lastFocusedKey = key;
        stopDockEdit();
        refreshDock();
        toast(getString(R.string.dock_removed, label));
    }

    private void stopDockEdit() {
        dockEditing = false;
        dockEditIndex = -1;
        dockEditKey = null;
        dockEditSwallowCenter = false;
    }

    private void showAppMenu(final AppAdapter.Entry entry, final boolean pinned) {
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
        View rowUninstall = panel.findViewById(R.id.rowUninstall);
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
        adapter.reload();
        updateEmptyHint();
        grid.scheduleLayoutAnimation();
        int pos = adapter.positionOf(key);
        if (pos >= 0) {
            View v = grid.getLayoutManager().findViewByPosition(pos);
            if (v != null) v.requestFocus();
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
            if (dockDecoration != null) {
                dockDecoration.setOverWallpaper(wallpaperPresent());
                grid.invalidateItemDecorations();
            }
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
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
        });
        panel.findViewById(R.id.rowTvPicture).setOnClickListener(v -> {
            if (wallpaperPanel != null) wallpaperPanel.dismiss();
            open(systemIntent(Settings.ACTION_DISPLAY_SETTINGS));
        });
        panel.findViewById(R.id.rowTvApps).setOnClickListener(v -> {
            if (wallpaperPanel != null) wallpaperPanel.dismiss();
            open(systemIntent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        });
        panel.findViewById(R.id.rowTvSystem).setOnClickListener(v -> {
            if (wallpaperPanel != null) wallpaperPanel.dismiss();
            open(tvSettingsIntent(this));
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
            Intent pick = new Intent(Build.VERSION.SDK_INT >= 19
                    ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*");
            try {
                wallpaperPanel.dismiss();
                startActivityForResult(pick, REQ_PICK_WALLPAPER);
            } catch (RuntimeException e) {
                toast(getString(R.string.not_available));
            }
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
            if (dockDecoration != null) {
                dockDecoration.setOverWallpaper(wallpaperPresent());
                grid.invalidateItemDecorations();
            }
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
        // The dock band has to follow: opaque over black, translucent over a picture.
        if (dockDecoration != null) {
            dockDecoration.setOverWallpaper(wallpaperPresent());
            grid.invalidateItemDecorations();
        }
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

    /** Provinces first, then the cities of the chosen province; Back closes the popup. */
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
        List<String> rows = new ArrayList<>();
        rows.add(getString(R.string.weather_city_back));
        for (int i = 0; i < cities.length(); i++) {
            rows.add(cities.optJSONArray(i).optString(0));
        }
        showCityList(province.optString("p"), rows, position -> {
            if (position == 0) {
                showCityPicker();
                return;
            }
            JSONArray city = cities.optJSONArray(position - 1);
            if (city == null) return;
            String name = city.optString(0);
            Weather.setPlace(this, name, city.optDouble(1, 0), city.optDouble(2, 0));
            refreshWeather(true);
            toast(getString(R.string.weather_city_set, name));
            if (cityPicker != null) cityPicker.dismiss();
        });
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
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        if (dockEditing && position >= adapter.dockSize()) stopDockEdit();
        // Video apps get their platform's hot posters behind the desk; everything else keeps the
        // default wallpaper. Same app again is a no-op inside the stage.
        if (stage != null) stage.showForPackage(adapter.pkgAt(position));
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
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
            // The posters were just read for this package; show their title and dots right away.
            updateProgram(stage.postersFor(adapter.pkgAt(position)), stage.currentPosterIndex());
        } else {
            hideHero();
            hideProgram();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = revealRect;
            want.set(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
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
        if (adapter == null || lastFocused >= COLUMNS) return;
        if (!pkg.equals(adapter.pkgAt(lastFocused))) return;
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
                if (adapter != null) adapter.launchAt(lastFocused);
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
                focusTile(lastFocused % COLUMNS);
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
        if (programDots == null || programDots.getVisibility() != View.VISIBLE || adapter == null) return;
        RecyclerView.ViewHolder first = grid.findViewHolderForAdapterPosition(0);
        RecyclerView.ViewHolder last = grid.findViewHolderForAdapterPosition(
                Math.max(0, adapter.dockSize() - 1));
        if (first == null || last == null) return;
        View leftCard = first.itemView.findViewById(R.id.card);
        View rightCard = last.itemView.findViewById(R.id.card);
        if (leftCard == null || rightCard == null) return;
        // Layout coordinates, not the visual ones: a focused dock tile is scaled up around its centre.
        int[] at = new int[2];
        first.itemView.getLocationInWindow(at);
        int bandLeft = at[0] + leftCard.getLeft() - dp(14);
        int top = at[1] + leftCard.getTop();
        last.itemView.getLocationInWindow(at);
        int bandRight = at[0] + rightCard.getLeft() + rightCard.getWidth() + dp(14);
        int w = programDots.getWidth() > 0
                ? programDots.getWidth() : programDots.getChildCount() * dp(36);
        int h = programDots.getHeight() > 0 ? programDots.getHeight() : dp(36);
        programDots.setX((bandLeft + bandRight) / 2f - w / 2f);
        // Just inside the glass band's top padding, clear of the tiles even when one is scaled.
        programDots.setY(top - dp(6) - h);
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) {
                // Up from the dock goes into the dot strip when there is more than one show.
                if (programDots.getVisibility() == View.VISIBLE && programDots.getChildCount() > 1) {
                    int at = Math.min(stage.currentPosterIndex(), programDots.getChildCount() - 1);
                    View dot = programDots.getChildAt(at);
                    if (dot != null) {
                        dot.requestFocus();
                        return true;
                    }
                }
                return false;                              // otherwise let focus move to the top bar
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
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
        if (attempt < 40) {                       // ~2s, then fall back to the scroll route
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
        emptyHint.setVisibility(adapter != null && adapter.getItemCount() == 0
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
     * Launcher entry of the vendor settings app for this profile, or null. MIUI TV's settings app
     * only opens through its explicit main activity; the others are ordinary launch intents.
     */
    private static Intent vendorSettingsIntent(android.content.Context ctx) {
        for (String pkg : DeviceProfile.get(ctx).settingsPackages) {
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
                "com.xiaomi.mitv.cast"}) {
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
            if (rows.getChildCount() == 0) {
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
     * iOS-style sheet: a full-screen frosted snapshot of the desk with the modules floating on it.
     * The dark card the panel used to sit in was most of why it read as an Android dialog; control
     * centre modules float straight over the blur on iOS. Real-time backdrop blur needs API 31 and
     * headroom a TV GPU does not have, but nothing behind the panel moves while it is open, so a
     * still blurred before it opens is indistinguishable. One quarter-scale draw and two box passes
     * over ~60k pixels, done only on open.
     */
    private PopupWindow floatingOver(View content, View anchor, int minWidthDp) {
        // A notice mid-flight would be baked into the still behind the panel; the dot keeps the state.
        if (updateBanner != null) updateBanner.setVisibility(View.GONE);
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(Math.max(anchor.getWidth(), (int) (minWidthDp * density)),
                (int) (screenW * 0.72f));

        FrameLayout frame = new FrameLayout(this);
        Bitmap back = blurredDesk();
        if (back != null) {
            ImageView bg = new ImageView(this);
            bg.setScaleType(ImageView.ScaleType.FIT_XY);
            bg.setImageBitmap(back);
            frame.addView(bg, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        View scrim = new View(this);
        scrim.setBackgroundColor(0x73000000);
        frame.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int top = at[1] + anchor.getHeight() + (int) (10 * density);
        int right = Math.max(0, screenW - at[0] - anchor.getWidth());
        int room = screenH - top - (int) (22 * density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width,
                Math.min(content.getMeasuredHeight(), Math.max(room, (int) (120 * density))),
                Gravity.TOP | Gravity.END);
        lp.topMargin = top;
        lp.rightMargin = right;
        frame.addView(content, lp);

        final PopupWindow popup = new PopupWindow(frame,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        // Taps land "inside" a full-screen popup, so outside-touch never fires; the scrim is the
        // thing that closes it instead.
        frame.setOnClickListener(v -> popup.dismiss());
        return popup;
    }

    private Bitmap blurredDesk() {
        try {
            int w = Math.max(1, root.getWidth() / 6), h = Math.max(1, root.getHeight() / 6);
            Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(small);
            canvas.scale(1f / 6, 1f / 6);
            root.draw(canvas);
            boxBlur(small, 5);
            return small;
        } catch (Throwable t) {
            // A firmware that refuses to draw the frame just gets the plain scrim.
            return null;
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

    /** Drop in: fade and unfold from the top edge, hanging under the anchor. */
    /** Same unfold, but the popup itself is full-screen and the content is placed by its layout. */
    private void riseIn(PopupWindow popup, View panel, View anchor) {
        panel.setAlpha(0f);
        panel.setTranslationY(-8 * density);
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, 0, 0);
        if (reducedMotion) {
            panel.setAlpha(1f);
            panel.setTranslationY(0f);
            return;
        }
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
            prefs.edit().putBoolean("a11y_was_on", true).putBoolean("a11y_prompted", false).apply();
            return;
        }
        if (a11yPromptedThisRun) return;
        boolean wasOn = prefs.getBoolean("a11y_was_on", false);
        if (!wasOn && prefs.getBoolean("a11y_prompted", false)) return;
        a11yPromptedThisRun = true;
        prefs.edit().putBoolean("a11y_prompted", true).apply();
        if (wasOn) Errors.record("a11y", "accessibility service turned off");
        showAccessibilityLost(wasOn);
    }

    private void showAccessibilityLost(boolean wasOn) {
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
                        if (!Remote.install(MainActivity.this, new java.io.File(path))) {
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

    /** One tap sends device info, the version and a logcat tail to the console; nothing to type. */
    private void sendFeedback() {
        toast(getString(R.string.feedback_sending));
        Remote.sendReport(this, "feedback", "", "",
                (ok, message) -> toast(getString(
                        ok ? R.string.feedback_sent : R.string.feedback_failed)));
    }

    // ---- control centre --------------------------------------------------------------------------

    /**
     * The macOS-style panel: network and Bluetooth switches, volume and brightness sliders, and the
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
        GlideBackdrop rowsGlide = panel.findViewById(R.id.rowsGlide);
        rowsGlide.setRadiusDp(26);

        // The four places people actually go, as round glass tiles. These used to be three separate
        // pills in the top bar; one glass entry that opens this sheet is the tvOS arrangement.
        addTile(tiles, R.drawable.ic_input, getString(R.string.inputs), this::showInputs);
        addTile(tiles, R.drawable.ic_wallpaper, getString(R.string.wallpaper), this::showWallpaperPicker);
        addTile(tiles, R.drawable.ic_dock, getString(R.string.favourites), this::showDockPicker);
        addTile(tiles, R.drawable.ic_settings, getString(R.string.tv_settings),
                () -> open(tvSettingsIntent(this)));

        // Network and Bluetooth open a detail sheet instead of flipping the radio in place: on a TV
        // remote a stray OK used to take the whole house off Wi-Fi.
        addControlRow(rows, R.drawable.ic_wifi, getString(R.string.network),
                networkSubtitle(), () -> {
                    if (controlPanel != null) controlPanel.dismiss();
                    showNetworkDetails();
                });
        if (bluetoothAdapter() != null) {
            addControlRow(rows, R.drawable.ic_bluetooth, getString(R.string.bluetooth),
                    bluetoothSubtitle(), () -> {
                        if (controlPanel != null) controlPanel.dismiss();
                        showBluetoothDetails();
                    });
        }

        // Below 23 WRITE_SETTINGS is granted at install time, so there is nothing to ask for.
        if (Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this)) {
            int brightness = 128;
            try {
                brightness = Settings.System.getInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, 128);
            } catch (Exception ignored) {
            }
            addSlider(sliders, R.drawable.ic_brightness, getString(R.string.brightness),
                    5, 255, brightness, false);
        } else {
            addShortcutRow(rows, R.drawable.ic_brightness,
                    getString(R.string.brightness_permission),
                    () -> open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                            .setData(Uri.parse("package:" + getPackageName()))));
        }

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            addSlider(sliders, R.drawable.ic_volume, getString(R.string.volume), 0, max, current, true);
        }

        // A row of compact pills instead of full-width rows: three tall rows pushed the volume
        // slider off the bottom of a 1080p screen once the modules started floating on the blur.
        // Its own backdrop, like the rows above, so the selection slides between the pills too.
        GlideBackdrop pillGlide = new GlideBackdrop(this, null);
        pillGlide.setRadiusDp(19);
        FrameLayout pillBox = new FrameLayout(this);
        pillBox.addView(pillGlide, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout shortcutRow = new LinearLayout(this);
        shortcutRow.setOrientation(LinearLayout.HORIZONTAL);
        pillBox.addView(shortcutRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shortcuts.addView(pillBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Intent cast = castIntent(this);
        if (cast != null) {
            addShortcutPill(shortcutRow, R.drawable.ic_cast, getString(R.string.quick_cast),
                    () -> open(cast));
        }
        // With a build already found, this opens its details straight away; otherwise it is a check.
        addShortcutPill(shortcutRow, R.drawable.ic_update, getString(R.string.check_update),
                this::openPendingUpdate, updateUnread());
        // Only a beta install carries the way back to stable; the stable desk never needs it.
        if (BuildConfig.CHANNEL.equals("beta")) {
            addShortcutPill(shortcutRow, R.drawable.ic_update, getString(R.string.stable_switch),
                    this::switchToStable);
        }
        addShortcutPill(shortcutRow, R.drawable.ic_send, getString(R.string.feedback_send),
                this::sendFeedback);

        // The items go transparent and hand their dark pill to the backdrop; the backdrop also
        // draws the white selection, so focus can travel instead of popping between items.
        List<View> tileIcons = new ArrayList<>(tiles.getChildCount());
        for (int i = 0; i < tiles.getChildCount(); i++) {
            View tile = tiles.getChildAt(i);
            tile.setTag(tilesGlide);
            ImageView icon = tile.findViewById(R.id.icon);
            icon.setBackground(null);
            tileIcons.add(icon);
        }
        List<View> rowItems = new ArrayList<>(rows.getChildCount());
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            row.setBackground(null);
            row.setTag(rowsGlide);
            rowItems.add(row);
        }
        List<View> pillItems = new ArrayList<>(shortcutRow.getChildCount());
        for (int i = 0; i < shortcutRow.getChildCount(); i++) {
            View pill = shortcutRow.getChildAt(i);
            pill.setBackground(null);
            pill.setTag(pillGlide);
            pillItems.add(pill);
        }
        watchGlide((ViewGroup) tiles.getParent(), tilesGlide, tileIcons);
        watchGlide((ViewGroup) rows.getParent(), rowsGlide, rowItems);
        watchGlide(pillBox, pillGlide, pillItems);

        View anchor = findViewById(R.id.btnControl);
        controlPanel = floatingOver(panel, anchor, 340);
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

    /** A wide pill ending in a detail sheet: glyph in a white disc, title, one line of state. */
    private View addControlRow(LinearLayout rows, int iconRes, String title, String subtitle,
                               Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_control_row, rows, false);
        ImageView icon = row.findViewById(R.id.icon);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFF1A1A1A, PorterDuff.Mode.SRC_IN);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView sub = row.findViewById(R.id.subtitle);
        if (subtitle == null || subtitle.isEmpty()) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setText(subtitle);
        }
        ImageView chevron = row.findViewById(R.id.chevron);
        if (action == null) {
            // A plain fact line: the remote skips it and there is nowhere to go.
            row.setFocusable(false);
            row.setClickable(false);
            chevron.setVisibility(View.GONE);
        } else {
            chevron.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
            row.setOnFocusChangeListener((v, hasFocus) -> {
                chevron.setColorFilter(hasFocus ? 0xFF111111 : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
                glideFocus(v, hasFocus, rows);
            });
            row.setOnClickListener(v -> action.run());
        }
        rows.addView(row);
        return row;
    }

    /** One line for the Wi-Fi pill: the network name, or "not connected". */
    private String networkSubtitle() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                String ssid = ssidOf(wm.getConnectionInfo());
                if (ssid != null) return ssid;
                return getString(R.string.network_off);
            }
        } catch (Exception ignored) {
        }
        return getString(R.string.network_off);
    }

    private static String ssidOf(WifiInfo info) {
        if (info == null) return null;
        String ssid = info.getSSID();
        if (ssid == null) return null;
        ssid = ssid.replace("\"", "").trim();
        if (ssid.isEmpty() || ssid.contains("unknown") || "0x".equals(ssid)) return null;
        return ssid;
    }

    private static String ipv4(int ip) {
        if (ip == 0) return "-";
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "."
                + ((ip >> 24) & 0xFF);
    }

    private String signalLabel(int rssi) {
        if (rssi >= -60) return getString(R.string.signal_strong) + " (" + rssi + " dBm)";
        if (rssi >= -70) return getString(R.string.signal_ok) + " (" + rssi + " dBm)";
        return getString(R.string.signal_weak) + " (" + rssi + " dBm)";
    }

    /** Network facts, and one way out to the system page. No radio flipping from the remote here. */
    private void showNetworkDetails() {
        showDetail(R.string.network, rows -> {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            boolean on = false;
            WifiInfo info = null;
            try {
                on = wm != null && wm.isWifiEnabled();
                info = wm != null ? wm.getConnectionInfo() : null;
            } catch (Exception ignored) {
            }
            String ssid = ssidOf(info);
            addControlRow(rows, R.drawable.ic_wifi, getString(R.string.current_network),
                    !on ? getString(R.string.network_off)
                            : (ssid != null ? ssid : getString(R.string.connected)), null);
            addControlRow(rows, R.drawable.ic_wifi, getString(R.string.ip_address),
                    ipv4(info != null ? info.getIpAddress() : 0), null);
            if (on && info != null && info.getRssi() != 0) {
                addControlRow(rows, R.drawable.ic_wifi, getString(R.string.signal),
                        signalLabel(info.getRssi()), null);
            }
            addControlRow(rows, R.drawable.ic_settings, getString(R.string.open_network_settings), null,
                    () -> {
                        dismissDetail();
                        open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
                    });
        });
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
        detailPanel = floatingOver(panel, anchor, 340);
        riseIn(detailPanel, panel, anchor);
        panel.post(() -> {
            for (int i = 0; i < rows.getChildCount(); i++) {
                View v = rows.getChildAt(i);
                if (v.isFocusable()) {
                    v.requestFocus();
                    return;
                }
            }
        });
    }

    private void dismissDetail() {
        if (detailPanel != null) detailPanel.dismiss();
    }

    /** A round glass tile that closes the sheet and goes somewhere. */
    private void addTile(LinearLayout tiles, int iconRes, String title, Runnable action) {
        View tile = getLayoutInflater().inflate(R.layout.item_control_tile, tiles, false);
        ImageView icon = tile.findViewById(R.id.icon);
        icon.setImageResource(iconRes);
        ((TextView) tile.findViewById(R.id.title)).setText(title);
        // The disc turns near-white when focused, so the white glyph has to invert with it. Tinting
        // by hand works on the old Android versions where a state-list tint is ignored.
        tile.setOnFocusChangeListener((v, hasFocus) -> {
            icon.setColorFilter(hasFocus ? 0xFF111111 : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
            glideFocus(v, hasFocus, tiles);
        });
        tile.setOnClickListener(v -> {
            if (controlPanel != null) controlPanel.dismiss();
            action.run();
        });
        tiles.addView(tile);
    }

    /** The compact bottom pill. Icon and label flip to dark with the white focus fill. */
    private void addShortcutPill(LinearLayout row, int iconRes, String title, Runnable action) {
        addShortcutPill(row, iconRes, title, action, false);
    }

    /** Same pill, with an unread dot after the label ("Check for updates" while a build is waiting). */
    private void addShortcutPill(LinearLayout row, int iconRes, String title, Runnable action,
                                 boolean unread) {
        View pill = getLayoutInflater().inflate(R.layout.item_shortcut_pill, row, false);
        ImageView icon = pill.findViewById(R.id.icon);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
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
            icon.setColorFilter(hasFocus ? 0xFF111111 : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
            glideFocus(v, hasFocus, row);
        });
        pill.setOnClickListener(v -> {
            if (controlPanel != null) controlPanel.dismiss();
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
     * A label row plus a slider. Both sliders write live: volume through the audio service, brightness
     * through the settings provider once the write permission is in place.
     */
    private SeekBar addSlider(LinearLayout parent, int iconRes, String label,
                              final int min, final int max, int value, final boolean percent) {
        final int range = Math.max(1, max - min);

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(14), dp(4), dp(14), dp(4));
        block.setBackgroundResource(R.drawable.control_slider_bg);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFFFFFFFF); // setImageTintList is API 21; this works everywhere.
        head.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(15);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(10);
        head.addView(title, titleLp);

        final TextView valueView = new TextView(this);
        valueView.setTextColor(0x99FFFFFF);
        valueView.setTextSize(12);
        head.addView(valueView);
        block.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));

        SeekBar bar = new SeekBar(this);
        bar.setMax(range);
        bar.setProgress(Math.max(0, Math.min(range, value - min)));
        if (Build.VERSION.SDK_INT >= 21) {
            bar.setProgressTintList(ColorStateList.valueOf(0xFFFFFFFF));
            bar.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        }
        valueView.setText(percent ? Math.round(Math.max(0, value - min) * 100f / range) + "%"
                : String.valueOf(value));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int v = min + progress;
                valueView.setText(percent ? Math.round(progress * 100f / range) + "%"
                        : String.valueOf(v));
                if (!fromUser) return;
                if (percent) {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        try {
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                        } catch (Exception ignored) {
                        }
                    }
                } else {
                    setBrightness(v);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        block.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams blockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blockLp.setMargins(dp(4), dp(1), dp(4), dp(1));
        parent.addView(block, blockLp);
        return bar;
    }

    private void setBrightness(int value) {
        try {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                    Math.max(5, Math.min(255, value)));
        } catch (Exception ignored) {
        }
    }

}
