package com.maydings.vibelauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.input.InputManager;
import android.os.PowerManager;
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
 * - Home is kept from the stock launcher: a press opens Vibe Launcher, a hold the TV's Settings (Google TV's
 *   own long-press panel needs a system permission);
 * - a stock home screen that comes up anyway (Back out of an app) is covered with Vibe Launcher.
 *
 * It hears only from the stock launchers, and only that one of their screens came up; it reads nothing on
 * screen. It sees every key and passes on all but Home. While a game controller is connected it stops
 * looking at keys (with key filtering on, games stop seeing the controller's buttons as its own; upstream,
 * Steam Link with an Xbox controller): Home then reaches the stock launcher, which gets covered.
 */
public final class HomeKeyService extends AccessibilityService implements InputManager.InputDeviceListener {

    /** "package/activity" for every stock HOME activity, turned off ones included: firmware can turn them back on. */
    private final Set<String> stockHome = new HashSet<>();
    /** This Home press is kept from the system: its down went nowhere, so its up mustn't either. */
    private boolean homeKept;
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
            input.registerInputDeviceListener(this, null);
            updateKeyFilter();
        }
        // Direct-boot aware: Android 11 skips a service that isn't while the user is still locked at boot, and
        // some firmware (TCL, upstream) then turns it off for good. Home itself can only open after the unlock.
        UserManager users = getSystemService(UserManager.class);
        if (users == null || users.isUserUnlocked()) {
            probe();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                probe();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        if (users.isUserUnlocked()) {   // it may have happened between the check and the registration
            stopWaitingForUnlock();
            probe();
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
        if (pkg != null && cls != null && stockHome.contains(pkg + "/" + cls)) open(homeIntent());
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;   // every other key goes on as usual
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // Only where Home doesn't come here anyway, and with the TV awake (asleep, Home wakes it).
            PowerManager power = getSystemService(PowerManager.class);
            homeKept = !DefaultHome.isHome(this) && (power == null || power.isInteractive());
            return homeKept;
        }
        if (!homeKept) return false;
        homeKept = false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            // A hold, by how long it was down: the TV's Settings. Else Vibe Launcher.
            boolean held = event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout();
            open(held ? new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) : homeIntent());
        }
        return true;
    }

    /**
     * The stock home may already be on screen when Android binds this service (at boot, after an update of
     * Vibe Launcher), so no event for it comes. ProbeActivity opens and closes at once: whatever was in front
     * comes back and says so, and onAccessibilityEvent covers it only if it's the stock home.
     */
    private void probe() {
        if (stockHome.isEmpty()) return;
        open(new Intent(this, ProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    private Intent homeIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Not before the unlock: the stock home stays this once.
        }
    }

    /**
     * Looks at keys only while no game controller is connected. A controller has all the SOURCE_GAMEPAD and
     * SOURCE_JOYSTICK bits; either alone isn't one (upstream, on a TCL: its voice remote reports a joystick,
     * its virtual search keyboard a gamepad).
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
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }
}
