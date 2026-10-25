package com.maydings.vibelauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.UpdateAvailability;

/**
 * The store build's updates, which Play installs: whether it has a newer version (for the green dot on the
 * gear), and its own update screen. Installed from anywhere but Play, Play knows of no update, and there's
 * no dot. The adb build has its own Updater instead; its StoreUpdates (src/adb) does nothing.
 */
final class StoreUpdates {

    /** What Play said last; null before it has answered. */
    private static volatile AppUpdateInfo info;

    private StoreUpdates() {
    }

    /** Asks Play; done runs on the main thread once it has answered (not at all if it can't). */
    static void check(Context context, Runnable done) {
        AppUpdateManagerFactory.create(context.getApplicationContext()).getAppUpdateInfo()
                .addOnSuccessListener(answer -> {
                    info = answer;
                    done.run();
                });
        // A failure (not installed from Play, no Play Store, offline) leaves things as they were: no dot.
    }

    /** Play has a newer version. */
    static boolean available() {
        AppUpdateInfo i = info;
        return i != null && i.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE;
    }

    /** Play's update screen, or where it doesn't offer one, Vibe Launcher's page in Play. */
    static void open(Activity activity) {
        AppUpdateInfo i = info;
        if (i != null && i.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
            AppUpdateManager manager = AppUpdateManagerFactory.create(activity);
            manager.startUpdateFlow(i, activity, AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE))
                    .addOnFailureListener(e -> playPage(activity));
            return;
        }
        playPage(activity);
    }

    private static void playPage(Activity activity) {
        String pkg = activity.getPackageName();
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg))
                    .setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException none) {
                // No Play and no browser: nothing to open.
            }
        }
    }
}
