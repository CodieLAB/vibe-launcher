package com.maydings.vibelauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Vibe Launcher settings: the TMDB, YouTube and Plex keys, each with an (i) explaining it, tiles per
 * row, a QR code for pasting the keys from a phone (PhoneLink), and the app version.
 */
final class SettingsDialog {

    private SettingsDialog() {
    }

    static void show(Activity activity, ViewGroup root) {
        float density = activity.getResources().getDisplayMetrics().density;
        VibeSettings settings = new VibeSettings(activity);
        View panel = activity.getLayoutInflater().inflate(R.layout.dialog_settings, root, false);
        EditText tmdb = panel.findViewById(R.id.tmdbKey);
        EditText youtube = panel.findViewById(R.id.youtubeKey);
        EditText plex = panel.findViewById(R.id.plexToken);
        tmdb.setText(settings.tmdbKey());
        youtube.setText(settings.youtubeKey());
        plex.setText(settings.plexToken());
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Near the top, so the on-screen keyboard (bottom half on TVs) doesn't cover the fields,
            // and the keyboard only comes up once a field is picked.
            window.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.y = (int) (24 * density);
            window.setAttributes(lp);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            // The dialog theme caps the width; the fields and the QR code sit side by side.
            window.setLayout((int) (760 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        ((TextView) panel.findViewById(R.id.version)).setText(activity.getString(R.string.version, versionName(activity)));
        View cancel = panel.findViewById(R.id.cancel), save = panel.findViewById(R.id.save);
        View tmdbInfo = panel.findViewById(R.id.tmdbInfo), youtubeInfo = panel.findViewById(R.id.youtubeInfo),
                plexInfo = panel.findViewById(R.id.plexInfo);
        tmdbInfo.setOnClickListener(v -> showInfo(activity, v, R.string.tmdb_info));
        youtubeInfo.setOnClickListener(v -> showInfo(activity, v, R.string.youtube_info));
        plexInfo.setOnClickListener(v -> showInfo(activity, v, R.string.plex_info));
        // Tiles per row: one of three pills, the current choice marked; applied on Save.
        int current = MainActivity.COLUMNS;
        int[] chosen = {current};
        View[] columnPills = {panel.findViewById(R.id.columns4), panel.findViewById(R.id.columns5),
                panel.findViewById(R.id.columns6)};
        for (int i = 0; i < columnPills.length; i++) {
            int value = 4 + i;
            columnPills[i].setSelected(value == current);
            columnPills[i].setOnClickListener(v -> {
                chosen[0] = value;
                for (View p : columnPills) p.setSelected(p == v);
            });
        }
        View columnsEntry = columnPills[Math.max(0, Math.min(2, current - 4))];
        // Text fields keep Up/Down to themselves on this TV, so steer the remote by hand: the
        // tiles-per-row choice, then each key's (i) and its field. Right from any of them jumps to Save
        // (and Cancel under it), and Left comes back to where it left.
        View[] order = {tmdbInfo, tmdb, youtubeInfo, youtube, plexInfo, plex};
        View[] cameFrom = {columnsEntry};
        for (int i = 0; i < order.length; i++) {
            steer(order[i], i > 0 ? order[i - 1] : columnsEntry, i < order.length - 1 ? order[i + 1] : null, save, cameFrom);
        }
        for (View pill : columnPills) {
            pill.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;   // nothing above
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return tmdbInfo.requestFocus();
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v == columnPills[2]) {
                    cameFrom[0] = v;
                    return save.requestFocus();
                }
                return false;   // Left/Right move between the pills
            });
        }
        View.OnKeyListener back = (v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                && keyCode == KeyEvent.KEYCODE_DPAD_LEFT && cameFrom[0].requestFocus();
        save.setOnKeyListener(back);
        cancel.setOnKeyListener(back);
        tmdb.setOnEditorActionListener((v, actionId, event) -> youtube.requestFocus());
        youtube.setOnEditorActionListener((v, actionId, event) -> plex.requestFocus());
        plex.setOnEditorActionListener((v, actionId, event) -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(plex.getWindowToken(), 0);
            return save.requestFocus();
        });
        // Typing a 32-character key with a remote is painful: offer a page to paste it from a phone.
        PhoneLink link = new PhoneLink(fields -> {
            if (!dialog.isShowing()) return;
            if (fields.containsKey("tmdbKey")) tmdb.setText(fields.get("tmdbKey"));
            if (fields.containsKey("youtubeKey")) youtube.setText(fields.get("youtubeKey"));
            if (fields.containsKey("plexToken")) plex.setText(fields.get("plexToken"));
            settings.save(tmdb.getText().toString(), youtube.getText().toString(), plex.getText().toString());
            Toast.makeText(activity, R.string.saved_from_phone, Toast.LENGTH_SHORT).show();
        }, settings);
        String url = link.start();
        if (url != null) {
            ((ImageView) panel.findViewById(R.id.qr)).setImageBitmap(PhoneLink.qr(url, (int) (134 * density)));
            ((TextView) panel.findViewById(R.id.phoneUrl)).setText(url.replace("http://", ""));
            panel.findViewById(R.id.phone).setVisibility(View.VISIBLE);
        }
        dialog.setOnDismissListener(d -> link.stop());
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            settings.save(tmdb.getText().toString(), youtube.getText().toString(), plex.getText().toString());
            dialog.dismiss();
            Toast.makeText(activity, R.string.saved, Toast.LENGTH_SHORT).show();
            if (chosen[0] != current) {
                settings.setColumns(chosen[0]);
                activity.recreate();   // rebuild Home with the new grid
            }
        });
        dialog.show();
        columnsEntry.requestFocus();
    }

    /** Up and Down move to these views; Right goes to Save, remembering where it came from. */
    private static void steer(View view, View up, View down, View save, View[] cameFrom) {
        view.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                cameFrom[0] = v;
                return save.requestFocus();
            }
            View to = keyCode == KeyEvent.KEYCODE_DPAD_UP ? up : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? down : null;
            return to != null && to.requestFocus();
        });
    }

    /** A glass bubble under the (i) explaining a key; Back or OK closes it. */
    private static void showInfo(Activity activity, View anchor, int textRes) {
        float density = activity.getResources().getDisplayMetrics().density;
        TextView text = new TextView(activity);
        text.setText(textRes);
        text.setTextColor(0xE6FFFFFF);
        text.setTextSize(15);
        text.setLineSpacing(0, 1.15f);
        text.setTypeface(activity.getResources().getFont(R.font.outfit));
        int pad = (int) (18 * density);
        text.setPadding(pad, pad, pad, pad);
        text.setBackgroundResource(R.drawable.glass_panel);
        text.setMaxWidth((int) (440 * density));
        text.setFocusable(true);
        PopupWindow bubble = new PopupWindow(text, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        bubble.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        bubble.setOutsideTouchable(true);
        text.setOnClickListener(v -> bubble.dismiss());
        bubble.showAsDropDown(anchor, 0, (int) (6 * density));
        text.requestFocus();
    }

    private static String versionName(Activity activity) {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
