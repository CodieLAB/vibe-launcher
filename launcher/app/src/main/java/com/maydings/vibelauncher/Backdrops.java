package com.maydings.vibelauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backdrop stills for dock apps, scraped from public web pages (no accounts or API keys). The big
 * streaming apps have a recipe naming a page with good artwork; any other app falls back to the TV
 * screenshots on its public Play Store listing (its interface or the developer's promo shots).
 * Candidates are downloaded and kept only if they are big and wide, then cached on disk and refreshed
 * once a day. Apps where nothing qualifies keep the colour glow.
 */
final class Backdrops {

    interface Callback {
        void onBackdrop(String pkg, Bitmap bitmap);
    }

    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.0 Safari/605.1.15";
    private static final long REFRESH_MS = 24L * 60 * 60 * 1000;
    private static final int MIN_WIDTH = 1200;
    private static final float MIN_ASPECT = 1.45f, MAX_ASPECT = 2.4f;
    private static final int KEEP = 8;            // images cached per app
    private static final int MAX_CANDIDATES = 30; // images downloaded per refresh
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final Pattern NOT_ARTWORK = Pattern.compile(
            "logo|icon|favicon|social|share|sprite|badge|avatar|og[-_]?image", Pattern.CASE_INSENSITIVE);

    /** A page with good artwork for an app, and how to pull image URLs out of it. */
    private static final class Recipe {
        final String page;
        final Pattern image;     // null: take every photo on the page
        final String template;   // "%s" is replaced by the first group, or null to use the match

        Recipe(String page, String imageRegex, String template) {
            this.page = page;
            this.image = imageRegex != null ? Pattern.compile(imageRegex) : null;
            this.template = template;
        }
    }

    private static final Map<String, Recipe> RECIPES = new HashMap<>();

    static {
        // YouTube's logged-out home is empty; the Movies storefront lists films with 1080p artwork.
        RECIPES.put("com.google.android.youtube.tv", new Recipe("https://www.youtube.com/feed/storefront",
                "\"videoId\":\"([A-Za-z0-9_-]{11})\"", "https://i.ytimg.com/vi/%s/maxresdefault.jpg"));
        // Netflix's own pages load artwork with JavaScript; Tudum (its show news site) has it inline.
        RECIPES.put("com.netflix.ninja", new Recipe("https://www.netflix.com/tudum",
                "https://dnm\\.nflximg\\.net/api/v6/[^\"'\\s)\\\\]+", null));
        RECIPES.put("com.apple.atve.androidtv.appletv", new Recipe("https://tv.apple.com/",
                "https://is\\d-ssl\\.mzstatic\\.com/image/thumb/[^\"'\\s)\\\\]+?/\\d{3,4}x\\d{3,4}[^\"'\\s)\\\\/]*?\\.jpg", null));
        // Plex's free movies and TV page links TMDB artwork (posters and backdrops; the size check
        // keeps the wide ones). Fetched at 1280 wide rather than the multi-megabyte originals.
        RECIPES.put("com.plexapp.android", new Recipe("https://watch.plex.tv/",
                "https://image\\.tmdb\\.org/t/p/original/([A-Za-z0-9]+)\\.jpg", "https://image.tmdb.org/t/p/w1280/%s.jpg"));
        // These homepages only have a few banners; the size check below picks the usable ones.
        RECIPES.put("com.cbs.ott", new Recipe("https://www.paramountplus.com/", null, null));
        RECIPES.put("com.amazon.amazonvideo.livingroom", new Recipe("https://www.primevideo.com/", null, null));
        RECIPES.put("com.peacocktv.peacockandroid", new Recipe("https://www.peacocktv.com/", null, null));
        // Disney+, Hulu and Max were tried: their artwork loads with JavaScript or is too small.
    }

    /** Every app gets a try: its recipe, or else its Play Store screenshots. */
    static boolean supports(String pkg) {
        return pkg != null;
    }

    /** Fallback for apps without a recipe: the listing's screenshots, requested at up to 1920x1080. */
    private static Recipe playScreenshots(String pkg) {
        return new Recipe("https://play.google.com/store/apps/details?id=" + pkg + "&hl=en_US",
                "<img[^>]+src=\"(https://play-lh\\.googleusercontent\\.com/[^\"=]+)[^\"]*\"[^>]*alt=\"Screenshot image\"",
                "%s=w1920-h1080");
    }

    private static final Pattern MZ_SIZE = Pattern.compile("/(\\d{3,4})x(\\d{3,4})");
    private static final Pattern PAGE_IMAGE = Pattern.compile(
            "(?:src|content|data-src|srcset)=\"([^\"]+)\"|url\\(['\"]?([^)'\"]+)['\"]?\\)");

    private final File root;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> refreshing = new HashSet<>();
    private final Map<String, File> chosen = new HashMap<>();   // this visit's still per app
    private final Random random = new Random();
    private final int targetWidth;

    Backdrops(Context context) {
        root = new File(context.getCacheDir(), "backdrops");
        targetWidth = context.getResources().getDisplayMetrics().widthPixels;
    }

    /** Pick a fresh still per app next time each is shown (called when Home comes back). */
    synchronized void reshuffle() {
        chosen.clear();
    }

    /** Fetch in the background if this app has no images yet or they are a day old. */
    void prefetch(String pkg) {
        if (supports(pkg) && stale(pkg)) worker.execute(() -> refresh(pkg));
    }

    /** Delivers this visit's still for the app on the main thread, or null when there is none (yet). */
    void load(String pkg, Callback callback) {
        worker.execute(() -> {
            if (cached(pkg).isEmpty()) refresh(pkg);
            File file = pick(pkg);
            Bitmap bitmap = file != null ? decode(file, !RECIPES.containsKey(pkg)) : null;
            main.post(() -> callback.onBackdrop(pkg, bitmap));
            if (stale(pkg)) refresh(pkg);
        });
    }

    private synchronized File pick(String pkg) {
        File file = chosen.get(pkg);
        if (file != null && file.exists()) return file;
        List<File> files = cached(pkg);
        if (files.isEmpty()) return null;
        file = files.get(random.nextInt(files.size()));
        chosen.put(pkg, file);
        return file;
    }

    private List<File> cached(String pkg) {
        File[] files = new File(root, pkg).listFiles((dir, name) -> name.endsWith(".img"));
        return files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
    }

    private boolean stale(String pkg) {
        File stamp = new File(new File(root, pkg), "fetched");
        return !stamp.exists() || System.currentTimeMillis() - stamp.lastModified() > REFRESH_MS;
    }

    /** Runs on the worker thread. Keeps the old images if the refresh finds nothing. */
    private void refresh(String pkg) {
        synchronized (refreshing) {
            if (!refreshing.add(pkg)) return;
        }
        try {
            List<String> candidates = candidates(pkg);
            File dir = new File(root, pkg);
            File fresh = new File(root, pkg + ".new");
            deleteDir(fresh);
            fresh.mkdirs();
            int kept = 0;
            for (String url : candidates) {
                if (kept >= KEEP) break;
                byte[] data = download(url, MAX_IMAGE_BYTES);
                if (data == null || !suitable(data)) continue;
                try (FileOutputStream out = new FileOutputStream(new File(fresh, (kept++) + ".img"))) {
                    out.write(data);
                }
            }
            if (kept > 0) {
                deleteDir(dir);
                fresh.renameTo(dir);
            } else {
                deleteDir(fresh);
                dir.mkdirs();
            }
            // Stamp even when nothing qualified, so an app without artwork isn't re-scraped every visit.
            File stamp = new File(dir, "fetched");
            new FileOutputStream(stamp).close();
            stamp.setLastModified(System.currentTimeMillis());
            synchronized (this) {
                chosen.remove(pkg);
            }
        } catch (IOException ignored) {
        } finally {
            synchronized (refreshing) {
                refreshing.remove(pkg);
            }
        }
    }

    /** Image URLs worth downloading for this app, from its recipe's page or its Play Store listing. */
    private List<String> candidates(String pkg) {
        Set<String> urls = new LinkedHashSet<>();
        Recipe recipe = RECIPES.containsKey(pkg) ? RECIPES.get(pkg) : playScreenshots(pkg);
        String page = fetchText(recipe.page);
        if (page == null) return new ArrayList<>();
        if (recipe.image != null) {
            Matcher m = recipe.image.matcher(page);
            while (m.find() && urls.size() < MAX_CANDIDATES * 3) {
                String url = recipe.template != null ? String.format(recipe.template, m.group(1))
                        : Html.fromHtml(m.group()).toString();
                if (wideByName(url)) urls.add(url);
            }
        } else {
            Matcher m = PAGE_IMAGE.matcher(page.replace("\\/", "/"));
            while (m.find()) {
                String raw = m.group(1) != null ? m.group(1) : m.group(2);
                for (String part : Html.fromHtml(raw).toString().split(",")) {
                    String url = part.trim().split("\\s+")[0];
                    if (!url.matches("(?i).+\\.(jpe?g|webp)(\\?.*)?$") || NOT_ARTWORK.matcher(url).find()) continue;
                    try {
                        urls.add(new URL(new URL(recipe.page), url).toString());
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        List<String> list = new ArrayList<>(urls);
        java.util.Collections.shuffle(list, random);   // a different selection each day
        return list.subList(0, Math.min(MAX_CANDIDATES, list.size()));
    }

    /** Skips Apple's portrait and thumbnail sizes by the size in the URL, before downloading. */
    private static boolean wideByName(String url) {
        Matcher m = MZ_SIZE.matcher(url);
        if (!url.contains("mzstatic") || !m.find()) return true;
        int w = Integer.parseInt(m.group(1)), h = Integer.parseInt(m.group(2));
        return w >= 1100 && w >= h * MIN_ASPECT;
    }

    private static boolean suitable(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth < MIN_WIDTH || o.outHeight <= 0) return false;
        float aspect = o.outWidth / (float) o.outHeight;
        return aspect >= MIN_ASPECT && aspect <= MAX_ASPECT;
    }

    /**
     * Decoded no larger than the screen, in 16-bit colour to keep memory low on 2 GB TVs. Play Store
     * screenshots (soft) are app interfaces full of text, so they are decoded tiny and blurred: the
     * view stretches them back up into a soft wash of the app's colours.
     */
    private Bitmap decode(File file, boolean soft) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        int target = soft ? targetWidth / 8 : targetWidth;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= target) o.inSampleSize *= 2;
        o.inPreferredConfig = soft ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), o);
        if (!soft || bitmap == null) return bitmap;
        if (bitmap.getWidth() > target) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, target,
                    Math.max(1, bitmap.getHeight() * target / bitmap.getWidth()), true);
            bitmap.recycle();
            bitmap = scaled;
        }
        if (!bitmap.isMutable()) bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] pixels = new int[w * h], scratch = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            FrostView.boxBlur(pixels, scratch, w, h, 3, true);
            FrostView.boxBlur(scratch, pixels, w, h, 3, false);
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        return bitmap;
    }

    private static String fetchText(String url) {
        byte[] data = download(url, 8 * 1024 * 1024);
        return data == null ? null : new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] download(String url, int maxBytes) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) return null;
                }
                return out.toByteArray();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
