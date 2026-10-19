package com.maydings.vibelauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backdrop stills from the cards apps publish for the TV home screen (their channels and Watch Next
 * rows in the system TV provider), read with READ_TV_LISTINGS. Only wide, big artwork is used; apps
 * that publish none keep the colour glow. Downloaded images are cached on disk for a week.
 */
final class Backdrops {

    interface Callback {
        void onBackdrop(String pkg, Bitmap bitmap);
    }

    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";

    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final String[] COLUMNS = {"package_name", "intent_uri", "poster_art_uri", "thumbnail_uri"};
    private static final int MIN_WIDTH = 800;   // Netflix publishes 853x480
    private static final float MIN_ASPECT = 1.45f, MAX_ASPECT = 2.4f;
    private static final int MAX_TRIES = 6;       // candidates tried per visit before giving up
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;
    private static final long CACHE_MS = 7L * 24 * 60 * 60 * 1000;

    private final ContentResolver resolver;
    private final File root;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, File> chosen = new HashMap<>();   // this visit's still per app
    private final Set<String> unsuitable = Collections.synchronizedSet(new HashSet<>());
    private final Random random = new Random();
    private final int targetWidth;

    Backdrops(Context context) {
        resolver = context.getContentResolver();
        root = new File(context.getCacheDir(), "cards");
        targetWidth = context.getResources().getDisplayMetrics().widthPixels;
        worker.execute(() -> {
            deleteTree(new File(context.getCacheDir(), "backdrops"));   // the old scraped stills
            prune();
        });
    }

    /** Pick a fresh still per app next time each is shown (called when Home comes back). */
    synchronized void reshuffle() {
        chosen.clear();
    }

    /** Choose and download this visit's still in the background so it is ready on focus. */
    void prefetch(String pkg) {
        worker.execute(() -> pick(pkg));
    }

    /** Delivers this visit's still for the app on the main thread, or null when it has none. */
    void load(String pkg, Callback callback) {
        worker.execute(() -> {
            File file = pick(pkg);
            Bitmap bitmap = file != null ? decode(file) : null;
            main.post(() -> callback.onBackdrop(pkg, bitmap));
        });
    }

    /** Runs on the worker thread. */
    private File pick(String pkg) {
        synchronized (this) {
            File file = chosen.get(pkg);
            if (file != null && file.exists()) return file;
        }
        List<String> urls = artwork(pkg);
        Collections.shuffle(urls, random);
        int tries = 0;
        for (String url : urls) {
            if (unsuitable.contains(url)) continue;
            if (tries++ >= MAX_TRIES) break;
            File file = fetch(url);
            if (file == null) continue;
            synchronized (this) {
                chosen.put(pkg, file);
            }
            return file;
        }
        return null;
    }

    /** Image URIs from the app's home screen cards. Empty without the permission or any cards. */
    private List<String> artwork(String pkg) {
        Set<String> urls = new LinkedHashSet<>();
        for (Uri table : new Uri[]{WATCH_NEXT, PREVIEW}) {
            // Filtering by package in the query isn't allowed without system permissions; read all.
            try (Cursor c = resolver.query(table, COLUMNS, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (!pkg.equals(c.getString(0)) || badged(c.getString(1))) continue;
                    for (int i = 2; i < COLUMNS.length; i++) {
                        String url = c.getString(i);
                        if (url != null && !url.isEmpty()) urls.add(url);
                    }
                }
            } catch (RuntimeException e) {
                // No permission, or the provider is missing (not a TV): no stills.
            }
        }
        return new ArrayList<>(urls);
    }

    /**
     * Netflix bakes stickers such as "Recently Added" and "Top 10" into the artwork of some cards, and
     * names them in the card's link (badges=B:recently.added). Those don't work as a backdrop.
     */
    private static boolean badged(String intentUri) {
        return intentUri != null && (intentUri.contains("badges%3D") || intentUri.contains("badges="));
    }

    /** The cached file for this image, downloading it first; null if it isn't wide and big. */
    private File fetch(String url) {
        File file = new File(root, Integer.toHexString(url.hashCode()) + ".img");
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis());
            return file;
        }
        byte[] data = read(url);
        if (data == null || !suitable(data)) {
            unsuitable.add(url);
            return null;
        }
        root.mkdirs();
        File tmp = new File(root, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        } catch (IOException e) {
            tmp.delete();
            return null;
        }
        return tmp.renameTo(file) ? file : null;
    }

    private byte[] read(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) return download(url);
        // content:// and android.resource:// art served by the app itself.
        try (InputStream in = resolver.openInputStream(Uri.parse(url))) {
            return in == null ? null : readAll(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean suitable(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth < MIN_WIDTH || o.outHeight <= 0) return false;
        float aspect = o.outWidth / (float) o.outHeight;
        return aspect >= MIN_ASPECT && aspect <= MAX_ASPECT;
    }

    /** Decoded no larger than the screen, in 16-bit colour to keep memory low on 2 GB TVs. */
    private Bitmap decode(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= targetWidth) o.inSampleSize *= 2;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    private static byte[] download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_IMAGE_BYTES) return null;
        }
        return out.toByteArray();
    }

    /** Drops stills not shown for a week; apps rotate their cards, so old art piles up otherwise. */
    private void prune() {
        File[] files = root.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) if (now - f.lastModified() > CACHE_MS) f.delete();
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }
}
