package com.maydings.vibelauncher;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private View backdrop;
    private ImageView backdropImage;
    private View backdropTitleBox;
    private ImageView backdropLogo;
    private TextView backdropTitle;
    private Backdrops backdrops;
    /** The dock app whose backdrop is showing or loading, or null. */
    private String backdropPkg;
    private TextView btnWifi;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    /** Scroll the glass target already accounts for, so it heads (mostly) to where the row will settle. */
    private int plannedScroll;
    /** The focused tile, so the glass can re-measure it once a scroll settles. */
    private View focusedCard;
    /** Share of a vertical scroll the glass runs ahead into before settling back: a gentle bounce. */
    private static final float GLASS_LEAD = 0.12f;
    private boolean glassShown;
    /** The dropdown currently on screen (Inputs or a tile menu), if any. */
    private PopupWindow openMenu;
    private int lastFocused = 0;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: after a reboot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /**
     * Apps installed, updated or removed while Home is on screen show up (or go) straight away, instead
     * of at the next return to Home. An update sends "removed" then "added": keep its tile meanwhile.
     */
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            adapter.reload();
            // Tiles may have moved: once they settle, bring the glass to wherever focus went.
            grid.postOnAnimation(() -> {
                RecyclerView.ItemAnimator animator = grid.getItemAnimator();
                if (animator != null) animator.isRunning(MainActivity.this::refreshFocusedTile);
                else refreshFocusedTile();
            });
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(this);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // Tiles per row: chosen in Vibe Launcher settings (4, 5 or 6), else config.xml.
        COLUMNS = new VibeSettings(this).columns(Math.max(1, getResources().getInteger(R.integer.columns)));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        backdrop = findViewById(R.id.backdrop);
        backdropImage = findViewById(R.id.backdropImage);
        backdropTitleBox = findViewById(R.id.backdropTitleBox);
        backdropLogo = findViewById(R.id.backdropLogo);
        backdropTitle = findViewById(R.id.backdropTitle);
        backdrops = new Backdrops(this);
        askPermissionsOnce();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        btnWifi = findViewById(R.id.btnWifi);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(new TileAnimator());
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);
        ((FrostView) findViewById(R.id.frost)).setSource(grid);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int tileH = AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(
                    (r - l) - grid.getPaddingLeft() - grid.getPaddingRight(), COLUMNS, density));
            int dockRow = AppAdapter.itemHeightPx(tileH, density) + (int) ((6 + 12) * density);   // plus DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                if (plannedScroll != 0 && (dy > 0) == (plannedScroll > 0)) {
                    int take = Math.abs(dy) >= Math.abs(plannedScroll) ? plannedScroll : dy;
                    plannedScroll -= take;
                    dy -= take;
                }
                if (dy == 0) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                // Settle exactly on the tile: the plan can be off by the few pixels the grid scrolled on
                // its own before onTileFocused ran, or the scroll stopped short at the end of the list.
                if (newState != RecyclerView.SCROLL_STATE_IDLE || !glassShown || focusedCard == null
                        || !focusedCard.isAttachedToWindow()) return;
                plannedScroll = 0;
                Rect r = new Rect(0, 0, focusedCard.getWidth(), focusedCard.getHeight());
                root.offsetDescendantRectToMyCoords(focusedCard, r);
                float y = r.exactCenterY() - glass.getLayoutParams().height / 2f;
                if (Math.abs(y - glassTargetY) < 0.5f) return;
                glassTargetY = y;
                springY.animateToFinalPosition(y);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(TileAnimator.STIFFNESS).setDampingRatio(TileAnimator.DAMPING));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> showNetwork());
        btnSettings.setOnClickListener(v -> showSettingsMenu(btnSettings));

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && (v.getId() == R.id.btnInputs
                    || (v.getId() == R.id.btnWifi && btnInputs.getVisibility() != View.VISIBLE))) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateInputsButton();
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        adapter.reload();
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);   // enabled or disabled
        packages.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, packages, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packageReceiver, packages);
        backdropPkg = null;
        backdrop.setAlpha(0f);
        backdrops.reshuffle();
        for (int i = 0; i < COLUMNS; i++) {
            String pkg = adapter.packageAt(i);
            if (pkg != null) backdrops.prefetch(pkg);
        }
        grid.scheduleLayoutAnimation();
        grid.requestLayout();   // reload() only touches changed tiles, so ask for the pass that plays the entrance
        updateWifiLabel();
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the tile we left from, or the first one. When a tile kept focus while we were away
        // (the usual case), no focus change follows, so bring back its glass, name and backdrop here:
        // the reset above cleared them.
        grid.post(() -> {
            if (!refreshFocusedTile()) focusTile(lastFocused);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
        unregisterReceiver(packageReceiver);
    }

    @Override
    protected void onDestroy() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
                // never registered
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    /** Puts the glass, name and backdrop on the tile that has focus; false when no tile has it. */
    private boolean refreshFocusedTile() {
        View focused = getCurrentFocus();
        RecyclerView.ViewHolder holder = focused != null ? grid.findContainingViewHolder(focused) : null;
        int position = holder != null ? holder.getBindingAdapterPosition() : RecyclerView.NO_POSITION;
        if (position == RecyclerView.NO_POSITION || focused.getId() != R.id.card) return false;
        onTileFocused(position, focused);
        return true;
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        focusedCard = card;
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * AppAdapter.FOCUS_SCALE);   // same corners as the tile
        }
        // Work out the scroll first so the glass goes straight to the row's final spot. Aiming at the
        // row's current spot and then following the scroll sent it too far up/down and back.
        View item = (View) card.getParent();
        boolean dock = position < COLUMNS;
        int dy;
        if (dock) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            dy = decoratedTop - grid.getPaddingTop();
        } else {
            // Scroll just enough so the whole glass (not only the tile) is visible.
            int top = item.getTop() - (int) pad * 2, bottom = item.getBottom() + (int) pad;
            int parentTop = grid.getPaddingTop(), parentBottom = grid.getHeight() - grid.getPaddingBottom();
            // When the glass is taller than the space (the 940E: 328 px of glass, 318 of room), favour the
            // top: fitting the bottom pushed the top out, so each sideways press scrolled 10 px back and forth.
            dy = top < parentTop ? top - parentTop
                    : (bottom > parentBottom ? Math.min(bottom - parentBottom, top - parentTop) : 0);
        }
        // The grid can't scroll past its ends (the bottom row), so only plan what is actually left.
        if (dy > 0) dy = Math.min(dy, grid.computeVerticalScrollRange()
                - grid.computeVerticalScrollExtent() - grid.computeVerticalScrollOffset());
        else if (dy < 0) dy = Math.max(dy, -grid.computeVerticalScrollOffset());
        if (Math.abs(dy) <= 1) dy = 0;
        plannedScroll = Math.round(dy * (1f - GLASS_LEAD));
        y -= plannedScroll;

        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        if (dy != 0) grid.smoothScrollBy(0, dy);
        if (dock) showHero(position, r.top - dy);
        else hideHero();
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
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
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
            heroName.setY(y);
        }
        showBackdrop(adapter.packageAt(position));
    }

    /** The app's still behind the dock when it has one; otherwise its colour glow (set above). */
    private void showBackdrop(String pkg) {
        if (pkg == null || pkg.equals(backdropPkg)) return;
        backdropPkg = pkg;
        backdrops.load(pkg, (loaded, bitmap, logo, title) -> {
            if (!heroShown || !loaded.equals(backdropPkg)) return;   // moved on while it loaded
            fadeBackdrop(bitmap, logo, title);
        });
    }

    /** Cross-fades to a still (or to nothing), and the glow the other way. */
    private void fadeBackdrop(Bitmap bitmap, Bitmap logo, String title) {
        if (bitmap == null) {
            backdrop.animate().alpha(0f).setDuration(300).start();
            ambient.animate().alpha(1f).setDuration(500).start();
            return;
        }
        Runnable in = () -> {
            backdropImage.setImageBitmap(bitmap);
            showBackdropTitle(logo, title);
            backdrop.animate().alpha(1f).setDuration(450).start();
            ambient.animate().alpha(0f).setDuration(450).start();
        };
        if (backdrop.getAlpha() > 0f) backdrop.animate().alpha(0f).setDuration(180).withEndAction(in).start();
        else in.run();
    }

    /** The film or show's logo, else its name, at the right, level with the app name; nothing otherwise. */
    private void showBackdropTitle(Bitmap logo, String title) {
        backdropLogo.setImageBitmap(logo);
        backdropLogo.setVisibility(logo != null ? View.VISIBLE : View.GONE);
        backdropTitle.setText(title);
        backdropTitle.setVisibility(logo == null && title != null ? View.VISIBLE : View.GONE);
        backdropTitleBox.post(() -> backdropTitleBox.setY(
                heroName.getY() + heroName.getHeight() - backdropTitleBox.getHeight()));
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
        backdrop.animate().alpha(0f).setDuration(300).start();
        backdropPkg = null;
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        // Handled here rather than by Android's focus search, which is what plays the click for left/right.
        grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_UP
                ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
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

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    /**
     * Granted at install over adb (-g). Installed from a file manager instead, ask once for both: TV
     * listings (the apps' home screen cards, for backdrops) and location (Android only reveals the
     * Wi-Fi network's name with it). Refused, the launcher keeps the colour glow and shows "Wi-Fi".
     */
    private void askPermissionsOnce() {
        if (getPreferences(MODE_PRIVATE).getBoolean("askedPermissions", false)) return;
        List<String> missing = new ArrayList<>();
        // Coarse with fine: Android 12+ refuses a request for fine location alone.
        for (String p : new String[]{Backdrops.PERMISSION, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("askedPermissions", true).apply();
        requestPermissions(missing.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        // Show what the new permissions unlock straight away: the network name and this visit's backdrops.
        updateWifiLabel();
        backdrops.reshuffle();
        backdropPkg = null;
    }

    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            // The pill names the physical connection; a VPN on top of it shows in the dropdown.
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) caps = underlying(cm);
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                String ssid = wifiName();
                if (ssid != null) text = ssid;
            }
        } catch (Exception ignored) {
        }
        btnWifi.setText(text);
        // The style's drawableTint also applies to an icon swapped in here.
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
    }

    /** The Ethernet (preferred) or Wi-Fi connection under a VPN, or null. */
    private static NetworkCapabilities underlying(ConnectivityManager cm) {
        NetworkCapabilities wifi = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return c;
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = c;
        }
        return wifi;
    }

    /** The connected Wi-Fi network's name, or null when it can't be read. */
    private String wifiName() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        String ssid = info != null ? info.getSSID() : null;
        if (ssid == null) return null;
        ssid = ssid.replace("\"", "").trim();
        return ssid.isEmpty() || ssid.contains("unknown") || ssid.equals("0x") ? null : ssid;
    }

    /** Dropdown under the network pill: each connected interface with its IPv4 address, then settings. */
    private void showNetwork() {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newMenu(panel);

        // Which device this is (Settings > About), to tell the TVs and the Shield apart.
        String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        name = name == null || name.trim().isEmpty() ? Build.MODEL : name.trim();
        addInfoRow(rows, R.drawable.ic_livetv, name, name.equalsIgnoreCase(Build.MODEL) ? null : Build.MODEL);
        addSeparator(rows);
        int firstNetworkRow = rows.getChildCount();

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                LinkProperties lp = cm.getLinkProperties(net);
                if (caps == null || lp == null) continue;
                boolean vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                String ip = null;
                for (LinkAddress a : lp.getLinkAddresses()) {
                    if (a.getAddress() instanceof Inet4Address) {
                        ip = a.getAddress().getHostAddress();
                        break;
                    }
                }
                // A VPN is worth listing even with only an IPv6 address (or none we can show).
                if (ip == null && vpn) {
                    ip = lp.getLinkAddresses().isEmpty() ? getString(R.string.connected)
                            : lp.getLinkAddresses().get(0).getAddress().getHostAddress();
                }
                if (ip == null) continue;
                String type;
                int icon = R.drawable.ic_wifi;
                // VPN first: Android often also marks a VPN with the Wi-Fi or Ethernet it runs over.
                if (vpn) {
                    type = getString(R.string.vpn_connected);
                    icon = R.drawable.ic_lock;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    type = getString(R.string.ethernet);
                    icon = R.drawable.ic_ethernet;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    String ssid = wifiName();
                    type = ssid != null ? getString(R.string.wifi) + " \u00B7 " + ssid : getString(R.string.wifi);
                } else {
                    type = getString(R.string.network);
                }
                String iface = lp.getInterfaceName();
                addInfoRow(rows, icon, iface != null ? type + " (" + iface + ")" : type, ip);
            }
        }
        if (rows.getChildCount() == firstNetworkRow) {
            addInfoRow(rows, R.drawable.ic_wifi, getString(R.string.offline), null);
        }
        addSeparator(rows);
        addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.network_settings), -1,
                () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        showMenu(popup, panel, rows, btnWifi, Gravity.END);
    }

    /** Dropdown under the gear: the launcher's own settings, or the system's. */
    private void showSettingsMenu(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newMenu(panel);
        // Usage on top, like the device and addresses in the network menu.
        // CPU only where the device lets apps read the counters (not on Android 8+ TVs like ours).
        CpuInfo cpuInfo = new CpuInfo();
        boolean cpuLive = cpuInfo.canMeasure();
        View cpu = cpuLive ? addMeterRow(rows, getString(R.string.cpu)) : null;
        View ram = addMeterRow(rows, getString(R.string.ram));
        addSeparator(rows);
        addInputRow(rows, popup, R.drawable.ic_all_apps, getString(R.string.vibe_settings), -1,
                () -> SettingsDialog.show(this, root));
        addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.android_settings), -1,
                () -> open(new Intent(Settings.ACTION_SETTINGS)));
        // Live while the menu is open.
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (cpuLive) updateCpu(cpu, cpuInfo.usage());
                updateRam(ram);
                handler.postDelayed(this, 1000);
            }
        };
        tick.run();
        popup.setOnDismissListener(() -> handler.removeCallbacks(tick));
        showMenu(popup, panel, rows, anchor, Gravity.END);
    }

    /** A read-only row with a usage bar; fill it with the returned view. */
    private View addMeterRow(LinearLayout rows, String label) {
        View row = getLayoutInflater().inflate(R.layout.item_meter, rows, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        row.setFocusable(false);
        rows.addView(row);
        return row;
    }

    private void updateCpu(View row, float usage) {
        if (usage < 0) return;
        ((ProgressBar) row.findViewById(R.id.bar)).setProgress((int) (1000 * usage));
        ((TextView) row.findViewById(R.id.value)).setText(getString(R.string.percent, Math.round(100 * usage)));
    }

    /** Memory in use: total minus what the system says it can hand out without killing apps. */
    private void updateRam(View row) {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        ProgressBar bar = row.findViewById(R.id.bar);
        bar.setProgress((int) (1000 * used / Math.max(1, mi.totalMem)));
        // Green while there's room, yellow under 100 MB free, red under 10 MB.
        long mb = 1024 * 1024;
        int color = mi.availMem > 100 * mb ? 0xFF34C759 : mi.availMem > 10 * mb ? 0xFFFFCC00 : 0xFFFF3B30;
        bar.setProgressTintList(ColorStateList.valueOf(color));
        float gb = 1024f * 1024 * 1024;
        ((TextView) row.findViewById(R.id.value)).setText(getString(R.string.ram_value, used / gb, mi.totalMem / gb));
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    /** Hides the Inputs pill on devices with nothing to switch to (e.g. a Shield: no HDMI-in, no tuner). */
    private void updateInputsButton() {
        boolean any = getLiveTvIntent() != null;
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        findViewById(R.id.btnInputs).setVisibility(any ? View.VISIBLE : View.GONE);
    }

    private Intent getLiveTvIntent() {
        Intent launch = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (launch == null) return null;
        // Launched plainly, the TV app resumes the last source, often an HDMI input (seen on the
        // XBR-940E). Ask it for the built-in tuner instead, when the TV has one.
        String tuner = builtInTuner();
        return tuner == null ? launch : new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tuner), "vnd.android.cursor.item/channel")
                .setPackage(liveTvPkg);
    }

    /** The TV's own tuner input (a system app, not e.g. an HDHomeRun app), or null. */
    private String builtInTuner() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        String tuner = null;
        if (tim == null) return null;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() == TvInputInfo.TYPE_TUNER
                    && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (tuner == null || info.getId().compareTo(tuner) < 0)) {
                tuner = info.getId();
            }
        }
        return tuner;
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = newMenu(panel);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = getLiveTvIntent();
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Right-aligned to the pill so a wider menu grows leftwards instead of off the screen edge.
        showMenu(popup, panel, rows, anchor, Gravity.END);
    }

    /** A glass dropdown (the Inputs look). Rows go in R.id.rows; show it with {@link #showMenu}. */
    private PopupWindow newMenu(View panel) {
        PopupWindow popup = new PopupWindow(panel, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        return popup;
    }

    private void showMenu(PopupWindow popup, View panel, LinearLayout rows, View anchor, int gravity) {
        // Fit the widest row (TVs label inputs with long names like "HDMI 4/ARC" or the connected
        // device), at least as wide as the anchor and capped so the menu stays compact.
        int minWidth = Math.max(anchor.getWidth(), (int) (150 * density));
        int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.4f);
        panel.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        popup.setWidth(Math.min(maxWidth, Math.max(minWidth, panel.getMeasuredWidth())));

        // Below the anchor, or above it when it's too close to the bottom of the screen (dock tiles).
        int gap = (int) (10 * density);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int spaceBelow = getResources().getDisplayMetrics().heightPixels - loc[1] - anchor.getHeight();
        boolean above = panel.getMeasuredHeight() + gap > spaceBelow;
        int yOff = above ? -(anchor.getHeight() + panel.getMeasuredHeight() + gap) : gap;

        // Drop in: fade and unfold from the edge nearest the anchor.
        panel.setAlpha(0f);
        panel.setPivotY(above ? panel.getMeasuredHeight() : 0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY((above ? 8 : -8) * density);
        popup.showAsDropDown(anchor, 0, yOff, gravity);
        openMenu = popup;
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (rows.getChildAt(i).isFocusable()) {
                rows.getChildAt(i).requestFocus();
                break;
            }
        }
    }

    // ---- tile menu: move, hide, app info ---------------------------------------------------------

    @Override
    public void onTileLongPress(int position, View card) {
        // Holding OK on a remote triggers both the long-press timer and the long-press key repeat;
        // only the first may open the menu, or a second copy stays behind holding focus.
        if (openMenu != null && openMenu.isShowing()) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newMenu(panel);
        if (adapter.isApp(position)) {
            addInputRow(rows, popup, R.drawable.ic_move, getString(R.string.move), -1, () -> {
                adapter.startMove(position);
                Toast.makeText(this, R.string.move_hint, Toast.LENGTH_LONG).show();
            });
            addInputRow(rows, popup, R.drawable.ic_delete, getString(R.string.remove), -1,
                    () -> showRemove(card, position));
            addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.app_info), -1,
                    () -> adapter.openAppInfo(position));
        }
        if (!adapter.userHidden().isEmpty()) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_all_apps, getString(R.string.show_hidden), -1,
                    () -> showHidden(card));
        }
        if (rows.getChildCount() == 0) return;
        showMenu(popup, panel, rows, card, Gravity.START);
    }

    /** Remove: hide the tile from Home, or uninstall the app (when it isn't built in). */
    private void showRemove(View anchor, int position) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newMenu(panel);
        addInputRow(rows, popup, R.drawable.ic_hide, getString(R.string.hide_from_home), -1, () -> {
            adapter.hide(position);
            grid.postOnAnimation(() -> focusTile(position));   // the tile sliding into its place
        });
        if (adapter.canUninstall(position)) {
            addInputRow(rows, popup, R.drawable.ic_delete, getString(R.string.uninstall), -1,
                    () -> adapter.uninstall(position));
        }
        showMenu(popup, panel, rows, anchor, Gravity.START);
    }

    /** Lists hidden apps; picking one puts it back at the end of the grid. */
    private void showHidden(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newMenu(panel);
        PackageManager pm = getPackageManager();
        List<String> pkgs = new ArrayList<>(adapter.userHidden());
        Collections.sort(pkgs, (a, b) -> appLabel(pm, a).compareToIgnoreCase(appLabel(pm, b)));
        for (String pkg : pkgs) {
            addInputRow(rows, popup, R.drawable.ic_hide, appLabel(pm, pkg), -1, () -> adapter.unhide(pkg));
        }
        if (rows.getChildCount() == 0) return;
        showMenu(popup, panel, rows, anchor, Gravity.START);
    }

    private static String appLabel(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    @Override
    public void onTileMoved(int position) {
        // The grid has fixed size, so it applies the move on the next animation frame without a layout
        // pass. Queue behind it on that frame, then bring the highlight (and dock title) to the new spot.
        grid.postOnAnimation(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
            if (vh == null) return;
            View card = vh.itemView.findViewById(R.id.card);
            if (!card.hasFocus()) card.requestFocus();
            onTileFocused(position, card);
        });
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    /** A read-only row (focus skips it): icon, title and, when given, a green dot with a status. */
    private void addInfoRow(LinearLayout rows, int iconRes, String title, String status) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView statusView = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        if (status != null) {
            statusView.setText(status);
        } else {
            statusView.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setFocusable(false);
        row.setClickable(false);
        rows.addView(row);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // Only definite states are shown: some TVs report "standby" for ports with nothing plugged in.
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
}
