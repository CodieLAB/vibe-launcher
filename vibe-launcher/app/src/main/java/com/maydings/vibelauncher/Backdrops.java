package com.maydings.vibelauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backdrop stills from the cards apps publish for the TV home screen (their channels and Watch Next
 * rows in the system TV provider), read with READ_TV_LISTINGS. A card's film or show is shown with a
 * clean full-size backdrop and the title's logo instead of the app's own art: from TMDB with a key,
 * from Stremio's free metadata without one. Otherwise only wide artwork is used, and low-res art (Netflix's 853x480) becomes a 2x2 wall of four cards. YouTube publishes no
 * cards, so its tile borrows SmartTube's (the user's own feed), or without SmartTube shows popular
 * videos via the YouTube Data API key. Plex, with a Plex token, shows art from the user's own server.
 * Apps with none keep the colour glow.
 * Downloaded images are cached on disk for a week.
 */
final class Backdrops {

    interface Callback {
        /**
         * The still for the app (null for none). For a film or show found by its title, also that title
         * and its logo (null when it has none, and then the title is shown as text).
         */
        void onBackdrop(String pkg, Bitmap bitmap, Bitmap logo, String title);
    }

    /** This visit's pick for an app: one still or a wall of four, and for a known title its logo. */
    private static final class Choice {
        final List<File> files;
        final File logo;
        final String title;

        Choice(List<File> files, File logo, String title) {
            this.files = files;
            this.logo = logo;
            this.title = title;
        }

        boolean exists() {
            for (File f : files) if (!f.exists()) return false;
            return logo == null || logo.exists();
        }
    }

    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";

    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final String[] COLUMNS = {"package_name", "intent_uri", "title", "poster_art_uri", "thumbnail_uri"};
    private static final int FIRST_IMAGE = 3;   // COLUMNS from here on are image URIs
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String PLEX = "com.plexapp.android";
    /** SmartTube builds (stable, beta, the old names): they publish the signed-in user's YouTube feed. */
    private static final String[] SMARTTUBE = {"org.smarttube.stable", "org.smarttube.beta",
            "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta", "com.teamsmart.videomanager.tv"};
    private static final int MIN_WIDTH = 800;   // Netflix publishes 853x480
    private static final float MIN_ASPECT = 1.45f, MAX_ASPECT = 2.4f;
    private static final int SHARP_WIDTH = 1280;  // narrower art goes into a 2x2 wall
    private static final int WALL = 4;
    private static final int WALL_GAP = 6;        // px between the wall's cards
    private static final int MAX_TRIES = 12;      // lookups and downloads per visit before giving up
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;
    private static final long CACHE_MS = 7L * 24 * 60 * 60 * 1000;

    private final ContentResolver resolver;
    private final Tmdb tmdb;
    private final Stremio stremio;
    private final YouTubePopular youtubePopular;
    private final Plex plex;
    private final File root;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Choice> chosen = new HashMap<>();   // this visit's pick per app
    private final Set<String> unsuitable = Collections.synchronizedSet(new HashSet<>());
    private final Random random = new Random();
    private final int targetWidth, targetHeight;

    Backdrops(Context context) {
        resolver = context.getContentResolver();
        tmdb = new Tmdb(context);
        stremio = new Stremio(context);
        youtubePopular = new YouTubePopular(context);
        root = new File(context.getCacheDir(), "cards");
        targetWidth = context.getResources().getDisplayMetrics().widthPixels;
        targetHeight = context.getResources().getDisplayMetrics().heightPixels;
        plex = new Plex(context, targetWidth, targetHeight);
        worker.execute(this::prune);
    }

    /** Pick a fresh still per app next time each is shown (called when Home comes back). */
    synchronized void reshuffle() {
        chosen.clear();
    }

    /** Choose and download this visit's still in the background so it is ready on focus. */
    void prefetch(String pkg) {
        worker.execute(() -> {
            try {
                pick(pkg);
            } catch (RuntimeException | OutOfMemoryError e) {
                // Nothing ready; load() tries again on focus.
            }
        });
    }

    /** Delivers this visit's still for the app on the main thread, or null when it has none. */
    void load(String pkg, Callback callback) {
        worker.execute(() -> {
            Bitmap bitmap = null, logo = null;
            String title = null;
            try {
                Choice c = pick(pkg);
                bitmap = c == null ? null : c.files.size() == 1 ? decode(c.files.get(0)) : wall(c.files);
                if (bitmap != null) darken(bitmap);
                logo = c != null && c.logo != null ? decodeLogo(c.logo) : null;
                title = c != null ? c.title : null;
            } catch (RuntimeException | OutOfMemoryError e) {
                // A bad image or cache entry, or no memory left on a small TV: the colour glow instead
                // of taking the launcher down (an error here would crash the whole app).
                bitmap = null;
                logo = null;
                title = null;
            }
            Bitmap b = bitmap, l = logo;
            String t = title;
            main.post(() -> callback.onBackdrop(pkg, b, l, t));
        });
    }

