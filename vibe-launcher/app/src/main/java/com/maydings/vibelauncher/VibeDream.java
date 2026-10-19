package com.maydings.vibelauncher;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver: the backdrops Home already has in its pools, cross-fading with a slow zoom, under the
 * clock and the film's title. Reads only what is on the TV; no network. Chosen in the TV's screen saver
 * settings. Everything on screen moves a little with each picture, so nothing stays put for long.
 */
public class VibeDream extends DreamService {

    private static final long SHOW_MS = 20_000;
    private static final long FADE_MS = 1_600;
    private static final float ZOOM = 1.07f;   // how far each still drifts in while it is shown
    private static final float DRIFT_DP = 28;   // how far the clock and title wander between stills

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private ExecutorService loader;
    private ImageView stillA, stillB, logo;
    private TextView title;
    private View clockBox, titleBox;
    private boolean aOnTop;   // which layer holds the picture on screen
    private List<Backdrops.Entry> stills = Collections.emptyList();
    private int next;
    private ObjectAnimator zoom;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key ends it
        setFullscreen(true);
        setScreenBright(true);
        setContentView(R.layout.dream_vibe);
        stillA = findViewById(R.id.stillA);
        stillB = findViewById(R.id.stillB);
        logo = findViewById(R.id.logo);
        title = findViewById(R.id.title);
        clockBox = findViewById(R.id.clockBox);
        titleBox = findViewById(R.id.titleBox);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        loader = Executors.newSingleThreadExecutor();
        loader.execute(() -> {
            List<Backdrops.Entry> found = new ArrayList<>(Backdrops.screensaverStills(this));
            Collections.shuffle(found, random);
            main.post(() -> {
                stills = found;
                showNext();
            });
        });
    }

    @Override
    public void onDreamingStopped() {
        main.removeCallbacksAndMessages(null);
        if (zoom != null) zoom.cancel();
        if (loader != null) loader.shutdownNow();
        super.onDreamingStopped();
    }

    /** Loads the next still in the background, then fades it in; again every SHOW_MS. */
    private void showNext() {
        if (stills.isEmpty()) return;   // nothing cached yet: just the clock
        if (next >= stills.size()) {    // every one shown once: a new order, not starting on the last one
            Backdrops.Entry last = stills.get(stills.size() - 1);
            Collections.shuffle(stills, random);
            if (stills.size() > 1 && stills.get(0) == last) Collections.swap(stills, 0, 1);
            next = 0;
        }
        Backdrops.Entry e = stills.get(next++);
        loader.execute(() -> {
            Bitmap b = null, l = null;
            try {
                b = BitmapFactory.decodeFile(e.still.getPath());
                l = e.logo != null ? Backdrops.decodeLogo(e.logo) : null;
            } catch (RuntimeException | OutOfMemoryError ignored) {
                // Skip this one.
            }
            Bitmap still = b, logoBitmap = l;
            main.post(() -> {
                if (still != null) show(still, logoBitmap, e.title);
                main.postDelayed(this::showNext, still != null ? SHOW_MS : 0);
            });
        });
    }

    private void show(Bitmap still, Bitmap logoBitmap, String name) {
        // The new still goes on the layer that isn't showing; the other fades away once it's in.
        ImageView in = aOnTop ? stillB : stillA, out = aOnTop ? stillA : stillB;
        aOnTop = !aOnTop;
        in.bringToFront();
        clockBox.bringToFront();
        titleBox.bringToFront();
        in.animate().cancel();
        in.setImageBitmap(still);
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            out.setAlpha(0f);
            out.setImageDrawable(null);   // let the old picture go
        }).start();
        // A slow drift in for the whole time the still is up: its own animator, since the view's
        // animate() runs all properties for one duration (the fade's).
        if (zoom != null) zoom.cancel();
        zoom = ObjectAnimator.ofPropertyValuesHolder(in,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, ZOOM), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, ZOOM));
        zoom.setDuration(SHOW_MS + FADE_MS);
        zoom.setInterpolator(new LinearInterpolator());
        zoom.start();

        float drift = DRIFT_DP * getResources().getDisplayMetrics().density;
        float dx = (random.nextFloat() * 2 - 1) * drift, dy = -random.nextFloat() * drift;
        titleBox.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(() -> {
            logo.setImageBitmap(logoBitmap);
            logo.setVisibility(logoBitmap != null ? View.VISIBLE : View.GONE);
            title.setText(name);
            title.setVisibility(logoBitmap == null && name != null ? View.VISIBLE : View.GONE);
            titleBox.setTranslationX(-dx);
            titleBox.setTranslationY(dy);
            titleBox.animate().alpha(1f).setDuration(FADE_MS / 2).start();
        }).start();
        clockBox.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(() -> {
            clockBox.setTranslationX(dx);
            clockBox.setTranslationY(dy);
            clockBox.animate().alpha(1f).setDuration(FADE_MS / 2).start();
        }).start();
    }
}
