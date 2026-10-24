package com.maydings.vibelauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * The Home button for the store build. Installed from an app store there's no adb to turn the stock
 * launcher off, and Android TV has no setting for the Home app, so Home keeps opening the stock one. The
 * user turns this on under Settings > Accessibility, and then:
 * - each time a stock home screen comes up (Home pressed in an app, or the firmware bringing it back),
 *   Vibe Launcher opens over it;
 * - a short press of Home with the stock home already on screen (no screen opens, so no event comes) opens
 *   it too. A long press is left alone: Google TV opens its quick settings with it.
 *
 * It hears only from the stock launchers, and only that one of their screens came up; it reads nothing on
 * screen. It sees every key but passes them all on. While a game controller is connected it stops looking
 * at keys: then Android routes every key through the service, and games stop seeing the controller's
 * buttons as its own (seen upstream with Steam Link and an Xbox controller).
 */
public final class HomeKeyService extends AccessibilityService implements InputManager.InputDeviceListener {

    /** How long after boot the first bind still counts as "at boot" for probeAtBoot. */
    private static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;
    /** Lets the system handle Home first, so ours opens over the stock home rather than under it. */
    private static final long HOME_KEY_DELAY_MS = 250;
    /** Home pressed in an app gives both the key and the stock home's screen: one opening is enough. */
    private static final long SAME_PRESS_MS = 1000;
    /** If the stock home keeps coming back by itself (firmware that insists on it), stop fighting it a while. */
    private static final int BURST = 4;
    private static final long BURST_MS = 10 * 1000L, PAUSE_MS = 60 * 1000L;

    /** "package/activity" for every stock HOME activity, turned off ones included: firmware can turn them back on. */
    private final Set<String> stockHome = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long[] recent = new long[BURST];
    private int next;
    private long pausedUntil, openedAt, homeKeyAt;
    private InputManager input;
    /** Waits for the user to unlock when Android binds this service at boot; null otherwise. */
    private BroadcastReceiver unlockReceiver;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Set<String> packages = findStockHome();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            // Screens from the stock launchers only; with none on this TV, none at all.
            if (packages.isEmpty()) {
                info.eventTypes = 0;
            } else {
                info.packageNames = packages.toArray(new String[0]);
            }
            setServiceInfo(info);
        }
        input = getSystemService(InputManager.class);
        if (input != null) {
            input.registerInputDeviceListener(this, handler);
            updateKeyFilter();
        }
        // Direct-boot aware: Android 11 skips a service that isn't while the user is still locked at boot, and
        // some firmware (TCL, upstream) then turns it off for good. Home itself can only open after the unlock.
        UserManager users = getSystemService(UserManager.class);
        if (users == null || users.isUserUnlocked()) {
            probeAtBoot();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                probeAtBoot();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        if (users.isUserUnlocked()) {   // it may have happened between the check and the registration
            stopWaitingForUnlock();
            probeAtBoot();
        }
    }

    /** Fills stockHome and returns the stock launchers' packages. */
    private Set<String> findStockHome() {
        stockHome.clear();
        Set<String> packages = new HashSet<>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
            ActivityInfo a = ri.activityInfo;
            // Not this launcher, Android's chooser, or the "Starting…" screen Android shows before the unlock.
            if (a == null || a.packageName.equals(getPackageName()) || a.packageName.equals("android")
                    || a.name.endsWith("FallbackHome")) continue;
            packages.add(a.packageName);
            stockHome.add(a.packageName + "/" + a.name);
            // An alias comes up under the activity it points to.
            if (a.targetActivity != null) stockHome.add(a.packageName + "/" + a.targetActivity);
        }
        return packages;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null || !stockHome.contains(pkg + "/" + cls)) return;
        long now = SystemClock.elapsedRealtime();
        // Home just pressed: the user asked for it, so it never counts as the firmware insisting.
        if (now - homeKeyAt < SAME_PRESS_MS) {
            openHome();
            return;
        }
        // The fourth time in ten seconds by itself: the firmware keeps bringing its home back, so let it have the screen.
        if (now < pausedUntil) return;
        long oldest = recent[next];
        recent[next] = now;
        next = (next + 1) % BURST;
        if (oldest != 0 && now - oldest < BURST_MS) {
            pausedUntil = now + PAUSE_MS;
            return;
        }
        openHome();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && event.getAction() == KeyEvent.ACTION_UP) {
            homeKeyAt = SystemClock.elapsedRealtime();
        }
        // Held time, not repeats: Android makes key repeats after the accessibility filter, so none come here.
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout()
                && !DefaultHome.isHome(this)) {
            handler.postDelayed(this::openHome, HOME_KEY_DELAY_MS);
        }
        return false;   // every key goes on as usual
    }

    /**
     * The stock home is often on screen before Android binds this service, so no event for it arrives: at
     * boot, and after an update of Vibe Launcher (which ends it, and the TV falls back to its own home while
     * the service starts again). Then, once, open ProbeActivity: when it closes, whatever was in front comes
     * back and sends an event, and onAccessibilityEvent covers it only if it's the stock home. An HDMI input
     * or an app the TV resumed is left alone. Not at other binds: the probe pauses whatever is playing for a
     * moment.
     */
    private void probeAtBoot() {
        if (stockHome.isEmpty()) return;
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        long updated = updateTime();
        // Device-protected: readable before the unlock too.
        SharedPreferences prefs = createDeviceProtectedStorageContext().getSharedPreferences("homekey", MODE_PRIVATE);
        boolean atBoot = boot >= 0 && boot != prefs.getInt("probeBoot", -1) && SystemClock.elapsedRealtime() < BOOT_WINDOW_MS;
        boolean afterUpdate = updated != prefs.getLong("probeUpdate", 0)
                && System.currentTimeMillis() - updated < BOOT_WINDOW_MS;
        if (!atBoot && !afterUpdate) return;
        prefs.edit().putInt("probeBoot", boot).putLong("probeUpdate", updated).apply();
        try {
            startActivity(new Intent(this, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Nothing to probe with: Home on the remote still works.
        }
    }

    /** When Vibe Launcher was last installed or updated, or 0. */
    private long updateTime() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private void openHome() {
        long now = SystemClock.elapsedRealtime();
        if (now - openedAt < SAME_PRESS_MS) return;
        openedAt = now;
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Not before the unlock: the stock home stays this once.
        }
    }

    /**
     * Looks at keys only while no game controller is connected. A controller has all the SOURCE_GAMEPAD and
     * SOURCE_JOYSTICK bits; either alone isn't one (upstream, on a TCL: its voice remote reports a joystick,
     * its virtual search keyboard a gamepad). Meanwhile Home on the stock home waits for the controller to go.
     */
    private void updateKeyFilter() {
        boolean gamepad = false;
        for (int id : input.getInputDeviceIds()) {
            InputDevice d = input.getInputDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    && (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                gamepad = true;
                break;
            }
        }
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        boolean filtering = (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
        if (filtering != gamepad) return;   // already right
        info.flags ^= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        updateKeyFilter();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        updateKeyFilter();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        updateKeyFilter();
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    @Override
    public void onDestroy() {
        stopWaitingForUnlock();
        if (input != null) input.unregisterInputDeviceListener(this);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }
}
