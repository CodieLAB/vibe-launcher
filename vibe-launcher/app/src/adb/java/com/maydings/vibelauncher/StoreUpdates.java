package com.maydings.vibelauncher;

import android.app.Activity;
import android.content.Context;

/** The adb build updates itself (Updater): the store build's Play updates (src/store) do nothing here. */
final class StoreUpdates {

    private StoreUpdates() {
    }

    static void check(Context context, Runnable done) {
    }

    static boolean available() {
        return false;
    }

    static void open(Activity activity) {
    }
}