    /** One card from the provider: its title, whether its art carries a sticker, and its images. */
    private static final class Card {
        final String title;
        final boolean badged;
        final List<String> images = new ArrayList<>();

        Card(String title, boolean badged) {
            this.title = title;
            this.badged = badged;
        }
    }

    /**
     * One sharp still (a clean backdrop for the card's title, or the card's own unbadged art), or else four
     * low-res cards for a wall (the wall may use badged ones: the sticker doesn't change the card's
     * size). Runs on the worker thread.
     */
    private Choice pick(String pkg) {
        synchronized (this) {
            Choice c = chosen.get(pkg);
            if (c != null && c.exists()) return c;
        }
        List<Card> cards = cards(pkg);
        // Films and shows by title: TMDB with a key, Stremio without. Not for video sites' video names.
        TitleArt titleArt = isVideoSite(pkg) ? null : tmdb.enabled() ? tmdb : stremio;
        List<File> wall = new ArrayList<>();
        File plain = null;   // first unbadged low-res card, the fallback when there aren't enough for a wall
        int tries = 0;
        for (Card card : cards) {
            if (tries >= MAX_TRIES || (wall.size() == WALL && titleArt == null)) break;
            if (titleArt != null && card.title != null) {
                tries++;
                TitleArt.Art art = titleArt.lookup(card.title);
                File file = art != null && !unsuitable.contains(art.backdrop) ? fetch(art.backdrop, true) : null;
                if (file != null) {
                    File logo = art.logo != null && !unsuitable.contains(art.logo) ? fetch(art.logo, false) : null;
                    return remember(pkg, new Choice(Collections.singletonList(file), logo, card.title));
                }
            }
            for (String url : card.images) {
                if (unsuitable.contains(url)) continue;
                if (tries++ >= MAX_TRIES) break;
                File file = fetch(url, true);
                if (file == null) continue;
                if (width(file) >= SHARP_WIDTH) {
                    if (!card.badged) return remember(pkg, new Choice(Collections.singletonList(file), null, null));
                } else {
                    if (plain == null && !card.badged) plain = file;
                    if (wall.size() < WALL) wall.add(file);
                }
                break;   // one image per card
            }
        }
        if (wall.size() == WALL) return remember(pkg, new Choice(wall, null, null));
        return plain != null ? remember(pkg, new Choice(Collections.singletonList(plain), null, null)) : null;
    }

    private synchronized Choice remember(String pkg, Choice c) {
        chosen.put(pkg, c);
        return c;
    }

