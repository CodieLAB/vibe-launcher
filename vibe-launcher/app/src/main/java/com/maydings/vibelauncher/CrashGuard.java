package com.maydings.vibelauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;

/**
 * Escape hatch for when this is the only home screen and it keeps crashing. An app can't re-enable
 * another (that needs a system permission), so on the second crash within two minutes this opens the
 * App info page of Google's TV launcher, where one press of Enable gives the TV a working home again.
 * A single crash just restarts as usual.
 */
final class CrashGuard {

    private static final String FALLBACK_LAUNCHER = "com.google.android.tvlauncher";
    private static final long WINDOW_MS = 2 * 60 * 1000;

    private CrashGuard() {
    }

    static void install(Context context) {
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                SharedPreferences prefs = app.getSharedPreferences("crashes", Context.MODE_PRIVATE);
                long now = System.currentTimeMillis();
                int count = now - prefs.getLong("last", 0) < WINDOW_MS ? prefs.getInt("count", 0) + 1 : 1;
                prefs.edit().putLong("last", now).putInt("count", count).commit();   // the process is dying
                if (count >= 2) openFallbackInfo(app);
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /**
     * Started straight from the crash handler: the system carries out the request even though this
     * process is about to die. (An alarm doesn't work here: plain alarms are inexact and can be
     * deferred for minutes.)
     */
    private static void openFallbackInfo(Context app) {
        app.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + FALLBACK_LAUNCHER))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
}
