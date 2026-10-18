package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock. The order is the one the
 * user arranged with Move (saved in preferences); before that, the dock_apps favourites then alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        /** Long-press OK on a tile. */
        void onTileLongPress(int position, View card);

        /** A tile in move mode changed position; it keeps focus. */
        void onTileMoved(int position);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** The fixed last tile that opens the system Apps screen. */
        final boolean allApps;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(label, component, banner, icon, false);
        }

        Entry(String label, ComponentName component, Drawable banner, Drawable icon, boolean allApps) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.allApps = allApps;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    private static final String PREFS = "tiles";
    private static final String KEY_ORDER = "order";
    private static final String KEY_HIDDEN = "hidden";
    private static final Object PAYLOAD_STATE = new Object();

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();
    private final SharedPreferences prefs;
    /** Position of the tile being moved, or -1. */
    private int moving = -1;

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || userHidden().contains(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon));
        }
        // The saved arrangement wins; apps installed since then go at the end, alphabetically.
        String saved = prefs.getString(KEY_ORDER, "");
        List<String> order = saved.isEmpty() ? favourites : Arrays.asList(saved.split(","));
        Collections.sort(fresh, (a, b) -> {
            int fa = order.indexOf(a.pkg), fb = order.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        fresh.add(new Entry(ctx.getString(R.string.all_apps), new ComponentName(ctx, MainActivity.class),
                null, ctx.getDrawable(R.drawable.ic_all_apps), true));
        moving = -1;
        if (items.isEmpty()) {
            items.addAll(fresh);
            notifyDataSetChanged();
            return;
        }
        // Apply only what changed (hidden, unhidden, installed, uninstalled), so the rest of the
        // tiles glide into place instead of the grid redrawing.
        List<Entry> old = new ArrayList<>(items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return fresh.size();
            }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                return old.get(o).component.equals(fresh.get(n).component);
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                return old.get(o).label.equals(fresh.get(n).label);
            }
        });
        items.clear();
        items.addAll(fresh);
        diff.dispatchUpdatesTo(this);
        // Tiles that crossed the dock edge show or hide their name.
        notifyItemRangeChanged(0, items.size(), PAYLOAD_STATE);
    }

    // ---- hiding ----------------------------------------------------------------------------------

    /** Packages hidden from the long-press menu (on top of config.xml's hidden_apps). */
    Set<String> userHidden() {
        return new HashSet<>(prefs.getStringSet(KEY_HIDDEN, Collections.emptySet()));
    }

    void hide(int position) {
        if (!isApp(position)) return;
        Set<String> h = userHidden();
        h.add(items.get(position).pkg);
        prefs.edit().putStringSet(KEY_HIDDEN, h).apply();
        reload();
    }

    void unhide(String pkg) {
        Set<String> h = userHidden();
        h.remove(pkg);
        prefs.edit().putStringSet(KEY_HIDDEN, h).apply();
        reload();
    }

    /** Downloaded apps (or updates to built-in ones) can be uninstalled; built-in apps only hidden. */
    boolean canUninstall(int position) {
        if (!isApp(position)) return false;
        try {
            int flags = ctx.getPackageManager().getApplicationInfo(items.get(position).pkg, 0).flags;
            return (flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Opens the system uninstall confirmation; the grid reloads when Home resumes. */
    void uninstall(int position) {
        if (!canUninstall(position)) return;
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + items.get(position).pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** A real app tile (not the All apps tile). */
    boolean isApp(int position) {
        return position >= 0 && position < items.size() && !items.get(position).allApps;
    }

    // ---- move mode -------------------------------------------------------------------------------

    boolean isMoving() {
        return moving >= 0;
    }

    void startMove(int position) {
        if (!isApp(position)) return;
        moving = position;
        notifyItemChanged(position, PAYLOAD_STATE);
    }

    void stopMove() {
        if (moving < 0) return;
        int pos = moving;
        moving = -1;
        notifyItemChanged(pos, PAYLOAD_STATE);
        StringBuilder sb = new StringBuilder();
        for (Entry e : items) {
            if (e.allApps) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(e.pkg);
        }
        prefs.edit().putString(KEY_ORDER, sb.toString()).apply();
    }

    /** Handles a D-pad key for the tile being moved. */
    private boolean moveKey(int keyCode) {
        int from = moving, to;
        int last = items.size() - 2;   // the All apps tile stays at the end
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: to = from - 1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: to = from + 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: to = from - dockSize; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: to = Math.min(from + dockSize, last); break;
            default: return false;
        }
        if (to < 0 || to > last || to == from) return true;
        items.add(to, items.remove(from));
        moving = to;
        notifyItemMoved(from, to);
        // Tiles that crossed the dock edge show or hide their name.
        notifyItemRangeChanged(Math.min(from, to), Math.abs(to - from) + 1, PAYLOAD_STATE);
        listener.onTileMoved(to);
        return true;
    }

    void openAppInfo(int position) {
        if (!isApp(position)) return;
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + items.get(position).pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, this, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(h, position);
        } else {
            bindState(h, position);
        }
    }

    /** The parts that change while moving: dock label visibility and the move outline. */
    private void bindState(Holder h, int position) {
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        h.card.setForeground(position == moving ? ctx.getDrawable(R.drawable.move_outline) : null);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        bindState(h, position);
        h.name.setAlpha(0f);
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && !isMoving()) listener.onTileLongPress(pos, v);
            return true;
        });
    }

    private void launch(Entry e) {
        if (e.allApps) {
            openAllApps();
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    /** The system Apps screen (Settings > Apps). */
    private void openAllApps() {
        for (String action : new String[]{Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
                Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS}) {
            try {
                ctx.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    String packageAt(int position) {
        return isApp(position) ? items.get(position).pkg : null;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
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
        d.draw(c);
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

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        /** OK ends move mode on key down; swallow the matching key up so it doesn't launch the app. */
        private boolean swallowOkUp;

        Holder(View v, AppAdapter adapter, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
                if (event.getAction() == KeyEvent.ACTION_UP && ok && swallowOkUp) {
                    swallowOkUp = false;
                    return true;
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                // Held OK: remotes send repeats flagged as a long press. Open the menu from that too
                // (cancelling the touch-style timer so it can't fire a second time).
                if (ok && event.isLongPress() && !adapter.isMoving()) {
                    view.cancelLongPress();
                    view.performLongClick();
                    swallowOkUp = true;
                    return true;
                }
                if (adapter.isMoving()) {
                    if (ok || keyCode == KeyEvent.KEYCODE_BACK) {
                        adapter.stopMove();
                        swallowOkUp = ok;
                        return true;
                    }
                    return adapter.moveKey(keyCode);   // other keys (volume etc.) pass through
                }
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