    /**
     * The app's home screen cards, unbadged ones first, each group shuffled. Empty without the
     * permission or any cards.
     */
    private List<Card> cards(String pkg) {
        if (pkg.equals(PLEX)) {
            // The server's own art; Plex's old cards point at a provider the new app no longer has.
            List<Card> cards = new ArrayList<>();
            for (String url : plex.backdrops()) {
                Card card = new Card(null, false);
                card.images.add(url);
                cards.add(card);
            }
            if (!cards.isEmpty()) {
                Collections.shuffle(cards, random);
                return cards;
            }
        }
        Set<String> sources = new HashSet<>(Collections.singletonList(pkg));
        if (pkg.equals(YOUTUBE)) Collections.addAll(sources, SMARTTUBE);
        List<Card> clean = new ArrayList<>(), badged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Uri table : new Uri[]{WATCH_NEXT, PREVIEW}) {
            // Filtering by package in the query isn't allowed without system permissions; read all.
            try (Cursor c = resolver.query(table, COLUMNS, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (!sources.contains(c.getString(0))) continue;
                    Card card = new Card(c.getString(2), badged(c.getString(1)));
                    for (int i = FIRST_IMAGE; i < COLUMNS.length; i++) {
                        String url = c.getString(i);
                        if (url == null || url.isEmpty() || !seen.add(url)) continue;
                        String sharper = youtubeMaxRes(url);
                        if (sharper != null && seen.add(sharper)) card.images.add(sharper);
                        card.images.add(url);
                    }
                    if (!card.images.isEmpty()) (card.badged ? badged : clean).add(card);
                }
            } catch (RuntimeException e) {
                // No permission, or the provider is missing (not a TV): no stills.
            }
        }
        if (pkg.equals(YOUTUBE) && clean.isEmpty()) {
            // No SmartTube: popular videos instead (needs the YouTube key; empty without it).
            for (String id : youtubePopular.videoIds()) {
                Card card = new Card(null, false);
                card.images.add("https://i.ytimg.com/vi/" + id + "/maxresdefault.jpg");
                card.images.add("https://i.ytimg.com/vi/" + id + "/hq720.jpg");
                clean.add(card);
            }
        }
        Collections.shuffle(clean, random);
        Collections.shuffle(badged, random);
        clean.addAll(badged);
        return clean;
    }

    /** Video sites: their card titles are video names, not films or shows to look up on TMDB. */
    private static boolean isVideoSite(String pkg) {
        if (pkg.startsWith(YOUTUBE)) return true;   // also YouTube Kids (com.google.android.youtube.tvkids)
        for (String s : SMARTTUBE) if (s.equals(pkg)) return true;
        return false;
    }

    /**
     * YouTube thumbnails come in several sizes under one video ID; the largest, maxresdefault, is
     * 1280x720 when the uploader provided one (a 404 otherwise, and the card's own size is used).
     */
    private static String youtubeMaxRes(String url) {
        if (!url.startsWith("https://i.ytimg.com/vi/")) return null;
        int end = url.indexOf('/', "https://i.ytimg.com/vi/".length());
        return end < 0 ? null : url.substring(0, end) + "/maxresdefault.jpg";
    }

    /**
     * Netflix bakes stickers such as "Recently Added" and "Top 10" into the artwork of some cards, and
     * names them in the card's link (badges=B:recently.added). Those don't work as a full-screen still.
     */
    private static boolean badged(String intentUri) {
        return intentUri != null && (intentUri.contains("badges%3D") || intentUri.contains("badges="));
    }

    /**
     * The cached file for this image, downloading it first. Null if it can't be had, or for a backdrop
     * if it isn't wide and big enough.
     */
    private File fetch(String url, boolean backdrop) {
        File file = new File(root, sha1(url) + ".img");
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis());
            return file;
        }
        byte[] data = read(url);
        if (data == null || !(backdrop ? suitable(data) : decodable(data))) {
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

    /** A file name for a URL: a 32-bit hash could give two images the same name and show the wrong one. */
    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** The image's bytes, or null when it can't be had (offline, gone, too big). */
    private byte[] read(String url) {
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) return Http.get(url, MAX_IMAGE_BYTES);
            // content:// and android.resource:// art served by the app itself.
            try (InputStream in = resolver.openInputStream(Uri.parse(url))) {
                return in == null ? null : Http.readAll(in, MAX_IMAGE_BYTES);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean decodable(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        return o.outWidth > 0 && o.outHeight > 0;
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
     * Decoded no larger than the screen, in full colour: 16-bit colour (half the memory) turned dark,
     * smooth areas into visible bands.
     */
    private Bitmap decode(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= targetWidth) o.inSampleSize *= 2;
        o.inMutable = true;   // darkened in place
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    /**
     * Darkens a still behind the clock and the dock for legibility, with a gradient that leaves the
     * middle of the picture nearly untouched. Baked into the image once, here on the worker thread,
     * instead of a full-screen layer the GPU would paint on every frame.
     */
    private static void darken(Bitmap bitmap) {
        Canvas canvas = new Canvas(bitmap);
        Paint scrim = new Paint();
        scrim.setShader(new LinearGradient(0, 0, 0, bitmap.getHeight(),
                new int[]{0xB3000000, 0x40000000, 0xF2000000}, new float[]{0f, 0.38f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, bitmap.getWidth(), bitmap.getHeight(), scrim);
    }

    /** A title logo, kept transparent and no wider than 800 px (it shows at about a third of the screen). */
    private static Bitmap decodeLogo(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= 800) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    private static int width(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        return o.outWidth;
    }

    /** Four cards in a 2x2 grid filling the screen, each centre-cropped into its cell. */
    private Bitmap wall(List<File> files) {
        Bitmap out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        int cellW = (targetWidth - WALL_GAP) / 2, cellH = (targetHeight - WALL_GAP) / 2;
        for (int i = 0; i < files.size(); i++) {
            Bitmap card = BitmapFactory.decodeFile(files.get(i).getPath());
            if (card == null) continue;
            int left = (i % 2) * (cellW + WALL_GAP), top = (i / 2) * (cellH + WALL_GAP);
            canvas.drawBitmap(card, centreCrop(card.getWidth(), card.getHeight(), cellW, cellH),
                    new Rect(left, top, left + cellW, top + cellH), paint);
            card.recycle();
        }
        return out;
    }

    /** The part of a w x h image with the cell's aspect ratio, centred. */
    private static Rect centreCrop(int w, int h, int cellW, int cellH) {
        if (w * cellH > h * cellW) {
            int cw = h * cellW / cellH;
            return new Rect((w - cw) / 2, 0, (w + cw) / 2, h);
        }
        int ch = w * cellH / cellW;
        return new Rect(0, (h - ch) / 2, w, (h + ch) / 2);
    }

    /** Drops stills not shown for a week; apps rotate their cards, so old art piles up otherwise. */
    private void prune() {
        File[] files = root.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) if (now - f.lastModified() > CACHE_MS) f.delete();
    }
}
