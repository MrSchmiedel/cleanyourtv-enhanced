package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Rect;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enhanced fork: a "Continue watching" shelf just above the dock, read from the TV's
 * Watch Next table (TvContract.WatchNextPrograms, READ_TV_LISTINGS granted over ADB). Up from the dock lands
 * on it, Down from the top bar too; it hides while the grid below the dock is in use. Posters are the apps'
 * own web images, fetched with the INTERNET permission this fork adds, cached in memory and on disk.
 * Netflix and YouTube publish no Watch Next rows on this TV, so they never appear here.
 */
final class ContinueWatching {
    interface Host {
        void focusDock();
        void focusTopBar();
        void onShelfFocused();
    }

    static final class Item {
        String pkg, title, sub, poster, intentUri;
        float progress;
    }

    private static final String TAG = "ContinueWatching";
    private static final int MAX_ITEMS = 10;
    private static final String[] COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
    };

    private final Activity activity;
    private final Host host;
    private final FrameLayout root;
    private final RecyclerView grid;
    private final int columns;
    private final float density;
    private final LinearLayout shelf;
    private final RecyclerView row;
    private FrameLayout rowWrap;
    private final Adapter adapter = new Adapter();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final File disk;
    private final Map<String, String> appLabels = new HashMap<>();
    private List<Item> items = new ArrayList<>();
    private int lastFocusedItem;
    /** The dock's tile size and the gap between tiles, measured from the grid so the cards line up with its columns. */
    private int tileW, tileH, tileGap;
    private boolean hiddenForGrid;
    private boolean refreshing;

    static ContinueWatching create(Activity activity, FrameLayout root, RecyclerView grid, int columns, float density, Host host) {
        return new ContinueWatching(activity, root, grid, columns, density, host);
    }

    private ContinueWatching(Activity activity, FrameLayout root, RecyclerView grid, int columns, float density, Host host) {
        this.activity = activity;
        this.root = root;
        this.grid = grid;
        this.columns = columns;
        this.density = density;
        this.host = host;
        disk = new File(activity.getCacheDir(), "posters");
        //noinspection ResultOfMethodCallIgnored
        disk.mkdirs();

        shelf = new LinearLayout(activity);
        shelf.setOrientation(LinearLayout.VERTICAL);
        shelf.setClipChildren(false);
        shelf.setClipToPadding(false);
        TextView heading = new TextView(activity);
        heading.setText(R.string.continue_watching);
        heading.setAllCaps(true);
        heading.setTextSize(12f);
        heading.setLetterSpacing(0.14f);
        heading.setTextColor(0xA6FFFFFF);
        try { heading.setTypeface(activity.getResources().getFont(R.font.outfit_medium)); } catch (Exception ignored) { }
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = dp(10);
        hlp.bottomMargin = dp(2);
        shelf.addView(heading, hlp);
        // No automatic focus scrolling: RecyclerView's smooth scroll to reveal a focused child kept running after the
        // snap below and left the row between cards. The focus listener does all the scrolling, by whole cards.
        row = new RecyclerView(activity) {
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull View child, @NonNull Rect rect, boolean immediate) {
                return false;
            }
        };
        row.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        row.setAdapter(adapter);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(10), dp(8), dp(10), dp(4));
        row.setOverScrollMode(View.OVER_SCROLL_NEVER);
        row.setHorizontalScrollBarEnabled(false);
        row.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        row.setItemAnimator(null);
        rowWrap = new FrameLayout(activity);
        rowWrap.setClipChildren(true);
        rowWrap.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shelf.addView(rowWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shelf.setVisibility(View.GONE);
        root.addView(shelf, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (shelf.getVisibility() == View.VISIBLE) place(); });
        // The dock moves when the grid scrolls (a row below it was in use): follow it.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                // Hidden only while the grid has scrolled up (a row below the dock in use); otherwise it follows the dock.
                setHidden(grid.computeVerticalScrollOffset() > dp(20));
                if (shelf.getVisibility() == View.VISIBLE) place();
            }
        });
        shelf.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (b - t != ob - ot) place(); });
    }

    private int dp(float v) { return Math.round(v * density); }

    // ---- data -----------------------------------------------------------------------------------

    void refresh() {
        if (refreshing) return;
        refreshing = true;
        io.execute(() -> {
            List<Item> found = query();
            main.post(() -> {
                refreshing = false;
                apply(found);
            });
        });
    }

    private List<Item> query() {
        List<Item> out = new ArrayList<>();
        try (Cursor c = activity.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, COLUMNS, null, null, null)) {
            if (c == null) return out;
            List<Object[]> rows = new ArrayList<>();
            while (c.moveToNext()) {
                int type = c.isNull(5) ? -1 : c.getInt(5);
                if (type != 0 && type != 1) continue;           // continue and next; not "new" or watchlist promos
                String title = c.isNull(1) ? null : c.getString(1);
                if (title == null || title.trim().isEmpty()) continue;
                Item it = new Item();
                it.pkg = c.getString(0);
                it.title = title.trim();
                String ep = c.isNull(2) ? "" : c.getString(2);
                String s = c.isNull(3) ? "" : c.getString(3);
                String e = c.isNull(4) ? "" : c.getString(4);
                StringBuilder sub = new StringBuilder(appLabel(it.pkg));
                if (!s.isEmpty() && !"0".equals(s) && !e.isEmpty() && !"0".equals(e)) sub.append(" · S").append(s).append(" E").append(e);
                else if (!e.isEmpty() && !"0".equals(e)) sub.append(" · E").append(e);
                if (!ep.isEmpty() && !ep.equals(it.title)) sub.append(" · ").append(ep);
                it.sub = sub.toString();
                long pos = c.isNull(7) ? 0 : c.getLong(7);
                long dur = c.isNull(8) ? 0 : c.getLong(8);
                it.progress = type == 0 && pos > 0 && dur > 0 ? Math.max(0f, Math.min(1f, (float) pos / dur)) : 0f;
                String poster = c.isNull(9) ? null : c.getString(9);
                it.poster = poster != null && poster.startsWith("https://") ? poster : null;
                // Paramount+ publishes 100px-wide thumbnails; its image service takes any width in the path.
                if (it.poster != null && it.poster.contains("thumbnails.cbsig.net/w100-")) it.poster = it.poster.replace("thumbnails.cbsig.net/w100-", "thumbnails.cbsig.net/w480-");
                it.intentUri = c.isNull(10) ? null : c.getString(10);
                long engaged = c.isNull(6) ? 0 : c.getLong(6);
                rows.add(new Object[] {engaged, it});
            }
            rows.sort((a, b) -> Long.compare((Long) b[0], (Long) a[0]));
            Set<String> seen = new HashSet<>();
            for (Object[] r : rows) {
                Item it = (Item) r[1];
                if (!seen.add(it.pkg + "\n" + it.title)) continue;
                out.add(it);
                if (out.size() >= MAX_ITEMS) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "watch next query failed: " + e);
        }
        return out;
    }

    private String appLabel(String pkg) {
        String label = appLabels.get(pkg);
        if (label != null) return label;
        try {
            PackageManager pm = activity.getPackageManager();
            label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            label = pkg;
        }
        appLabels.put(pkg, label);
        return label;
    }

    private void apply(List<Item> found) {
        boolean hadFocus = row.hasFocus();
        items = found;
        if (lastFocusedItem >= items.size()) lastFocusedItem = 0;
        adapter.notifyDataSetChanged();
        if (items.isEmpty()) {
            shelf.setVisibility(View.GONE);
            if (hadFocus) host.focusDock();
            return;
        }
        if (!hiddenForGrid) {
            shelf.setVisibility(View.VISIBLE);
            place();
        }
    }

    // ---- placement and focus ---------------------------------------------------------------------

    /** Just above the dock, starting where the dock's first tile starts. */
    private void place() {
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        View first = lm == null ? null : lm.findViewByPosition(0);
        View card = first == null ? null : first.findViewById(R.id.card);
        if (card == null || card.getWidth() == 0) {
            main.postDelayed(this::place, 120);
            return;
        }
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        // Same size as the app tiles, same gap, same left edge: the shelf reads as another row of the grid.
        int gap = dp(14);
        View second = lm.findViewByPosition(1);
        View card2 = second == null ? null : second.findViewById(R.id.card);
        if (card2 != null && card2.getWidth() > 0) {
            Rect r2 = new Rect(0, 0, card2.getWidth(), card2.getHeight());
            root.offsetDescendantRectToMyCoords(card2, r2);
            if (r2.left > r.right) gap = r2.left - r.right;
        }
        if (tileW != r.width() || tileH != r.height() || tileGap != gap) {
            tileW = r.width();
            tileH = r.height();
            tileGap = gap;
            adapter.notifyDataSetChanged();
            main.post(this::place);   // the shelf's height changes with the cards: position it again once laid out
        }
        int left = Math.max(0, r.left - dp(10));
        // Exactly six cards: the seventh starts a full gap after the sixth, past the right padding, so it stays clipped.
        int width = columns * tileW + (columns - 1) * tileGap + row.getPaddingLeft() + row.getPaddingRight();
        ViewGroup.LayoutParams wlp = rowWrap.getLayoutParams();
        if (wlp.width != width) { wlp.width = width; rowWrap.setLayoutParams(wlp); }
        if (shelf.getPaddingLeft() != left) shelf.setPadding(left, 0, 0, 0);
        int height = shelf.getHeight();
        if (height == 0) {
            shelf.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED);
            height = shelf.getMeasuredHeight();
        }
        shelf.setY(r.top - height - dp(6));
    }

    /** A tile took focus: re-check the grid's scroll (rows that fit under the dock do not scroll it). */
    void onGridFocus(int position) {
        setHidden(grid.computeVerticalScrollOffset() > dp(20));
        if (!hiddenForGrid) main.postDelayed(this::place, 350);   // once the grid's smooth scroll back is done
    }

    private void setHidden(boolean hidden) {
        if (hidden == hiddenForGrid) return;
        hiddenForGrid = hidden;
        if (items.isEmpty()) return;
        if (hidden) {
            shelf.setVisibility(View.INVISIBLE);
        } else {
            shelf.setVisibility(View.VISIBLE);
            place();
        }
    }

    boolean focusFromDock() { return focusItem(lastFocusedItem); }

    boolean focusFromTopBar() { return focusItem(lastFocusedItem); }

    private boolean focusItem(int index) {
        if (items.isEmpty() || hiddenForGrid || shelf.getVisibility() != View.VISIBLE) return false;
        int i = Math.max(0, Math.min(index, items.size() - 1));
        RecyclerView.LayoutManager lm = row.getLayoutManager();
        View v = lm == null ? null : lm.findViewByPosition(i);
        if (v != null) {
            View card = v.findViewById(R.id.card);
            return card != null && card.requestFocus();
        }
        row.scrollToPosition(i);
        row.post(() -> {
            View w = lm == null ? null : lm.findViewByPosition(i);
            View card = w == null ? null : w.findViewById(R.id.card);
            if (card != null) card.requestFocus();
        });
        return true;
    }

    // ---- opening --------------------------------------------------------------------------------

    private void open(Item it) {
        Intent intent = null;
        try {
            if (it.intentUri != null && it.intentUri.startsWith("intent:")) {
                intent = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
            } else if (it.intentUri != null) {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(it.intentUri)).setPackage(it.pkg);
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "deep link failed for " + it.pkg + ": " + e);
        }
        Intent launch = activity.getPackageManager().getLeanbackLaunchIntentForPackage(it.pkg);
        if (launch == null) launch = activity.getPackageManager().getLaunchIntentForPackage(it.pkg);
        if (launch != null) {
            try { activity.startActivity(launch); } catch (Exception e) { Log.w(TAG, "launch failed: " + e); }
        }
    }

    // ---- posters --------------------------------------------------------------------------------

    private void loadPoster(String url, ImageView view) {
        view.setTag(url);
        if (url == null) { view.setImageDrawable(null); return; }
        Bitmap cached = memory.get(url);
        if (cached != null) { view.setImageBitmap(cached); return; }
        view.setImageDrawable(null);
        int targetWidth = (tileW > 0 ? tileW : dp(156)) * 2;
        io.execute(() -> {
            Bitmap bitmap = null;
            try {
                File file = new File(disk, hash(url));
                if (!file.exists() || file.length() == 0) download(url, file);
                bitmap = decode(file, targetWidth);
            } catch (Exception e) {
                Log.w(TAG, "poster failed: " + e);
            }
            if (bitmap == null) return;
            memory.put(url, bitmap);
            Bitmap done = bitmap;
            main.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(done); });
        });
    }

    private static void download(String url, File file) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36");
        File tmp = new File(file.getPath() + ".part");
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
        if (!tmp.renameTo(file)) throw new Exception("rename failed");
    }

    private static Bitmap decode(File file, int targetWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0) { //noinspection ResultOfMethodCallIgnored
            file.delete(); return null; }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getPath(), d);
    }

    private static String hash(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x));
        return b.toString();
    }

    // ---- adapter --------------------------------------------------------------------------------

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_continue, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Item it = items.get(position);
            int w = tileW > 0 ? tileW : dp(156);
            int hgt = tileH > 0 ? tileH : dp(88);
            ViewGroup.LayoutParams clp = h.card.getLayoutParams();
            if (clp.width != w || clp.height != hgt) { clp.width = w; clp.height = hgt; h.card.setLayoutParams(clp); }
            for (TextView t : new TextView[] {h.title, h.sub}) {
                ViewGroup.LayoutParams tlp = t.getLayoutParams();
                if (tlp.width != w) { tlp.width = w; t.setLayoutParams(tlp); }
            }
            ViewGroup.MarginLayoutParams ilp = (ViewGroup.MarginLayoutParams) h.itemView.getLayoutParams();
            int gap = tileGap > 0 ? tileGap : dp(14);
            if (ilp.rightMargin != gap) { ilp.rightMargin = gap; h.itemView.setLayoutParams(ilp); }
            h.title.setText(it.title);
            h.sub.setText(it.sub);
            ViewGroup.LayoutParams flp = h.fill.getLayoutParams();
            flp.width = Math.max(0, Math.round(w * it.progress));
            h.fill.setLayoutParams(flp);
            h.fill.setVisibility(it.progress > 0f ? View.VISIBLE : View.GONE);
            h.track.setVisibility(it.progress > 0f ? View.VISIBLE : View.GONE);
            loadPoster(it.poster, h.poster);
            boolean focused = h.card.hasFocus();
            h.title.setAlpha(focused ? 1f : 0f);
            h.sub.setAlpha(focused ? 1f : 0f);
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final View card, fill, track;
        final ImageView poster;
        final TextView title, sub;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            poster = v.findViewById(R.id.poster);
            fill = v.findViewById(R.id.fill);
            track = v.findViewById(R.id.track);
            title = v.findViewById(R.id.title);
            sub = v.findViewById(R.id.sub);
            float radius = dp(16);   // tile_bg's corner
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);
            card.setOnFocusChangeListener((view, focused) -> {
                if (focused) {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        // Scroll one card early, so the next card is already in view: moving right onto the fifth
                        // of the six visible cards (or left onto the second) shifts the row by whole cards. The row's
                        // own focus scroll is off (requestChildRectangleOnScreen), so this is the only scroll.
                        RecyclerView.LayoutManager lm = row.getLayoutManager();
                        if (lm instanceof LinearLayoutManager) {
                            LinearLayoutManager llm = (LinearLayoutManager) lm;
                            int first = llm.findFirstCompletelyVisibleItemPosition();
                            int last = llm.findLastCompletelyVisibleItemPosition();
                            int count = items.size();
                            int target = -1;
                            // Right: on the fifth of six the row shifts so the focused card sits fourth, two cards after it.
                            // Left: on the second it shifts so the focused card sits third, two cards before it.
                            if (last >= 0 && pos >= last - 1 && last < count - 1) target = pos - (columns - 3);
                            else if (first >= 0 && pos <= first + 1 && first > 0) target = pos - 2;
                            if (target >= 0 || pos > last || pos < first) {
                                if (target < 0) target = pos - (columns - 3);
                                target = Math.max(0, Math.min(target, count - columns));
                                row.stopScroll();
                                llm.scrollToPositionWithOffset(target, 0);   // 0 = at the padded start edge
                            }
                        }
                    }
                }
                card.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(140).start();
                title.animate().alpha(focused ? 1f : 0f).setDuration(140).start();
                sub.animate().alpha(focused ? 1f : 0f).setDuration(140).start();
                if (focused) {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) lastFocusedItem = pos;
                    host.onShelfFocused();
                }
            });
            card.setOnClickListener(view -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && pos < items.size()) open(items.get(pos));
            });
            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP: host.focusTopBar(); return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN: host.focusDock(); return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT: return pos <= 0;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: return pos >= items.size() - 1;
                    default: return false;
                }
            });
        }
    }
}
