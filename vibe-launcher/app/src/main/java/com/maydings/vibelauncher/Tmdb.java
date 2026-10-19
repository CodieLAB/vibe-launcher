package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Clean, full-resolution backdrops from TMDB for the titles apps put on their cards (Netflix's own art
 * is 853x480 with its logo and the title baked in). Uses the key from Vibe Launcher settings; does
 * nothing without one. Only exact title matches are used, and answers are remembered for a month so
 * each title costs two API calls at most.
 */
final class Tmdb {

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/original";
    private static final int CHOICES = 4;   // best few textless backdrops per title, picked at random
    private static final long FOUND_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long MISSING_MS = 3L * 24 * 60 * 60 * 1000;   // retry unmatched titles sooner

    private final VibeSettings settings;
    private final SharedPreferences cache;
    private final Random random = new Random();

    Tmdb(Context context) {
        settings = new VibeSettings(context);
        cache = context.getSharedPreferences("tmdb_cache", Context.MODE_PRIVATE);
    }

    boolean enabled() {
        return !settings.tmdbKey().isEmpty();
    }

    /** A backdrop URL for the title, or null when TMDB has no exact match. Network: worker thread only. */
    String backdrop(String title) {
        String key = normalize(title);
        if (key.isEmpty() || !enabled()) return null;
        String cached = cache.getString(key, null);
        long now = System.currentTimeMillis();
        if (cached != null) {
            int bar = cached.indexOf('|');
            long time = Long.parseLong(cached.substring(0, bar));
            String paths = cached.substring(bar + 1);
            if (now - time < (paths.isEmpty() ? MISSING_MS : FOUND_MS)) return choose(paths);
        }
        String paths;
        try {
            paths = lookup(title, key);
        } catch (IOException | JSONException | RuntimeException e) {
            return null;   // offline or a bad key: don't remember, try again next time
        }
        cache.edit().putString(key, now + "|" + paths).apply();
        return choose(paths);
    }

    private String choose(String paths) {
        if (paths.isEmpty()) return null;
        String[] list = paths.split(",");
        return IMAGES + list[random.nextInt(list.length)];
    }

    /** Comma-separated image paths for the best exact match, or "" for none. */
    private String lookup(String title, String key) throws IOException, JSONException {
        JSONArray results = get("/search/multi?include_adult=false&query=" + Uri.encode(title))
                .optJSONArray("results");
        JSONObject best = null;
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String type = r.optString("media_type");
            if (!type.equals("movie") && !type.equals("tv")) continue;
            boolean same = key.equals(normalize(r.optString(type.equals("tv") ? "name" : "title")))
                    || key.equals(normalize(r.optString(type.equals("tv") ? "original_name" : "original_title")));
            if (!same || r.optString("backdrop_path").isEmpty()) continue;
            if (best == null || r.optDouble("popularity") > best.optDouble("popularity")) best = r;
        }
        if (best == null) return "";

        // Backdrops tagged with no language have no text on them: no title, no logo.
        List<String> paths = new ArrayList<>();
        JSONArray backdrops = get("/" + best.getString("media_type") + "/" + best.getLong("id")
                + "/images?include_image_language=null").optJSONArray("backdrops");
        for (int i = 0; backdrops != null && i < backdrops.length() && paths.size() < CHOICES; i++) {
            JSONObject b = backdrops.getJSONObject(i);   // TMDB lists them best-rated first
            if (b.optInt("width") >= 1280 && b.optString("iso_639_1", "null").equals("null")) {
                paths.add(b.getString("file_path"));
            }
        }
        if (paths.isEmpty()) paths.add(best.getString("backdrop_path"));
        return String.join(",", paths);
    }

    private JSONObject get(String path) throws IOException, JSONException {
        String key = settings.tmdbKey();
        // A v4 read access token (a long JWT) goes in a header; a v3 API key goes in the URL.
        boolean token = key.startsWith("eyJ");
        String url = API + path + (token ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + Uri.encode(key));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/json");
            if (token) c.setRequestProperty("Authorization", "Bearer " + key);
            if (c.getResponseCode() != 200) throw new IOException("TMDB " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            c.disconnect();
        }
    }

    /** "The Office (U.S.)" and "the office u s" compare equal: lower case, letters and digits only. */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
