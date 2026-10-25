package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The desk. Position 0 is one full-width dock row holding a resizable number of slots; after it
 * come folder tiles, then the alphabetical apps. Dock slots and folders draw art that the grid
 * tiles do not (banners, mini icons), so each has its own view type.
 */
public class AppAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a grid tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        /** Long-press on a tile: pin to the dock, or unpin when already pinned. */
        void onTileLongPressed(Entry entry, boolean alreadyPinned);

        /** About to start another app: stop anything the desk is playing first. */
        void onTileLaunching();

        /** Focus moved onto a dock slot. [entry] is null for the empty "+" slot. */
        void onDockFocused(int index, Entry entry, View card);

        /** D-pad Up/Down on a dock slot. Return true when handled. */
        boolean onDockVerticalKey(int index, int keyCode);

        /** OK on an empty dock slot: open the favourites picker. */
        void onDockEmptyPressed();

        /** Open the folder's own panel. */
        void onFolderOpen(FolderStore.Folder folder);

        /** Long-press on a folder tile: add apps or dissolve it. */
        void onFolderLongPressed(FolderStore.Folder folder);
    }

    static final int TYPE_DOCK = 0;
    static final int TYPE_FOLDER = 1;
    static final int TYPE_APP = 2;
    private static final long DOCK_ID = Long.MIN_VALUE;

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Preinstalled and not updated: the system uninstaller can only disable it, not remove it. */
        final boolean system;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon, boolean system) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.system = system;
        }
    }

    /**
     * How much a focused tile grows. This is a state, not a motion: it stays on even with reduced
     * motion, because the glass is fitted around it and a tile that does not grow would sit under a
     * white slab instead of inside a halo.
     */
    static final float FOCUS_SCALE = 1.10f;
    /** Names under the grid cards are always readable, a touch dimmer until their tile is focused. */
    private static final float NAME_ALPHA = 0.78f;

    /** Weak boxes get the same look, applied instantly instead of tweened. */
    static boolean reducedMotion;

    /** Called once at startup from MainActivity, before the first layout pass. */
    static void setReducedMotion(boolean reduced) {
        reducedMotion = reduced;
    }
    /** Design size of a tile (dp). Tiles grow past it at four columns and shrink below it at eight. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Widest a tile is allowed to get, in dp. The cell decides the size so 4 / 6 / 8 per row are
     * visibly three different sizes; this cap only stops four columns on a very wide screen from
     * turning into posters.
     */
    static final float MAX_W_DP = 240f;

    /**
     * Tile width for a grid of this inner width: the cell, minus room for the focus zoom and the glass
     * highlight. Four columns near the cap, six near the design size, eight small.
     *
     * <p>The size has to come from the cell: item_app.xml ships one fixed 180dp card, so from five
     * columns on a 960dp-wide screen the card no longer fits its cell — the neighbours touch, and at
     * six they overlap. Deriving the width instead keeps the breathing room at any column count.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int max = Math.round(MAX_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return Math.round(DESIGN_W_DP * density);
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(max, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    /** 16dp at the design width, proportionally less on a smaller tile, so the shape stays the same. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    /**
     * Trims a card's contents to its rounded corners. The tile background is already round, but a
     * full-bleed banner paints over it, so without this the corners come out square. 5.0+ only;
     * below that the banner's own 4dp inset keeps it inside the shape.
     */
    static void clipToCorners(View card, float density) {
        if (android.os.Build.VERSION.SDK_INT < 21) return;
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        cornerRadius(view.getWidth(), density));
            }
        });
        card.setClipToOutline(true);
    }

    /** Mi Home on Xiaomi TV: installed, but usually with no LAUNCHER activity to discover. */
    private static final String[] HIDDEN_LAUNCHERS = {
            "com.xiaomi.smarthome.tv",
            "com.xiaomi.smarthome.tv.global2",
            "com.xiaomi.smarthome.tv.global4",
            "com.xiaomi.smarthome",
    };

    /** Package or label fragments that name a file manager worth a tile. */
    private static final String[] FILE_TOOLS = {
            "filemanager", "file_manager", "fileexplorer", "file_explorer", "filebrowser",
            "documentsui", "filemaster", "文件", "资源管理", "file manager", "file explorer",
    };

    /** The same for the vendor's own app store, which people look for by name. */
    private static final String[] STORE_TOOLS = {
            "appstore", "app_store", "appmarket", "app_market", "appcenter", "应用商店",
            "应用市场", "应用商城", "应用中心", "商店", "市场", "商城", "app store", "market",
    };

    private static boolean matchesTool(String pkg, String label, String[] words) {
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        String l = label.toLowerCase(java.util.Locale.ROOT);
        for (String w : words) {
            if (p.contains(w) || l.contains(w)) return true;
        }
        return false;
    }

    private final Context ctx;
    private final TileListener listener;
    /** Columns in the app grid; the dock sizes its own tiles independently of this. */
    private final int columns;
    /** The user's dock, from config.xml defaults or their own edits (DockStore). */
    private final DockStore dock;
    private final FolderStore folders;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    /** Non-dock, non-folder apps, alphabetical. */
    private final List<Entry> items = new ArrayList<>();
    private final List<FolderStore.Folder> folderRows = new ArrayList<>();
    /** Every installed entry, including the ones living in the dock or a folder. */
    private final java.util.Map<String, Entry> byPkg = new java.util.HashMap<>();
    /** Width the dock slots were last sized for; 0 when the row was bound before the grid had one. */
    private int dockBoundWidth;

    AppAdapter(Context ctx, TileListener listener, int columns, List<String> dockDefaults) {
        this.ctx = ctx;
        this.listener = listener;
        this.columns = columns;
        this.dock = new DockStore(ctx, dockDefaults, columns);
        this.folders = new FolderStore(ctx);
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    DockStore dock() {
        return dock;
    }

    FolderStore folders() {
        return folders;
    }

    /** How many slots the dock draws (user-resizable, independent of the grid's columns). */
    int dockSize() {
        return dock.capacity();
    }

    /** App and folder tiles, excluding the dock row. */
    int contentCount() {
        return items.size() + folderRows.size();
    }

    /**
     * Lets go of every tile and its artwork. Called when the system says memory is short while Home is
     * in the background: the icons are the biggest thing we hold, and they can be loaded again when
     * the user comes back.
     */
    void clear() {
        items.clear();
        folderRows.clear();
        byPkg.clear();
        notifyDataSetChanged();
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(probe, 0));
        // Phone-style apps (ZeroTier Fix and friends) only publish the plain LAUNCHER category, so a
        // leanback-only desk hides them while they are plainly installed. Merge the ordinary
        // launcher entries in, one tile per package, keeping the leanback entry when there is one.
        Set<String> seenPackages = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo != null) seenPackages.add(ri.activityInfo.packageName);
        }
        probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo != null && seenPackages.add(ri.activityInfo.packageName)) {
                found.add(ri);
            }
        }
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            // PackageItemInfo.loadBanner is API 20. On Android 4.x Dalvik resolves the method at the
            // call site and dies with NoSuchMethodError the moment Home resumes, so below 20 there
            // is simply no banner; the icon path below already handles a null.
            Drawable banner = null;
            if (android.os.Build.VERSION.SDK_INT >= 20) {
                banner = ri.activityInfo.loadBanner(pm);
                if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            }
            Drawable icon = fitIcon(ctx, ri.loadIcon(pm));
            String label = String.valueOf(ri.loadLabel(pm));
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            boolean system = ai != null
                    && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon,
                    system));
        }
        // Xiaomi TV ships Mi Home with no LAUNCHER entry at all — PatchWall opens it by component,
        // so the desk never saw it. Add it back when one of the known packages is installed.
        Set<String> listed = new HashSet<>();
        for (Entry e : fresh) listed.add(e.pkg);
        for (String pkg : HIDDEN_LAUNCHERS) {
            if (listed.contains(pkg) || hidden.contains(pkg)) continue;
            Entry e = hiddenLauncher(pm, pkg);
            if (e != null) {
                fresh.add(e);
                listed.add(pkg);
            }
        }
        // Vendor file managers and app stores often ship without a LAUNCHER entry — PatchWall
        // opens them by component — so the desk never showed them. Match package or label rather
        // than a package table that only ever covers the TVs we happened to buy.
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            String pkg = ai.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || listed.contains(pkg)) {
                continue;
            }
            String label = String.valueOf(ai.loadLabel(pm));
            if (!matchesTool(pkg, label, FILE_TOOLS) && !matchesTool(pkg, label, STORE_TOOLS)) {
                continue;
            }
            Entry e = hiddenLauncher(pm, pkg);
            if (e != null) {
                fresh.add(e);
                listed.add(pkg);
            }
        }
        byPkg.clear();
        for (Entry e : fresh) byPkg.put(e.pkg, e);

        // Dock and folders own their apps; a package must not also appear in the grid. Folders are
        // pruned of anything uninstalled or now pinned, and disappear when nothing is left.
        Set<String> installed = new HashSet<>(byPkg.keySet());
        // A pinned package that was uninstalled, or one left beyond the capacity after a resize,
        // must not keep a slot: clean it out and write the list back so the picker agrees.
        List<String> pinned = dock.apps();
        if (pinned.isEmpty() && !dock.isCustomised()) {
            // Out of the box the dock shows the first row of apps, the way the 2.0 desk did. Once
            // the user edits the dock, an empty dock means empty and must not refill itself.
            List<Entry> first = new ArrayList<>(fresh);
            Collections.sort(first, (a, b) -> a.label.compareToIgnoreCase(b.label));
            for (Entry e : first) {
                if (pinned.size() >= dock.capacity()) break;
                pinned.add(e.pkg);
            }
            if (!pinned.isEmpty()) dock.set(pinned);
        }
        List<String> clean = new ArrayList<>();
        for (String pkg : pinned) {
            if (byPkg.containsKey(pkg) && !clean.contains(pkg) && clean.size() < dock.capacity()) {
                clean.add(pkg);
            }
        }
        if (!clean.equals(pinned)) dock.set(clean);
        Set<String> docked = new HashSet<>(clean);
        folders.prune(installed, docked);
        folderRows.clear();
        folderRows.addAll(folders.all());
        Set<String> foldered = new HashSet<>();
        for (FolderStore.Folder f : folderRows) foldered.addAll(f.pkgs);

        items.clear();
        for (Entry e : fresh) {
            if (docked.contains(e.pkg) || foldered.contains(e.pkg)) continue;
            items.add(e);
        }
        Collections.sort(items, (a, b) -> a.label.compareToIgnoreCase(b.label));
        notifyDataSetChanged();
    }

    /**
     * The Mi Home builds that never publish a launcher activity. Returns null when the package is
     * absent or has no activity that can be started from outside.
     */
    private Entry hiddenLauncher(PackageManager pm, String pkg) {
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        Intent launch = null;
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        }
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        ComponentName comp = launch != null ? launch.getComponent() : null;
        if (comp == null) {
            // No LAUNCHER / INFO entry: fall back to the package's MAIN activity, which is what the
            // vendor home's own card starts.
            Intent main = new Intent(Intent.ACTION_MAIN).setPackage(pkg);
            ResolveInfo best = null;
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
                String n = ri.activityInfo.name.toLowerCase();
                if (n.endsWith("mainactivity") || n.contains("home")) {
                    best = ri;
                    break;
                }
                if (best == null) best = ri;
            }
            if (best != null) comp = new ComponentName(pkg, best.activityInfo.name);
        }
        if (comp == null) return null;
        String label = String.valueOf(ai.loadLabel(pm));
        if (label.isEmpty() || label.equals(pkg)) label = ctx.getString(R.string.mi_home);
        Drawable banner = android.os.Build.VERSION.SDK_INT >= 20 ? ai.loadBanner(pm) : null;
        boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
        return new Entry(label, comp, banner, fitIcon(ctx, ai.loadIcon(pm)), system);
    }

    /**
     * Icons arrive in whatever shape their author shipped: legacy art fills its canvas, adaptive
     * icons carry a wide safe-zone padding, and TV apps often use 16:9 logos. Drawn straight into
     * the tile they read as different sizes. Render each one once, crop the transparent border,
     * then scale what is left so its longest side is exactly the icon box — same artwork, same
     * optical size.
     */
    static Drawable fitIcon(Context context, Drawable d) {
        if (d == null) return null;
        float density = context.getResources().getDisplayMetrics().density;
        int n = Math.max(1, Math.round(96 * density));
        int iw = d.getIntrinsicWidth();
        int ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) { iw = ih = n; }
        float fit = Math.min(n / (float) iw, n / (float) ih);
        int dw = Math.max(1, Math.round(iw * fit));
        int dh = Math.max(1, Math.round(ih * fit));
        Bitmap src;
        try {
            src = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return d;
        }
        Canvas canvas = new Canvas(src);
        d.setBounds((n - dw) / 2, (n - dh) / 2, (n - dw) / 2 + dw, (n - dh) / 2 + dh);
        try {
            d.draw(canvas);
        } catch (Throwable t) {
            // Some vendor drawables (a LayerDrawable wrapping a zero-size VectorDrawable on an
            // API 22 MiTV) throw while rendering into a bitmap. The untrimmed icon is fine.
            src.recycle();
            return d;
        }

        int[] px = new int[n * n];
        src.getPixels(px, 0, n, 0, 0, n, n);
        int l = n, t = n, r = -1, b = -1, opaque = 0;
        for (int y = 0; y < n; y++) {
            int row = y * n;
            for (int x = 0; x < n; x++) {
                if ((px[row + x] >>> 24) > 16) {
                    opaque++;
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > b) b = y;
                }
            }
        }
        if (r < l || b < t) {
            src.recycle();
            return d;
        }

        // Only an icon that is a solid block of artwork has a background colour to carry across
        // the tile. A logo drawn on transparency does not, and painting its own colour around it
        // would swallow the logo.
        int fill = opaque * 100 >= (r - l + 1) * (b - t + 1) * 85
                ? edgeColor(px, n, l, t, r, b) : 0;
        int bw = r - l + 1, bh = b - t + 1;
        // Keep the artwork at render size. The tile decides how big the icon is shown; shrinking
        // the bitmap to a fixed 64dp first only made it soft once the icon was drawn larger.
        int size = n;
        float scale = size / (float) Math.max(bw, bh);
        int ow = Math.max(1, Math.round(bw * scale));
        int oh = Math.max(1, Math.round(bh * scale));
        // Centred in the same square every icon gets: a square logo fills it, a 16:9 logo is
        // exactly as wide but shorter, instead of both being judged by their transparent margins.
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas oc = new Canvas(out);
        oc.drawBitmap(src, new Rect(l, t, r + 1, b + 1),
                new RectF((size - ow) / 2f, (size - oh) / 2f, (size + ow) / 2f, (size + oh) / 2f),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return new IconDrawable(context.getResources(), out, fill);
    }

    /**
     * Most common opaque colour along the cropped artwork's edge, in 4-bit-per-channel buckets.
     * 0 unless that one colour covers a good part of the border, so a photo or a busy edge does
     * not get a band of some random colour. Used to carry the icon's background across the tile.
     */
    private static int edgeColor(int[] px, int n, int l, int t, int r, int b) {
        int[] counts = new int[4096];
        int total = 0;
        for (int x = l; x <= r; x++) {
            tally(counts, px[t * n + x]);
            tally(counts, px[b * n + x]);
            total += 2;
        }
        for (int y = t; y <= b; y++) {
            tally(counts, px[y * n + l]);
            tally(counts, px[y * n + r]);
            total += 2;
        }
        int best = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[best]) best = i;
        }
        if (counts[best] * 10 < total * 4) return 0;
        int rr = ((best >> 8) & 0xF) * 17;
        int gg = ((best >> 4) & 0xF) * 17;
        int bb = (best & 0xF) * 17;
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    private static void tally(int[] counts, int argb) {
        if ((argb >>> 24) < 200) return;
        counts[((argb >> 20) & 0xF) << 8 | ((argb >> 12) & 0xF) << 4 | ((argb >> 4) & 0xF)]++;
    }


    /** A fitted icon plus the background colour its edge showed before it was trimmed. */
    static final class IconDrawable extends BitmapDrawable {
        final int fill;

        IconDrawable(android.content.res.Resources res, Bitmap bitmap, int fill) {
            super(res, bitmap);
            this.fill = fill;
        }
    }

    /**
     * Draws a fitted icon across the whole tile: the artwork centred at 92% of the tile height,
     * with the tile itself painted in the artwork's own edge colour. Tiles are 16:9 and app icons
     * are square; without the colour the icon read as a small square in a wide empty box.
     */
    static final class TileIcon extends Drawable {
        private final IconDrawable art;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint band = new Paint();
        private final RectF dst = new RectF();

        TileIcon(IconDrawable art) {
            this.art = art;
            band.setColor(art.fill);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            Bitmap bmp = art.getBitmap();
            if (b.isEmpty() || bmp == null || bmp.isRecycled()) return;
            // The whole icon, at 92% of the tile height. The artwork keeps its own padding and
            // proportions; cropping that padding away made logos run into the tile's edges.
            float scale = b.height() * 0.92f / bmp.getHeight();
            float width = bmp.getWidth() * scale;
            float left = b.left + (b.width() - width) / 2f;
            float top = b.top + (b.height() - b.height() * 0.92f) / 2f;
            canvas.drawRect(b, band);
            dst.set(left, top, left + width, top + b.height() * 0.92f);
            canvas.drawBitmap(bmp, null, dst, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            band.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
            band.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) return TYPE_DOCK;
        return isFolderPos(position) ? TYPE_FOLDER : TYPE_APP;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_DOCK) {
            View v = inflater.inflate(R.layout.item_dock_bar, parent, false);
            return new DockHolder(v);
        }
        float density = parent.getResources().getDisplayMetrics().density;
        int parentWidth = parent.getWidth() > 0
                ? parent.getWidth() : parent.getResources().getDisplayMetrics().widthPixels;
        int inner = parentWidth - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, columns, density);
        int h = tileHeightPx(w);
        if (viewType == TYPE_FOLDER) {
            View v = inflater.inflate(R.layout.item_folder, parent, false);
            sizeTile(v, w, h, density);
            return new FolderHolder(v, listener);
        }
        View v = inflater.inflate(R.layout.item_app, parent, false);
        sizeTile(v, w, h, density);
        return new Holder(v, listener);
    }

    /** Size the card and the name under it. The XML's 180dp is only the design size. */
    private static void sizeTile(View v, int w, int h, float density) {
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        // The icon covers the whole card; the artwork's own background is carried across the wide
        // part by TileIcon. The 4dp inset is only for API < 21, where clipToOutline is unavailable
        // and a full-card square would show its corners outside the rounded background.
        View icon = v.findViewById(R.id.icon);
        if (icon != null) {
            FrameLayout.LayoutParams iconLp = (FrameLayout.LayoutParams) icon.getLayoutParams();
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                iconLp.width = w;
                iconLp.height = h;
            } else {
                int iconSize = Math.max(Math.round(24 * density), h - Math.round(8 * density));
                iconLp.width = iconSize;
                iconLp.height = iconSize;
            }
            iconLp.gravity = Gravity.CENTER;
            iconLp.topMargin = 0;
            iconLp.bottomMargin = 0;
            icon.setLayoutParams(iconLp);
        }
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof DockHolder) {
            bindDock((DockHolder) holder);
        } else if (holder instanceof FolderHolder) {
            FolderStore.Folder f = folderRows.get(position - 1);
            bindFolder((FolderHolder) holder, f, position);
        } else {
            bindApp((Holder) holder, items.get(position - 1 - folderRows.size()));
        }
    }

    private void bindApp(Holder h, Entry e) {
        h.name.setText(e.label);
        h.name.setVisibility(View.VISIBLE);
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        float density = h.card.getResources().getDisplayMetrics().density;
        // Grid names live under the card now, visible at rest; focus just lifts and brightens them.
        h.name.setAlpha(focused ? 1f : NAME_ALPHA);
        h.name.setTranslationY(focused ? density * 4f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        // Grid tiles are all icons at one size; only the dock shows banners, so the grid does not
        // read as "some icons are bigger".
        h.banner.setImageDrawable(null);
        h.banner.setVisibility(View.GONE);
        h.icon.setImageDrawable(e.icon instanceof IconDrawable
                ? new TileIcon((IconDrawable) e.icon) : e.icon);
        h.icon.setVisibility(View.VISIBLE);
        h.label.setVisibility(View.GONE);
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            if (listener != null) listener.onTileLongPressed(e, dock.isPinned(e.pkg));
            return true;
        });
    }

    private void bindFolder(FolderHolder h, FolderStore.Folder f, int position) {
        h.name.setText(f.name);
        boolean focused = h.card.isFocused();
        float density = h.card.getResources().getDisplayMetrics().density;
        h.name.setAlpha(focused ? 1f : NAME_ALPHA);
        h.name.setTranslationY(focused ? density * 4f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);

        h.mini.removeAllViews();
        int n = Math.min(f.pkgs.size(), 9);
        int cols = n <= 4 ? 2 : 3;
        int pad = Math.round(10 * density);
        int gap = Math.round(5 * density);
        int cardW = h.card.getWidth() > 0 ? h.card.getWidth() : h.card.getLayoutParams().width;
        int cell = Math.max(1, (cardW - 2 * pad - (cols - 1) * gap) / cols);
        android.widget.GridLayout grid = new android.widget.GridLayout(ctx);
        grid.setColumnCount(cols);
        grid.setAlignmentMode(android.widget.GridLayout.ALIGN_BOUNDS);
        for (int i = 0; i < n; i++) {
            Entry e = byPkg.get(f.pkgs.get(i));
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (e != null) iv.setImageDrawable(e.icon);
            android.widget.GridLayout.LayoutParams lp =
                    new android.widget.GridLayout.LayoutParams(
                            android.widget.GridLayout.spec(i / cols),
                            android.widget.GridLayout.spec(i % cols));
            lp.width = cell;
            lp.height = cell;
            lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
            grid.addView(iv, lp);
        }
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.gravity = Gravity.CENTER;
        h.mini.addView(grid, gp);
        h.count.setVisibility(f.pkgs.size() > 9 ? View.VISIBLE : View.GONE);
        h.count.setText(String.valueOf(f.pkgs.size()));

        h.card.setOnClickListener(v -> {
            if (listener != null) listener.onFolderOpen(f);
        });
        h.card.setOnLongClickListener(v -> {
            if (listener != null) listener.onFolderLongPressed(f);
            return true;
        });
        // Folder tiles sit in the same grid as apps; Up/Down must leave them the ordinary way.
        h.card.setTag(position);
    }

    /** One dock row: capacity slots, filled apps first and "+" slots after them. */
    private void bindDock(DockHolder h) {
        LinearLayout row = h.row;
        row.removeAllViews();
        float density = ctx.getResources().getDisplayMetrics().density;
        int capacity = dock.capacity();
        List<String> pinned = dock.apps();
        // The dock item is as wide as the grid, which is narrower than the screen; using the
        // screen here made an eight-slot band wider than its row and clipped at both ends.
        View parent = (View) h.itemView.getParent();
        int parentW = parent != null ? parent.getWidth() : 0;
        // On a cold start the first bind runs before the grid has been measured. The screen width is
        // the only number available then, and it is wider than the grid, so the row overflows and the
        // first and last slots get squeezed. MainActivity calls resizeDockRow once the width is known.
        dockBoundWidth = parentW;
        int screenW = parentW > 0
                ? parentW : ctx.getResources().getDisplayMetrics().widthPixels;
        int bandPad = Math.round(14 * density);
        int gap = Math.round(14 * density);
        int normal = tileWidthPx(screenW, columns, density);
        int fit = Math.max(1, (screenW - 2 * bandPad - (capacity - 1) * gap) / capacity);
        int w = Math.min(normal, fit);
        int ht = tileHeightPx(w);

        for (int i = 0; i < capacity; i++) {
            Entry e = i < pinned.size() ? byPkg.get(pinned.get(i)) : null;
            View tile = LayoutInflater.from(ctx).inflate(R.layout.item_app, row, false);
            sizeTile(tile, w, ht, density);
            View name = tile.findViewById(R.id.name);
            name.setVisibility(View.GONE);              // the dock has no labels
            View banner = tile.findViewById(R.id.banner);
            ImageView icon = tile.findViewById(R.id.icon);
            TextView label = tile.findViewById(R.id.label);
            label.setVisibility(View.GONE);
            View card = tile.findViewById(R.id.card);
            // These tiles are inflated here rather than by Holder, so the holder's round clip has
            // to be applied by hand or the dock banners paint square corners over the tile.
            clipToCorners(card, density);
            card.setScaleX(card.isFocused() ? FOCUS_SCALE : 1f);
            card.setScaleY(card.isFocused() ? FOCUS_SCALE : 1f);

            if (e != null) {
                if (e.banner != null) {
                    ((ImageView) banner).setImageDrawable(e.banner);
                    banner.setVisibility(View.VISIBLE);
                    icon.setVisibility(View.GONE);
                } else {
                    banner.setVisibility(View.GONE);
                    icon.setImageDrawable(e.icon);
                    icon.setVisibility(View.VISIBLE);
                }
                card.setOnClickListener(v -> launch(e));
                card.setOnLongClickListener(v -> {
                    if (listener != null) listener.onTileLongPressed(e, true);
                    return true;
                });
            } else if (i == pinned.size()) {
                // The next free slot carries the "+"; further empty slots stay bare so a resized
                // dock reads as space rather than a wall of plus signs.
                banner.setVisibility(View.GONE);
                FrameLayout.LayoutParams ip = (FrameLayout.LayoutParams) icon.getLayoutParams();
                ip.width = Math.round(26 * density);
                ip.height = Math.round(26 * density);
                ip.gravity = Gravity.CENTER;
                icon.setLayoutParams(ip);
                icon.setImageResource(R.drawable.ic_plus);
                icon.setColorFilter(0x99FFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
                icon.setVisibility(View.VISIBLE);
                card.setOnClickListener(v -> {
                    if (listener != null) listener.onDockEmptyPressed();
                });
                card.setOnLongClickListener(v -> true);
            } else {
                banner.setVisibility(View.GONE);
                icon.setVisibility(View.GONE);
                card.setOnClickListener(v -> {
                    if (listener != null) listener.onDockEmptyPressed();
                });
                card.setOnLongClickListener(v -> true);
            }

            final int index = i;
            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                if (reducedMotion) {
                    view.setScaleX(s);
                    view.setScaleY(s);
                } else {
                    view.animate().scaleX(s).scaleY(s)
                            .setDuration(focused ? 260 : 180)
                            .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                            .start();
                }
                if (focused && listener != null) listener.onDockFocused(index, e, view);
            });
            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return index == 0;
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return index == capacity - 1;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return listener != null && listener.onDockVerticalKey(index, keyCode);
                }
                return false;
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = i == capacity - 1 ? 0 : gap;
            row.addView(tile, lp);
        }
    }

    /** True when the dock was sized for something other than the width the grid has now. */
    boolean dockWidthStale(int width) {
        return width > 0 && width != dockBoundWidth;
    }

    /**
     * Re-sizes the slots of an already bound dock row in place. Used on a cold start, where the first
     * bind has no grid width to work from: rebinding would throw away the focused card, resizing keeps it.
     */
    void resizeDockRow(View dockItem) {
        View rowView = dockItem.findViewById(R.id.dockRow);
        View parent = (View) dockItem.getParent();
        if (!(rowView instanceof LinearLayout) || parent == null || parent.getWidth() <= 0) return;
        LinearLayout row = (LinearLayout) rowView;
        float density = ctx.getResources().getDisplayMetrics().density;
        int capacity = dock.capacity();
        int screenW = parent.getWidth();
        int bandPad = Math.round(14 * density);
        int gap = Math.round(14 * density);
        int normal = tileWidthPx(screenW, columns, density);
        int fit = Math.max(1, (screenW - 2 * bandPad - (capacity - 1) * gap) / capacity);
        int w = Math.min(normal, fit);
        int ht = tileHeightPx(w);
        List<String> pinned = dock.apps();
        for (int i = 0; i < row.getChildCount(); i++) {
            View tile = row.getChildAt(i);
            if (i < pinned.size() && byPkg.get(pinned.get(i)) != null) {
                sizeTile(tile, w, ht, density);
            } else {
                // Empty slots carry a small "+", not a full-bleed icon; sizeTile would blow it up.
                View card = tile.findViewById(R.id.card);
                ViewGroup.LayoutParams cardLp = card.getLayoutParams();
                cardLp.width = w;
                cardLp.height = ht;
                card.setLayoutParams(cardLp);
            }
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
            lp.width = w;
            tile.setLayoutParams(lp);
        }
        dockBoundWidth = screenW;
    }

    static int dockTileWidthPx(int screenWidthPx, int columns, int capacity, float density) {
        int bandPad = Math.round(14 * density);
        int gap = Math.round(14 * density);
        int normal = tileWidthPx(screenWidthPx, columns, density);
        int fit = Math.max(1, (screenWidthPx - 2 * bandPad - (capacity - 1) * gap) / capacity);
        return Math.min(normal, fit);
    }

    /**
     * Height of the whole dock row: the card, item_app's 8+8, the root's 6+12 and the band's 6+6.
     * Under-count this and the last row of cards hangs off the bottom of the screen.
     */
    static int dockRowHeightPx(int screenWidthPx, int columns, int capacity, float density) {
        int w = dockTileWidthPx(screenWidthPx, columns, capacity, density);
        return tileHeightPx(w) + Math.round(46 * density);
    }

    /** Launch the tile at a position (used by the dot strip's OK button). */
    void launchAt(int position) {
        Entry e = entryAt(position);
        if (e != null) launch(e);
    }

    void launchPkg(String pkg) {
        Entry e = byPkg.get(pkg);
        if (e != null) launch(e);
    }

    void launchEntry(Entry e) {
        if (e != null) launch(e);
    }

    private void launch(Entry e) {
        if (listener != null) listener.onTileLaunching();
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return 1 + folderRows.size() + items.size();
    }

    @Override
    public long getItemId(int position) {
        if (position == 0) return DOCK_ID;
        FolderStore.Folder f = folderAt(position);
        if (f != null) return 0x4000000000000000L | (f.id.hashCode() & 0xFFFFFFFFL);
        Entry e = entryAt(position);
        return e == null ? 0 : e.component.flattenToShortString().hashCode();
    }

    private boolean isFolderPos(int position) {
        return position >= 1 && position <= folderRows.size();
    }

    /** The app at a grid position, or null for the dock row and folder tiles. */
    Entry entryAt(int position) {
        int i = position - 1 - folderRows.size();
        return i >= 0 && i < items.size() ? items.get(i) : null;
    }

    /** The folder at a position, or null. */
    FolderStore.Folder folderAt(int position) {
        return isFolderPos(position) ? folderRows.get(position - 1) : null;
    }

    /** The installed entry for a package, or null when it is gone. */
    Entry entryForPkg(String pkg) {
        return byPkg.get(pkg);
    }

    /** Every installed entry, dock and folder members included. */
    List<Entry> entries() {
        return new ArrayList<>(byPkg.values());
    }

    /** Entry for a dock slot, or null for an empty slot. */
    Entry dockEntryAt(int index) {
        List<String> pinned = dock.apps();
        if (index < 0 || index >= pinned.size() || index >= dock.capacity()) return null;
        return byPkg.get(pinned.get(index));
    }

    /** Dock slot of this package, or -1. */
    int dockIndexOf(String pkg) {
        if (pkg == null) return -1;
        int i = dock.apps().indexOf(pkg);
        return i >= 0 && i < dock.capacity() ? i : -1;
    }

    /** Package name of the app at a position, or null. */
    String pkgAt(int position) {
        Entry e = entryAt(position);
        return e == null ? null : e.pkg;
    }

    /** Stable key for the item at a position (its component or folder id), or null for the dock. */
    String keyAt(int position) {
        if (position == 0) return null;
        FolderStore.Folder f = folderAt(position);
        if (f != null) return "folder:" + f.id;
        Entry e = entryAt(position);
        return e == null ? null : e.component.flattenToShortString();
    }

    /** Position of the app/folder with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        if (key.startsWith("folder:")) {
            String id = key.substring("folder:".length());
            for (int i = 0; i < folderRows.size(); i++) {
                if (folderRows.get(i).id.equals(id)) return i + 1;
            }
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) {
                return 1 + folderRows.size() + i;
            }
        }
        return -1;
    }

    String labelAt(int position) {
        if (position == 0) return "";
        FolderStore.Folder f = folderAt(position);
        if (f != null) return f.name;
        Entry e = entryAt(position);
        return e == null ? "" : e.label;
    }

    /** A vivid colour sampled from one entry's banner (or icon), cached on the entry. */
    int ambientColorOf(Entry e) {
        if (e == null) return 0xFF444444;
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position == 0) return 0xFF444444;
        FolderStore.Folder f = folderAt(position);
        if (f != null) {
            for (String pkg : f.pkgs) {
                Entry e = byPkg.get(pkg);
                if (e != null) {
                    if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
                    return e.ambient;
                }
            }
            return 0xFF444444;
        }
        Entry e = entryAt(position);
        if (e == null) return 0xFF444444;
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        try {
            d.draw(c);
        } catch (Throwable t) {
            // Same vendor-drawable hazard as fitIcon; a missing ambient tint is not worth a crash.
            d.setBounds(saved);
            bmp.recycle();
            return 0xFF444444;
        }
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class DockHolder extends RecyclerView.ViewHolder {
        final LinearLayout row;

        DockHolder(View v) {
            super(v);
            row = v.findViewById(R.id.dockRow);
        }
    }

    static final class FolderHolder extends RecyclerView.ViewHolder {
        final View card;
        final FrameLayout mini;
        final TextView count;
        final TextView name;

        FolderHolder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            mini = v.findViewById(R.id.mini);
            count = v.findViewById(R.id.count);
            name = v.findViewById(R.id.name);
            final float density = v.getResources().getDisplayMetrics().density;
            clipToCorners(card, density);
            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });
            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                if (reducedMotion) {
                    view.setScaleX(s);
                    view.setScaleY(s);
                    name.setAlpha(focused ? 1f : NAME_ALPHA);
                    name.setTranslationY(focused ? density * 4f : 0f);
                } else {
                    view.animate().scaleX(s).scaleY(s)
                            .setDuration(focused ? 260 : 180)
                            .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                            .start();
                    name.animate().alpha(focused ? 1f : NAME_ALPHA)
                            .translationY(focused ? density * 4f : 0f)
                            .setDuration(220).start();
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            // Outline/clip is 5.0+. Below that the tile's own rounded background still shows; only
            // the rim drawable stops being trimmed to the corners.
            clipToCorners(card, density);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                if (reducedMotion) {
                    view.setScaleX(s);
                    view.setScaleY(s);
                    name.setAlpha(focused ? 1f : NAME_ALPHA);
                    name.setTranslationY(focused ? density * 4f : 0f);
                } else {
                    view.animate().scaleX(s).scaleY(s)
                            .setDuration(focused ? 260 : 180)
                            .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                            .start();
                    name.animate().alpha(focused ? 1f : NAME_ALPHA)
                            .translationY(focused ? density * 4f : 0f)
                            .setDuration(220).start();
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
