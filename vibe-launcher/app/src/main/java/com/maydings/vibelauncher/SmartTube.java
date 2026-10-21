package com.maydings.vibelauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SmartTube, the YouTube app whose home screen cards come from the signed-in user's own feed (the best
 * YouTube backdrop, with no key). Installs its latest stable release from its GitHub, checked against the
 * SHA-256 GitHub publishes for it, or opens it to sign in.
 */
final class SmartTube {

    /** The stable build first: the one this installs. The rest are its beta and older names. */
    static final String[] PACKAGES = {"org.smarttube.stable", "org.smarttube.beta",
            "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta", "com.teamsmart.videomanager.tv"};

    private static final String LATEST = "https://api.github.com/repos/yuliskov/SmartTube/releases/latest";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private SmartTube() {
    }

    /** The installed SmartTube's launch intent, or null when there's none. */
    static Intent launchIntent(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : PACKAGES) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) return i;
        }
        return null;
    }

    /** Finds the latest stable APK for this TV's processor and hands it to Updater to install. */
    static void install(Context context, Updater.InstallListener listener) {
        Context app = context.getApplicationContext();
        worker.execute(() -> {
            String[] apk;
            try {
                apk = latestApk();
            } catch (IOException | JSONException | RuntimeException e) {
                apk = null;   // offline, or GitHub busy
            }
            String[] found = apk;
            main.post(() -> {
                if (found == null) listener.onProblem(R.string.smarttube_failed);
                else Updater.installApk(app, found[0], found[1], PACKAGES[0], R.string.smarttube_allow_installs, listener);
            });
        });
    }

    /** The download address and SHA-256 of the build for this TV's processor (else the universal one). */
    private static String[] latestApk() throws IOException, JSONException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("User-Agent", "VibeLauncher");   // GitHub's API requires one
        JSONArray assets = new JSONObject(Http.getText(LATEST, headers)).getJSONArray("assets");
        // Names like SmartTube_stable_32.68_arm64-v8a.apk; the TV's preferred processor type first.
        String[] wanted = new String[Build.SUPPORTED_ABIS.length + 1];
        for (int i = 0; i < Build.SUPPORTED_ABIS.length; i++) wanted[i] = "_" + Build.SUPPORTED_ABIS[i] + ".apk";
        wanted[wanted.length - 1] = "_universal.apk";
        for (String suffix : wanted) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name"), digest = a.optString("digest");
                if (name.startsWith("SmartTube_stable_") && name.endsWith(suffix) && digest.startsWith("sha256:")) {
                    return new String[]{a.getString("browser_download_url"), digest.substring(7)};
                }
            }
        }
        return null;
    }
}
