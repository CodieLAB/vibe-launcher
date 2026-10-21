package com.maydings.vibelauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
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
 * Vibe Launcher settings on two tabs. General: tiles per row and updates (Updater). API keys: the TMDB,
 * YouTube and Plex keys, each with an (i) explaining it, and a QR code for pasting them from a phone
 * (PhoneLink). Save and Cancel serve both tabs.
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
        // Saved keys show only their last four characters. Focusing a field selects it, so typing replaces
        // the key and clearing it removes it; left alone, the saved key stays.
        EditText[] fields = {tmdb, youtube, plex};
        String[] saved = {settings.tmdbKey(), settings.youtubeKey(), settings.plexToken()};
        for (int i = 0; i < fields.length; i++) {
            fields[i].setText(mask(saved[i]));
            fields[i].setSelectAllOnFocus(true);
        }
        Runnable saveKeys = () -> {
            for (int i = 0; i < fields.length; i++) saved[i] = value(fields[i], saved[i]);
            settings.save(saved[0], saved[1], saved[2]);
        };
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
            window.setLayout((int) (620 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
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
        TextView updateButton = panel.findViewById(R.id.updateButton);
        TextView updateStatus = panel.findViewById(R.id.updateStatus);
        View tabGeneral = panel.findViewById(R.id.tabGeneral), tabKeys = panel.findViewById(R.id.tabKeys);
        View pageGeneral = panel.findViewById(R.id.pageGeneral), pageKeys = panel.findViewById(R.id.pageKeys);
        View phone = panel.findViewById(R.id.phone);
        boolean[] phoneReady = {false};
        // A tab shows its page as soon as it has focus, like the tabs in the TV's own apps.
        Runnable showGeneral = () -> {
            // Each tab is as tall as it needs: the dialog is pinned to the top of the screen, so the title,
            // tabs, Save and Cancel stay put and only the bottom edge moves.
            pageGeneral.setVisibility(View.VISIBLE);
            pageKeys.setVisibility(View.GONE);
            phone.setVisibility(View.GONE);
            tabGeneral.setSelected(true);
            tabKeys.setSelected(false);
        };
        Runnable showKeys = () -> {
            pageGeneral.setVisibility(View.GONE);
            pageKeys.setVisibility(View.VISIBLE);
            phone.setVisibility(phoneReady[0] ? View.VISIBLE : View.GONE);   // the QR code is for the keys
            tabGeneral.setSelected(false);
            tabKeys.setSelected(true);
        };
        tabGeneral.setOnFocusChangeListener((v, focused) -> { if (focused) showGeneral.run(); });
        tabKeys.setOnFocusChangeListener((v, focused) -> { if (focused) showKeys.run(); });
        tabGeneral.setOnClickListener(v -> columnsEntry.requestFocus());
        tabKeys.setOnClickListener(v -> tmdbInfo.requestFocus());

        // Text fields keep Up/Down to themselves on this TV, so steer the remote by hand. Up from the top
        // of a page reaches its tab; Right from anything jumps to Save (and Cancel under it), and Left
        // comes back to where it left.
        View[] cameFrom = {columnsEntry};
        View[] order = {tmdbInfo, tmdb, youtubeInfo, youtube, plexInfo, plex};
        for (int i = 0; i < order.length; i++) {
            steer(order[i], i > 0 ? order[i - 1] : tabKeys, i < order.length - 1 ? order[i + 1] : null, save, cameFrom);
        }
        View.OnKeyListener tabKeysNav = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;   // nothing above
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return v == tabGeneral ? columnsEntry.requestFocus() : tmdbInfo.requestFocus();
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v == tabGeneral || tabGeneral.requestFocus();
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (v == tabGeneral) return tabKeys.requestFocus();
                cameFrom[0] = v;
                return save.requestFocus();
            }
            return false;
        };
        tabGeneral.setOnKeyListener(tabKeysNav);
        tabKeys.setOnKeyListener(tabKeysNav);
        for (View pill : columnPills) {
            pill.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return tabGeneral.requestFocus();
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (updateButton.getVisibility() == View.VISIBLE) updateButton.requestFocus();
                    return true;   // nothing else below
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v == columnPills[2]) {
                    cameFrom[0] = v;
                    return save.requestFocus();
                }
                return false;   // Left/Right move between the pills
            });
        }
        steer(updateButton, columnsEntry, null, save, cameFrom);
        showUpdates(activity, updateStatus, updateButton);
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
        PhoneLink link = new PhoneLink(received -> {
            if (!dialog.isShowing()) return;
            String[] names = {"tmdbKey", "youtubeKey", "plexToken"};
            for (int i = 0; i < names.length; i++) {
                if (received.containsKey(names[i])) fields[i].setText(received.get(names[i]));
            }
            saveKeys.run();
            for (int i = 0; i < fields.length; i++) fields[i].setText(mask(saved[i]));
            Toast.makeText(activity, R.string.saved_from_phone, Toast.LENGTH_SHORT).show();
        }, settings);
        String url = link.start();
        if (url != null) {
            ((ImageView) panel.findViewById(R.id.qr)).setImageBitmap(PhoneLink.qr(url, (int) (114 * density)));
            ((TextView) panel.findViewById(R.id.phoneUrl)).setText(url.replace("http://", ""));
            phoneReady[0] = true;
        }
        showGeneral.run();
        dialog.setOnDismissListener(d -> {
            link.stop();
            // An update found here shows on the Settings pill straight away, not at the next return to Home.
            if (activity instanceof MainActivity) ((MainActivity) activity).updateDot();
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            saveKeys.run();
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

    /** A saved key as shown in its field: bullets and the last four characters; "" when not set. */
    private static String mask(String key) {
        return key.isEmpty() ? "" : "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" + key.substring(Math.max(0, key.length() - 4));
    }

    /** What a field holds: the saved key while it still shows the mask, else what was typed. */
    private static String value(EditText field, String saved) {
        String text = field.getText().toString().trim();
        return !saved.isEmpty() && text.equals(mask(saved)) ? saved : text;
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
        // Below the (i), or above it when there isn't room: the Plex one sits low enough on screen that a
        // bubble below ran off the bottom.
        int gap = (int) (6 * density);
        text.measure(View.MeasureSpec.makeMeasureSpec((int) (440 * density), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int below = activity.getResources().getDisplayMetrics().heightPixels - at[1] - anchor.getHeight();
        boolean above = text.getMeasuredHeight() + gap > below;
        bubble.showAsDropDown(anchor, 0, above ? -(anchor.getHeight() + text.getMeasuredHeight() + gap) : gap);
        text.requestFocus();
    }

    /**
     * The Updates part of General: the installed version and a button that checks for a newer one, then
     * offers to install it. A development build only says how it updates.
     */
    private static void showUpdates(Activity activity, TextView status, TextView button) {
        String version = Updater.installedVersion(activity);
        if (Updater.isDevelopmentBuild(activity)) {
            status.setText(activity.getString(R.string.update_development_status, version));
            button.setVisibility(View.GONE);
            return;
        }
        Updater.Release found = Updater.available(activity);
        if (found != null) {
            offerUpdate(activity, status, button, found);
            return;
        }
        status.setText(activity.getString(R.string.version_current, version));
        button.setText(R.string.check_updates);
        button.setOnClickListener(v -> {
            button.setText(R.string.checking_updates);
            button.setEnabled(false);
            Updater.check(activity, (newer, failed) -> {
                button.setEnabled(true);
                if (newer != null) {
                    offerUpdate(activity, status, button, newer);
                } else {
                    status.setText(activity.getString(failed ? R.string.update_check_failed : R.string.version_up_to_date, version));
                    button.setText(R.string.check_updates);
                }
                button.requestFocus();
            });
        });
    }

    private static void offerUpdate(Activity activity, TextView status, TextView button, Updater.Release release) {
        status.setText(activity.getString(R.string.update_available, release.version));
        button.setText(activity.getString(R.string.update_to, release.version));
        button.setOnClickListener(v -> startUpdate(activity, release));
    }

    /** Downloads and installs; Android's own screen asks to confirm. Shared with the gear menu. */
    static void startUpdate(Activity activity, Updater.Release release) {
        Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show();
        Updater.install(activity, release, problem -> Toast.makeText(activity, problem, Toast.LENGTH_LONG).show());
    }
}
