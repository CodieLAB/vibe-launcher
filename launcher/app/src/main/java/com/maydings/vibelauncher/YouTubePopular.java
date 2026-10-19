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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Popular YouTube videos in the TV's country, for the YouTube backdrop when SmartTube (the user's own
 * feed) isn't installed. Uses the YouTube Data API key from Vibe Launcher settings; one request costs
 * 1 unit of the free 10,000 a day, and the list is reused for a few hours.
 */
final class YouTubePopular {

    private static final long REFRESH_MS = 4L * 60 * 60 * 1000;

    private final VibeSettings settings;
    private final SharedPreferences cache;

    YouTubePopular(Context context) {
        settings = new VibeSettings(context);
        cache = context.getSharedPreferences("youtube_popular", Context.MODE_PRIVATE);
    }

    /** Video IDs, most popular first; empty without a key or offline. Network: worker thread only. */
    List<String> videoIds() {
        String key = settings.youtubeKey();
        if (key.isEmpty()) return Collections.emptyList();
        String region = region();
        long now = System.currentTimeMillis();
        String ids = cache.getString("ids", "");
        boolean fresh = now - cache.getLong("time", 0) < REFRESH_MS
                && region.equals(cache.getString("region", "")) && key.equals(cache.getString("key", ""));
        if (!fresh) {
            try {
                ids = fetch(key, region);
                cache.edit().putString("ids", ids).putLong("time", now).putString("region", region)
                        .putString("key", key).apply();
            } catch (IOException | JSONException | RuntimeException e) {
                // Offline or a bad key: use the last list if there is one.
            }
        }
        return ids.isEmpty() ? Collections.emptyList() : new ArrayList<>(Arrays.asList(ids.split(",")));
    }

    private static String fetch(String key, String region) throws IOException, JSONException {
        URL url = new URL("https://www.googleapis.com/youtube/v3/videos?part=id&chart=mostPopular&maxResults=30"
                + "&regionCode=" + region + "&key=" + Uri.encode(key));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) throw new IOException("YouTube " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            JSONArray items = new JSONObject(out.toString(StandardCharsets.UTF_8.name())).optJSONArray("items");
            List<String> ids = new ArrayList<>();
            for (int i = 0; items != null && i < items.length(); i++) ids.add(items.getJSONObject(i).getString("id"));
            return String.join(",", ids);
        } finally {
            c.disconnect();
        }
    }

    /** The TV's country (e.g. "US"), which YouTube's chart is per. */
    private static String region() {
        String country = Locale.getDefault().getCountry();
        return country.length() == 2 ? country.toUpperCase(Locale.ROOT) : "US";
    }
}
