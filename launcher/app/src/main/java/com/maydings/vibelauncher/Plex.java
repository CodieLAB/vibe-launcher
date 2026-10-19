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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Backdrops for the Plex tile from the user's own server: the fan art of what's on Continue Watching
 * and recently added. The new Plex app publishes no home screen cards. Uses the Plex token from Vibe
 * Launcher settings to find the server through plex.tv (its secure local address first), and has the
 * server scale the art (stored at up to 4K) to the screen.
 */
final class Plex {

    private static final long SERVER_MS = 24L * 60 * 60 * 1000;
    private static final long ART_MS = 30L * 60 * 1000;

    private final VibeSettings settings;
    private final SharedPreferences cache;
    private final int width, height;
    private List<String> art = Collections.emptyList();
    private long artTime;
    private String artToken = "";

    Plex(Context context, int width, int height) {
        settings = new VibeSettings(context);
        cache = context.getSharedPreferences("plex", Context.MODE_PRIVATE);
        this.width = width;
        this.height = height;
        if (cache.getString("clientId", null) == null) {
            cache.edit().putString("clientId", UUID.randomUUID().toString()).apply();
        }
    }

    /** Image URLs (token included) of backdrops on the server; empty without a token or server. Worker thread. */
    synchronized List<String> backdrops() {
        String token = settings.plexToken();
        if (token.isEmpty()) return Collections.emptyList();
        long now = System.currentTimeMillis();
        if (token.equals(artToken) && now - artTime < ART_MS && !art.isEmpty()) return art;
        for (int attempt = 0; attempt < 2; attempt++) {
            String[] server = server(token, attempt > 0);
            if (server == null) break;
            try {
                art = fetchArt(server[0], server[1]);
                artTime = now;
                artToken = token;
                return art;
            } catch (IOException | JSONException | RuntimeException e) {
                // The cached address may be stale (new IP, server moved): look it up again once.
            }
        }
        return art;   // the last good list, if any
    }

    /** {base URI, server access token} for the first owned (else any) online server, cached for a day. */
    private String[] server(String token, boolean refresh) {
        long now = System.currentTimeMillis();
        if (!refresh && token.equals(cache.getString("forToken", "")) && now - cache.getLong("time", 0) < SERVER_MS) {
            String uri = cache.getString("uri", null), access = cache.getString("access", null);
            if (uri != null && access != null) return new String[]{uri, access};
        }
        try {
            JSONArray resources = new JSONArray(get("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=0", token));
            JSONObject best = null;
            for (int i = 0; i < resources.length(); i++) {
                JSONObject r = resources.getJSONObject(i);
                if (!r.optString("provides").contains("server") || r.optJSONArray("connections") == null) continue;
                if (best == null || (r.optBoolean("owned") && !best.optBoolean("owned"))) best = r;
            }
            if (best == null) return null;
            JSONArray connections = best.getJSONArray("connections");
            String uri = null;
            for (int pass = 0; pass < 2 && uri == null; pass++) {   // local first, then remote
                for (int i = 0; i < connections.length(); i++) {
                    JSONObject c = connections.getJSONObject(i);
                    if (c.optBoolean("relay") || c.optBoolean("local") != (pass == 0)) continue;
                    uri = c.getString("uri");
                    break;
                }
            }
            if (uri == null) return null;
            String access = best.optString("accessToken", token);
            cache.edit().putString("uri", uri).putString("access", access).putString("forToken", token)
                    .putLong("time", now).apply();
            return new String[]{uri, access};
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    private List<String> fetchArt(String base, String token) throws IOException, JSONException {
        Set<String> paths = new LinkedHashSet<>();
        for (String path : new String[]{"/library/onDeck", "/library/recentlyAdded"}) {
            JSONArray items = new JSONObject(get(base + path, token)).getJSONObject("MediaContainer")
                    .optJSONArray("Metadata");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject m = items.getJSONObject(i);
                String a = m.optString("grandparentArt", m.optString("art"));   // a show's art for its episodes
                if (!a.isEmpty()) paths.add(a);
            }
        }
        List<String> urls = new ArrayList<>();
        for (String p : paths) {
            urls.add(base + "/photo/:/transcode?width=" + width + "&height=" + height + "&minSize=1&upscale=0"
                    + "&url=" + Uri.encode(p) + "&X-Plex-Token=" + Uri.encode(token));
        }
        return urls;
    }

    private String get(String url, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("X-Plex-Token", token);
            c.setRequestProperty("X-Plex-Product", "Vibe Launcher");
            c.setRequestProperty("X-Plex-Client-Identifier", cache.getString("clientId", "vibe-launcher"));
            if (c.getResponseCode() != 200) throw new IOException("Plex " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
