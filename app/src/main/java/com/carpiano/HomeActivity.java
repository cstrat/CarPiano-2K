package com.carpiano;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * English home screen: Sounds, Live mic and Setup.
 */
public class HomeActivity extends Activity {

    private static final String[] PAGES = {"Library", "Widget", "Setup"};
    private static final int P_SOUNDS = 0, P_WIDGET = 1, P_SETUP = 2;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int REQ_MIC = 77;
    private static final int REQ_FILES = 78;
    private static final long LATCH_LIMIT_MS = 30000L;

    /** Preset groups the user can file imported sounds under. They can add their own too. */
    private static final String[] USER_GROUPS = Library.PRESETS;

    private static final class Sound {
        final String label;
        final String category;
        final String asset;
        final File file;

        Sound(String label, String category, String asset, File file) {
            this.label = label;
            this.category = category;
            this.asset = asset;
            this.file = file;
        }
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<TextView> navButtons = new ArrayList<>();

    private AudioOut audio;
    private SharedPreferences sp;
    private FrameLayout host;
    private TextView title;
    private TextView status;
    private TextView updateBanner;
    private TextView btnCabin;
    private TextView btnOutside;

    private int page;
    private String category = "All";
    private int playSeq;

    private int latestBuild = -1;
    private String latestUrl;
    private String updateMsg = "Not checked yet.";
    private TextView updateText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashSaver();
        try {
            audio = new AudioOut(this);
            sp = getSharedPreferences("piano", MODE_PRIVATE);
            Prefs.repair(this);
            Player.setGainPercent(sp.getInt("vol_pct", 60));
            setContentView(buildRoot());
            showPage(P_SOUNDS);
            checkUpdate(false);
            if (sp.getBoolean("float_on", false) && !FloatService.running
                    && android.provider.Settings.canDrawOverlays(this)) {
                startFloat();
            }
        } catch (Throwable t) {
            setContentView(errorView(t));
        }
    }

    // ------------------------------------------------------------------ frame

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout nav = Ui.col(this);
        nav.setBackgroundColor(Ui.SURF);
        int np = Ui.dp(this, 10);
        nav.setPadding(np, np, np, np);
        for (int i = 0; i < PAGES.length; i++) {
            final int idx = i;
            TextView b = navButton(PAGES[i], () -> showPage(idx));
            navButtons.add(b);
            nav.addView(b, Ui.lp(this, MATCH, Ui.dp(this, 68), 0, 4));
        }
        root.addView(nav, Ui.lp(Ui.dp(this, 132), MATCH, 0));

        LinearLayout right = Ui.col(this);
        right.addView(buildTopBar(), Ui.lp(MATCH, Ui.dp(this, 76), 0));
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        right.addView(line, Ui.lp(MATCH, Math.max(1, Ui.dp(this, 1)), 0));
        host = new FrameLayout(this);
        right.addView(host, Ui.lp(MATCH, 0, 1f));
        status = Ui.text(this, "Ready.", 14f, Ui.DIM, false);
        status.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 10));
        status.setSingleLine(true);
        right.addView(status, Ui.lp(MATCH, WRAP, 0));
        root.addView(right, Ui.lp(0, MATCH, 1f));
        return root;
    }

    private TextView navButton(String label, Runnable onClick) {
        TextView b = Ui.text(this, label, 16f, Ui.DIM, true);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private View buildTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 20), 0);

        title = Ui.text(this, "", 26f, Ui.TXT, true);
        bar.addView(title, Ui.lp(0, WRAP, 1f));

        updateBanner = Ui.button(this, "Update available", Ui.OK, Ui.BG, () -> showPage(P_SETUP));
        updateBanner.setVisibility(View.GONE);
        bar.addView(updateBanner, Ui.lp(this, WRAP, WRAP, 0, 6));

        final TextView volText = Ui.text(this, "Volume " + Player.gainPercent() + "%", 14f, Ui.DIM, true);
        bar.addView(volText, Ui.lp(this, WRAP, WRAP, 0, 6));
        SeekBar vol = new SeekBar(this);
        vol.setMax(100);
        vol.setProgress(Player.gainPercent());
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                Player.setGainPercent(v);
                volText.setText("Volume " + v + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                sp.edit().putInt("vol_pct", s.getProgress()).apply();
            }
        });
        bar.addView(vol, Ui.lp(Ui.dp(this, 200), WRAP, 0));

        LinearLayout seg = Ui.row(this);
        seg.setBackground(Ui.box(this, Ui.RAISE, 0, 16));
        int pad = Ui.dp(this, 4);
        seg.setPadding(pad, pad, pad, pad);
        btnCabin = Ui.button(this, "Cabin", Ui.RAISE, Ui.DIM, () -> setOutside(false));
        btnOutside = Ui.button(this, "Outside", Ui.RAISE, Ui.DIM, () -> setOutside(true));
        seg.addView(btnCabin, Ui.lp(WRAP, WRAP, 0));
        seg.addView(btnOutside, Ui.lp(WRAP, WRAP, 0));
        LinearLayout.LayoutParams sl = Ui.lp(WRAP, WRAP, 0);
        sl.leftMargin = Ui.dp(this, 16);
        bar.addView(seg, sl);
        refreshSpeakerButtons();
        return bar;
    }

    private void setOutside(boolean outside) {
        Prefs.setChan(this, outside ? 3 : 0);
        refreshSpeakerButtons();
        setStatus(outside ? "Sounds now play through the outside speaker."
                : "Sounds now play in the cabin.");
    }

    private void refreshSpeakerButtons() {
        boolean outside = Prefs.outer(this);
        btnCabin.setBackground(Ui.box(this, outside ? Ui.RAISE : Ui.CAB, 0, 12));
        btnCabin.setTextColor(outside ? Ui.DIM : Ui.BG);
        btnOutside.setBackground(Ui.box(this, outside ? Ui.OUT : Ui.RAISE, 0, 12));
        btnOutside.setTextColor(outside ? Ui.BG : Ui.DIM);
    }

    private void showPage(int idx) {
        page = idx;
        closeBrowser();
        for (int i = 0; i < navButtons.size(); i++) {
            boolean on = i == idx;
            navButtons.get(i).setBackground(on ? Ui.box(this, Ui.RAISE, 0, 16) : null);
            navButtons.get(i).setTextColor(on ? Ui.TXT : Ui.DIM);
        }
        title.setText(PAGES[idx]);
        host.removeAllViews();
        View v;
        if (idx == P_SOUNDS) {
            v = buildSounds();
        } else if (idx == P_WIDGET) {
            v = buildWidget();
        } else {
            v = buildSetup();
        }
        host.addView(v, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private void setStatus(String s) {
        if (status != null) {
            status.setText(s);
        }
    }

    // ----------------------------------------------------------------- sounds

    private List<Sound> allSounds() {
        List<Sound> out = new ArrayList<>();
        try {
            SharedPreferences groups = getSharedPreferences("groups", MODE_PRIVATE);
            for (File f : Library.userSounds(this)) {
                out.add(new Sound(Library.label(f), groups.getString(f.getName(), "My sounds"), null, f));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private View buildSounds() {
        LinearLayout page = Ui.col(this);
        int p = Ui.dp(this, 20);
        page.setPadding(p, Ui.dp(this, 14), p, 0);

        LinearLayout top = Ui.row(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(this);
        List<String> cats = new ArrayList<>();
        cats.add("All");
        List<Sound> everything = allSounds();
        for (String g : userGroups()) {
            boolean used = "My sounds".equals(g);
            for (Sound s : everything) {
                if (s.file != null && s.category.equals(g)) {
                    used = true;
                    break;
                }
            }
            if (used) {
                cats.add(g);
            }
        }
        for (final String c : cats) {
            boolean on = c.equals(category);
            TextView chip = Ui.button(this, c, on ? Ui.TXT : Ui.SURF, on ? Ui.BG : Ui.DIM, () -> {
                category = c;
                showPage(P_SOUNDS);
            });
            chips.addView(chip, Ui.lp(this, WRAP, WRAP, 0, 4));
        }
        hs.addView(chips);
        top.addView(hs, Ui.lp(0, WRAP, 1f));
        top.addView(Ui.button(this, "Browse web", Ui.RAISE, Ui.TXT, this::showBrowser),
                Ui.lp(this, WRAP, WRAP, 0, 4));
        top.addView(Ui.button(this, "Add sounds", Ui.RAISE, Ui.TXT, this::showImport),
                Ui.lp(this, WRAP, WRAP, 0, 4));
        top.addView(Ui.button(this, "Stop", Ui.TXT, Ui.BG, () -> {
            playSeq++;
            Player.stop();
            setStatus("Stopped.");
        }), Ui.lp(this, Ui.dp(this, 140), WRAP, 0, 4));
        page.addView(top, Ui.lp(MATCH, WRAP, 0));

        if (!"All".equals(category)) {
            final String g = category;
            LinearLayout tools = Ui.row(this);
            tools.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 2));
            tools.addView(Ui.text(this, "Colour", 15f, Ui.DIM, true), Ui.lp(this, WRAP, WRAP, 0, 6));
            int current = Library.colorOf(this, g);
            for (final int c : Library.palette()) {
                View sw = new View(this);
                android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
                dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dot.setColor(c);
                if (c == current) {
                    dot.setStroke(Ui.dp(this, 3), Ui.TXT);
                }
                sw.setBackground(dot);
                sw.setClickable(true);
                sw.setOnClickListener(v -> {
                    Library.setColor(this, g, c);
                    restartWidgetQuietly();
                    showPage(P_SOUNDS);
                });
                tools.addView(sw, Ui.lp(this, Ui.dp(this, 40), Ui.dp(this, 40), 0, 5));
            }
            tools.addView(Ui.button(this, "Auto", Ui.RAISE, Ui.TXT, () -> {
                Library.clearColor(this, g);
                restartWidgetQuietly();
                showPage(P_SOUNDS);
            }), Ui.lp(this, WRAP, WRAP, 0, 6));
            if (isCustomGroup(g)) {
                tools.addView(new View(this), Ui.lp(0, 1, 1f));
                tools.addView(Ui.button(this, "Delete group", Ui.RAISE, Ui.TXT, () -> confirmDeleteGroup(g)),
                        Ui.lp(this, WRAP, WRAP, 0, 4));
            }
            page.addView(tools, Ui.lp(MATCH, WRAP, 0));
        }

        ScrollView sv = new ScrollView(this);
        LinearLayout grid = Ui.col(this);
        List<Sound> shown = new ArrayList<>();
        for (Sound s : everything) {
            if ("All".equals(category) || s.category.equals(category)) {
                shown.add(s);
            }
        }
        if (shown.isEmpty()) {
            TextView none = Ui.text(this, "Nothing here yet. Tap Browse web or Add sounds to bring in your own MP3 "
                    + "or WAV files. Long-press a sound to move, rename, favourite or remove it.",
                    16f, Ui.DIM, false);
            none.setPadding(Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 8), 0);
            grid.addView(none);
        }
        final int perRow = 4;
        LinearLayout rowView = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % perRow == 0) {
                rowView = new LinearLayout(this);
                rowView.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(rowView, Ui.lp(MATCH, WRAP, 0));
            }
            rowView.addView(tile(shown.get(i)), Ui.lp(this, 0, Ui.dp(this, 104), 1f, 6));
        }
        if (rowView != null) {
            for (int i = shown.size() % perRow; i != 0 && i < perRow; i++) {
                rowView.addView(new View(this), Ui.lp(this, 0, Ui.dp(this, 104), 1f, 6));
            }
        }
        sv.addView(grid);
        page.addView(sv, Ui.lp(MATCH, 0, 1f));
        return page;
    }

    private View tile(final Sound s) {
        LinearLayout t = Ui.col(this);
        t.setBackground(Ui.box(this, Ui.blend(Library.colorOf(this, s.category), Ui.SURF, 0.2f), 0, 18));
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p);
        t.setGravity(Gravity.BOTTOM);
        t.setClickable(true);
        t.setFocusable(true);
        TextView name = Ui.text(this, s.label, 18f, Ui.TXT, true);
        name.setSingleLine(true);
        t.addView(name);
        boolean starred = s.file != null && Library.isFav(this, s.file);
        t.addView(Ui.text(this, starred ? s.category + "  \u00b7  favourite" : s.category, 13f,
                starred ? Ui.OUT : Ui.DIM, false));
        t.setOnClickListener(v -> play(s));
        if (s.file != null) {
            t.setOnLongClickListener(v -> {
                final List<String> groups = userGroups();
                final boolean fav = Library.isFav(this, s.file);
                final String[] items = new String[groups.size() + 4];
                for (int k = 0; k < groups.size(); k++) {
                    items[k] = "Move to " + groups.get(k);
                }
                items[groups.size()] = "Move to a new group...";
                items[groups.size() + 1] = fav ? "Remove from favourites" : "Add to favourites";
                items[groups.size() + 2] = "Rename";
                items[groups.size() + 3] = "Remove from the app";
                new android.app.AlertDialog.Builder(this)
                        .setTitle(s.label)
                        .setItems(items, (d, which) -> {
                            if (which < groups.size()) {
                                moveSound(s, groups.get(which));
                            } else if (which == groups.size()) {
                                promptNewGroup(created -> moveSound(s, created));
                            } else if (which == groups.size() + 1) {
                                Library.setFav(this, s.file, !fav);
                                setStatus(fav ? "Removed " + s.label + " from favourites."
                                        : "Added " + s.label + " to favourites.");
                                restartWidgetQuietly();
                                showPage(P_SOUNDS);
                            } else if (which == groups.size() + 2) {
                                renameSound(s);
                            } else {
                                playSeq++;
                                Player.stop();
                                getSharedPreferences("groups", MODE_PRIVATE).edit()
                                        .remove(s.file.getName()).apply();
                                setStatus(s.file.delete() ? "Removed " + s.label + "."
                                        : "Could not remove " + s.label + ".");
                                showPage(P_SOUNDS);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            });
        }
        return t;
    }

    private void renameSound(final Sound s) {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setSingleLine(true);
        in.setText(s.label);
        in.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Rename sound")
                .setView(in)
                .setPositiveButton("Rename", (d, w) -> {
                    String label = in.getText().toString().replaceAll("[\\\\/:*?\"<>|\\n]", " ").trim();
                    if (label.isEmpty() || label.equals(s.label)) {
                        return;
                    }
                    playSeq++;
                    Player.stop();
                    File dst = Library.rename(this, s.file, label);
                    setStatus(dst == null ? "Could not rename " + s.label + "." : "Renamed to " + Library.label(dst) + ".");
                    restartWidgetQuietly();
                    showPage(P_SOUNDS);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Restarts the floating widget, if it is showing, so it picks up a library change. */
    private void restartWidgetQuietly() {
        if (FloatService.running) {
            stopService(new Intent(this, FloatService.class));
            h.postDelayed(() -> {
                try {
                    startForegroundService(new Intent(this, FloatService.class));
                } catch (Throwable ignored) {
                }
            }, 500);
        }
    }

    private void play(final Sound s) {
        final boolean outside = Prefs.outer(this);
        AudioDeviceInfo dev;
        if (outside) {
            dev = audio.outer();
            if (dev == null) {
                setStatus("Outside speaker not found on this car. Setup lists the audio outputs it does have.");
                return;
            }
        } else {
            dev = audio.notifyBus();
            if (dev == null) {
                dev = audio.inner();
            }
        }
        final int usage = outside ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_NOTIFICATION;
        final int token = ++playSeq;
        setStatus("Playing " + s.label + (outside ? " through the outside speaker." : " in the cabin."));
        Player.play(this, s.asset, s.file, dev, usage,
                (ok, msg) -> runOnUiThread(() -> {
                    if (token != playSeq) {
                        return;
                    }
                    if (ok) {
                        setStatus("Finished: " + s.label + ".");
                    } else {
                        setStatus("Could not play " + s.label + ". " + ascii(msg));
                    }
                }));
    }

    // ------------------------------------------------------------------ float

    private void toggleFloat() {
        try {
            if (FloatService.running) {
                stopService(new Intent(this, FloatService.class));
                sp.edit().putBoolean("float_on", false).apply();
                setStatus("Floating soundboard hidden.");
                h.postDelayed(() -> {
                    if (page == P_WIDGET) {
                        showPage(P_WIDGET);
                    }
                }, 400);
                return;
            }
            if (!android.provider.Settings.canDrawOverlays(this)) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())));
                    setStatus("Allow CarTalk to display over other apps, come back, and switch the widget on again.");
                } catch (Throwable t) {
                    setStatus("This head unit has no screen for allowing apps to draw over others, "
                            + "so the floating soundboard cannot be shown.");
                }
                return;
            }
            sp.edit().putBoolean("float_on", true).apply();
            startFloat();
        } catch (Throwable t) {
            setStatus("Could not change the floating soundboard: " + t.getClass().getSimpleName());
        }
    }

    private void startFloat() {
        startForegroundService(new Intent(this, FloatService.class));
        h.postDelayed(() -> {
            if (FloatService.running) {
                setStatus("Floating soundboard is on. Drag the round button anywhere; tap it to open.");
            } else {
                setStatus("Floating soundboard did not start. " + ascii(FloatService.lastError));
            }
            if (page == P_WIDGET) {
                showPage(P_WIDGET);
            }
        }, 600);
    }

    // ---------------------------------------------------------------- browser

    private android.webkit.WebView web;

    private void closeBrowser() {
        if (web != null) {
            try {
                web.stopLoading();
                web.destroy();
            } catch (Throwable ignored) {
            }
            web = null;
        }
    }

    /** A small browser. Any audio file it is asked to download is saved into the chosen group. */
    private void showBrowser() {
        closeBrowser();
        title.setText("Browse web");
        host.removeAllViews();
        LinearLayout pg = Ui.col(this);
        int p = Ui.dp(this, 14);
        pg.setPadding(p, Ui.dp(this, 10), p, 0);

        final android.widget.EditText address = new android.widget.EditText(this);
        address.setSingleLine(true);
        address.setHint("Web address or search words");
        address.setTextColor(Ui.TXT);
        address.setHintTextColor(Ui.DIM);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        address.setText(sp.getString("browser_url", ""));

        try {
            web = new android.webkit.WebView(this);
        } catch (Throwable t) {
            web = null;
            TextView none = Ui.text(this, "This head unit has no built-in web view, so the in-app browser "
                    + "cannot run here. Keep using the car's browser and Add sounds. ("
                    + t.getClass().getSimpleName() + ")", 16f, Ui.DIM, false);
            none.setPadding(Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 8), 0);
            pg.addView(none);
            pg.addView(Ui.button(this, "Back to Library", Ui.RAISE, Ui.TXT, () -> showPage(P_SOUNDS)),
                    Ui.lp(this, WRAP, WRAP, 0, 8));
            host.addView(pg, new FrameLayout.LayoutParams(MATCH, MATCH));
            return;
        }
        final android.webkit.WebView w = web;
        w.getSettings().setJavaScriptEnabled(true);
        w.getSettings().setDomStorageEnabled(true);
        w.getSettings().setMediaPlaybackRequiresUserGesture(true);
        final Runnable go = () -> {
            String t = address.getText().toString().trim();
            if (t.isEmpty()) {
                return;
            }
            String url;
            if (t.startsWith("http://") || t.startsWith("https://")) {
                url = t;
            } else if (t.contains(".") && !t.contains(" ")) {
                url = "https://" + t;
            } else {
                url = "https://duckduckgo.com/?q=" + android.net.Uri.encode(t);
            }
            w.loadUrl(url);
        };
        address.setOnEditorActionListener((v, actionId, event) -> {
            go.run();
            return true;
        });
        w.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(android.webkit.WebView view,
                                                    android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                String path = request.getUrl().getPath();
                if (path != null && Library.isAudio(path)) {
                    captureAudio(url, null, null);
                    return true;
                }
                return !(url.startsWith("http://") || url.startsWith("https://"));
            }

            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                if (url != null && url.startsWith("http")) {
                    address.setText(url);
                    sp.edit().putString("browser_url", url).apply();
                }
            }
        });
        w.setDownloadListener((url, userAgent, contentDisposition, mime, length) ->
                captureAudio(url, contentDisposition, mime));

        LinearLayout bar = Ui.row(this);
        bar.addView(Ui.button(this, "Back", Ui.RAISE, Ui.TXT, () -> {
            if (w.canGoBack()) {
                w.goBack();
            }
        }), Ui.lp(this, WRAP, WRAP, 0, 3));
        bar.addView(address, Ui.lp(0, WRAP, 1f));
        bar.addView(Ui.button(this, "Go", Ui.OK, Ui.BG, go), Ui.lp(this, WRAP, WRAP, 0, 3));
        bar.addView(Ui.button(this, "Done", Ui.TXT, Ui.BG, () -> {
            category = importGroup;
            showPage(P_SOUNDS);
        }), Ui.lp(this, WRAP, WRAP, 0, 3));
        pg.addView(bar, Ui.lp(MATCH, WRAP, 0));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout chips = Ui.row(this);
        hs.addView(chips);
        pg.addView(hs, Ui.lp(MATCH, WRAP, 0));
        fillBrowserChips(chips);

        pg.addView(w, Ui.lp(MATCH, 0, 1f));
        host.addView(pg, new FrameLayout.LayoutParams(MATCH, MATCH));
        setStatus("Tap a download link for an audio file and it is saved into the selected group.");
        if (!address.getText().toString().trim().isEmpty()) {
            go.run();
        }
    }

    /** Group chips for the browser; rebuilt in place so the page being viewed is not reloaded. */
    private void fillBrowserChips(final LinearLayout chips) {
        chips.removeAllViews();
        chips.addView(Ui.text(this, "Save into", 15f, Ui.DIM, true), Ui.lp(this, WRAP, WRAP, 0, 6));
        for (final String g : userGroups()) {
            boolean on = g.equals(importGroup);
            chips.addView(Ui.button(this, g, on ? Ui.TXT : Ui.SURF, on ? Ui.BG : Ui.DIM, () -> {
                importGroup = g;
                fillBrowserChips(chips);
            }), Ui.lp(this, WRAP, WRAP, 0, 3));
        }
        chips.addView(Ui.button(this, "+ New group", Ui.RAISE, Ui.TXT, () -> promptNewGroup(created -> {
            importGroup = created;
            fillBrowserChips(chips);
        })), Ui.lp(this, WRAP, WRAP, 0, 3));
    }

    /** Downloads an audio file the browser was asked for, then lets the user name it. */
    private void captureAudio(final String url, final String disposition, final String mime) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            setStatus("That download is generated inside the page and cannot be captured. "
                    + "Look for a direct download link instead.");
            return;
        }
        final String group = importGroup;
        final String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
        final String agent = web != null ? web.getSettings().getUserAgentString() : "CarTalk";
        setStatus("Downloading...");
        new Thread(() -> {
            java.net.HttpURLConnection con = null;
            File tmp = null;
            String err = null;
            String name = null;
            try {
                con = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                con.setConnectTimeout(10000);
                con.setReadTimeout(30000);
                con.setInstanceFollowRedirects(true);
                con.setRequestProperty("User-Agent", agent);
                if (cookies != null) {
                    con.setRequestProperty("Cookie", cookies);
                }
                int code = con.getResponseCode();
                if (code != 200) {
                    throw new IllegalStateException("the site answered " + code);
                }
                String type = mime != null && !mime.isEmpty() ? mime : con.getContentType();
                String disp = disposition != null ? disposition : con.getHeaderField("Content-Disposition");
                name = android.webkit.URLUtil.guessFileName(con.getURL().toString(), disp, type);
                if (!Library.isAudio(name)) {
                    if (type != null && type.startsWith("audio/")) {
                        int dot = name.lastIndexOf('.');
                        name = (dot > 0 ? name.substring(0, dot) : name) + ".mp3";
                    } else {
                        throw new IllegalStateException("that link is not an audio file");
                    }
                }
                tmp = new File(getCacheDir(), "browser-download.tmp");
                try (java.io.InputStream in = con.getInputStream();
                     java.io.FileOutputStream os = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[32768];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        total += n;
                        if (total > 40L * 1024 * 1024) {
                            throw new IllegalStateException("file is larger than 40 MB");
                        }
                    }
                }
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
            final String e = err;
            final File got = tmp;
            final String fileName = name;
            runOnUiThread(() -> {
                if (e != null || got == null || fileName == null) {
                    setStatus("Could not save that sound: " + ascii(e) + ".");
                    return;
                }
                nameAndKeep(got, fileName, group);
            });
        }, "browser-download").start();
    }

    private void nameAndKeep(final File tmp, String fileName, final String group) {
        int dot = fileName.lastIndexOf('.');
        final String ext = dot > 0 ? fileName.substring(dot).toLowerCase() : ".mp3";
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setSingleLine(true);
        in.setText(base.replace('_', ' ').replace('-', ' ').trim());
        in.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Name this sound (saving into " + group + ")")
                .setView(in)
                .setCancelable(false)
                .setPositiveButton("Save", (d, wch) -> {
                    String label = in.getText().toString().replaceAll("[\\\\/:*?\"<>|\\n]", " ").trim();
                    if (label.isEmpty()) {
                        label = "Sound";
                    }
                    File dst = new File(Library.dir(this), label + ext);
                    for (int k = 2; dst.exists(); k++) {
                        dst = new File(Library.dir(this), label + " " + k + ext);
                    }
                    try {
                        try (java.io.InputStream is = new java.io.FileInputStream(tmp);
                             java.io.OutputStream os = new java.io.FileOutputStream(dst)) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = is.read(buf)) > 0) {
                                os.write(buf, 0, n);
                            }
                        }
                        getSharedPreferences("groups", MODE_PRIVATE).edit().putString(dst.getName(), group).apply();
                        setStatus("Saved " + Library.label(dst) + " into " + group + ".");
                    } catch (Throwable t) {
                        setStatus("Could not save that sound: " + t.getClass().getSimpleName() + ".");
                    }
                    tmp.delete();
                })
                .setNegativeButton("Discard", (d, wch) -> {
                    tmp.delete();
                    setStatus("Discarded.");
                })
                .show();
    }

    // ----------------------------------------------------------------- groups

    private interface NameCb {
        void got(String name);
    }

    private List<String> customGroups() {
        List<String> out = new ArrayList<>();
        String raw = getSharedPreferences("groups_meta", MODE_PRIVATE).getString("custom", "");
        for (String g : raw.split("\n")) {
            if (!g.trim().isEmpty()) {
                out.add(g.trim());
            }
        }
        return out;
    }

    private void saveCustomGroups(List<String> groups) {
        StringBuilder b = new StringBuilder();
        for (String g : groups) {
            b.append(g).append('\n');
        }
        getSharedPreferences("groups_meta", MODE_PRIVATE).edit().putString("custom", b.toString()).apply();
    }

    /** Presets first, then the user's own groups. */
    private List<String> userGroups() {
        List<String> out = new ArrayList<>();
        for (String g : USER_GROUPS) {
            out.add(g);
        }
        out.addAll(customGroups());
        return out;
    }

    private boolean isCustomGroup(String name) {
        return customGroups().contains(name);
    }

    private boolean groupNameTaken(String name) {
        if ("All".equalsIgnoreCase(name)) {
            return true;
        }
        for (String g : userGroups()) {
            if (g.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private void promptNewGroup(final NameCb cb) {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("Group name");
        in.setSingleLine(true);
        in.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});
        new android.app.AlertDialog.Builder(this)
                .setTitle("New group")
                .setView(in)
                .setPositiveButton("Create", (d, w) -> {
                    String name = in.getText().toString().replace('\n', ' ').trim();
                    if (name.isEmpty()) {
                        setStatus("No group created: the name was empty.");
                        return;
                    }
                    if (groupNameTaken(name)) {
                        setStatus("There is already a group called " + name + ".");
                        return;
                    }
                    List<String> custom = customGroups();
                    custom.add(name);
                    saveCustomGroups(custom);
                    setStatus("Created group " + name + ".");
                    cb.got(name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteGroup(final String name) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Delete group " + name + "?")
                .setMessage("Its sounds are kept and move to My sounds.")
                .setPositiveButton("Delete", (d, w) -> {
                    List<String> custom = customGroups();
                    custom.remove(name);
                    saveCustomGroups(custom);
                    SharedPreferences groups = getSharedPreferences("groups", MODE_PRIVATE);
                    SharedPreferences.Editor e = groups.edit();
                    for (java.util.Map.Entry<String, ?> en : groups.getAll().entrySet()) {
                        if (name.equals(en.getValue())) {
                            e.putString(en.getKey(), "My sounds");
                        }
                    }
                    e.apply();
                    if (name.equals(importGroup)) {
                        importGroup = "My sounds";
                    }
                    if (name.equals(category)) {
                        category = "All";
                    }
                    setStatus("Deleted group " + name + ".");
                    if ("Add sounds".contentEquals(title.getText())) {
                        showImport();
                    } else {
                        showPage(P_SOUNDS);
                    }
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    private void moveSound(Sound s, String group) {
        getSharedPreferences("groups", MODE_PRIVATE).edit().putString(s.file.getName(), group).apply();
        setStatus("Moved " + s.label + " to " + group + ".");
        category = group;
        showPage(P_SOUNDS);
    }

    // ----------------------------------------------------------------- import

    private String importGroup = "My sounds";

    private void showImport() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_FILES);
        }
        title.setText("Add sounds");
        host.removeAllViews();
        LinearLayout page = Ui.col(this);
        int p = Ui.dp(this, 20);
        page.setPadding(p, Ui.dp(this, 14), p, 0);

        final List<File> found = new ArrayList<>();
        final List<TextView> names = new ArrayList<>();

        LinearLayout top = Ui.row(this);
        TextView help = Ui.text(this, "Audio files and zips in Downloads, Music and on USB. Pick a group, then "
                + "tap a file or Add all. A zip's folders become groups. Long-press your own group to delete it.", 15f, Ui.DIM, false);
        top.addView(help, Ui.lp(0, WRAP, 1f));
        top.addView(Ui.button(this, "Add all", Ui.OK, Ui.BG, () -> {
            for (int i = 0; i < found.size(); i++) {
                importOne(found.get(i), names.get(i));
            }
        }), Ui.lp(this, WRAP, WRAP, 0, 4));
        top.addView(Ui.button(this, "Scan again", Ui.RAISE, Ui.TXT, this::showImport),
                Ui.lp(this, WRAP, WRAP, 0, 4));
        top.addView(Ui.button(this, "Done", Ui.TXT, Ui.BG, () -> {
            category = importGroup;
            showPage(P_SOUNDS);
        }), Ui.lp(this, Ui.dp(this, 120), WRAP, 0, 4));
        page.addView(top, Ui.lp(MATCH, WRAP, 0));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(this);
        chips.addView(Ui.text(this, "File under", 15f, Ui.DIM, true), Ui.lp(this, WRAP, WRAP, 0, 6));
        for (final String g : userGroups()) {
            boolean on = g.equals(importGroup);
            TextView chip = Ui.button(this, g, on ? Ui.TXT : Ui.SURF, on ? Ui.BG : Ui.DIM, () -> {
                importGroup = g;
                showImport();
            });
            if (isCustomGroup(g)) {
                chip.setOnLongClickListener(v -> {
                    confirmDeleteGroup(g);
                    return true;
                });
            }
            chips.addView(chip, Ui.lp(this, WRAP, WRAP, 0, 4));
        }
        chips.addView(Ui.button(this, "+ New group", Ui.RAISE, Ui.TXT, () -> promptNewGroup(name -> {
            importGroup = name;
            showImport();
        })), Ui.lp(this, WRAP, WRAP, 0, 4));
        hs.addView(chips);
        page.addView(hs, Ui.lp(MATCH, WRAP, 0));

        ScrollView sv = new ScrollView(this);
        final LinearLayout list = Ui.col(this);
        list.addView(Ui.text(this, "Scanning...", 16f, Ui.DIM, false));
        sv.addView(list);
        page.addView(sv, Ui.lp(MATCH, 0, 1f));
        host.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));

        new Thread(() -> {
            final List<File> scan = Library.findImportable();
            runOnUiThread(() -> {
                list.removeAllViews();
                if (scan.isEmpty()) {
                    TextView none = Ui.text(this, "No audio files found. Download MP3 or WAV files with "
                            + "the car's browser, or plug in a USB stick, then tap Scan again. If Android "
                            + "asked for permission to read files, allow it first.", 16f, Ui.DIM, false);
                    none.setPadding(Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 8), 0);
                    list.addView(none);
                    return;
                }
                for (final File f : scan) {
                    LinearLayout r = Ui.col(this);
                    r.setBackground(Ui.box(this, Ui.SURF, Ui.LINE, 14));
                    int rp = Ui.dp(this, 14);
                    r.setPadding(rp, rp, rp, rp);
                    r.setClickable(true);
                    r.setFocusable(true);
                    final boolean zip = Library.isZip(f.getName());
                    boolean have = !zip && new File(Library.dir(this), f.getName()).isFile();
                    final TextView name = Ui.text(this, zip ? f.getName() + "  (zip: tap to unpack)"
                            : have ? f.getName() + "  (already added)" : f.getName(),
                            17f, have ? Ui.DIM : zip ? Ui.CAB : Ui.TXT, true);
                    name.setSingleLine(true);
                    r.addView(name);
                    String parent = f.getParent() == null ? "" : f.getParent();
                    r.addView(Ui.text(this, parent + "  \u00b7  " + (f.length() / 1024) + " KB", 13f, Ui.DIM, false));
                    r.setOnClickListener(v -> {
                        if (zip) {
                            importZip(f, name);
                        } else {
                            importOne(f, name);
                        }
                    });
                    list.addView(r, Ui.lp(this, MATCH, WRAP, 0, 4));
                    if (!have && !zip) {
                        found.add(f);
                        names.add(name);
                    }
                }
            });
        }, "import-scan").start();
    }

    /** Maps a folder name from a zip onto an existing group, creating one if needed. */
    private String groupForFolder(String folder, String fallback) {
        String name = folder == null ? "" : folder.replace('\n', ' ').trim();
        if (name.isEmpty()) {
            return fallback;
        }
        if (name.length() > 24) {
            name = name.substring(0, 24).trim();
        }
        for (String g : userGroups()) {
            if (g.equalsIgnoreCase(name)) {
                return g;
            }
        }
        if (groupNameTaken(name)) {
            return fallback;
        }
        List<String> custom = customGroups();
        custom.add(name);
        saveCustomGroups(custom);
        return name;
    }

    private void importZip(final File f, final TextView name) {
        final String fallback = importGroup;
        setStatus("Unpacking " + f.getName() + "...");
        new Thread(() -> {
            final java.util.Map<String, String> folders = new java.util.LinkedHashMap<>();
            String err = null;
            int n = 0;
            try {
                n = Library.unpackZip(getApplicationContext(), f, folders);
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String e = err;
            final int count = n;
            runOnUiThread(() -> {
                SharedPreferences.Editor ed = getSharedPreferences("groups", MODE_PRIVATE).edit();
                for (java.util.Map.Entry<String, String> en : folders.entrySet()) {
                    ed.putString(en.getKey(), groupForFolder(en.getValue(), fallback));
                }
                ed.apply();
                if (e != null) {
                    setStatus("Could not finish unpacking " + f.getName() + " (" + count + " added). " + ascii(e));
                } else if (count == 0) {
                    setStatus("No audio files found inside " + f.getName() + ".");
                } else {
                    name.setTextColor(Ui.OK);
                    name.setText(f.getName() + "  (" + count + " sounds added)");
                    setStatus("Added " + count + " sounds from " + f.getName() + ".");
                }
            });
        }, "import-zip").start();
    }

    private void importOne(final File f, final TextView name) {
        final String group = importGroup;
        setStatus("Copying " + f.getName() + "...");
        new Thread(() -> {
            String err = null;
            try {
                Library.importFile(getApplicationContext(), f);
                getSharedPreferences("groups", MODE_PRIVATE).edit().putString(f.getName(), group).apply();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String e = err;
            runOnUiThread(() -> {
                if (e == null) {
                    name.setTextColor(Ui.OK);
                    name.setText(f.getName() + "  (added to " + group + ")");
                    setStatus("Added " + f.getName() + " to " + group + ".");
                } else {
                    setStatus("Could not copy " + f.getName() + ". " + ascii(e));
                }
            });
        }, "import-copy").start();
    }

    /** Keeps only the plain-English part of a message from the original code. */
    private static String ascii(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c < 127) {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    // ----------------------------------------------------------------- widget

    /** Restarts the floating widget so a changed setting takes effect, then redraws this page. */
    private void applyWidget(String message) {
        if (FloatService.running) {
            stopService(new Intent(this, FloatService.class));
            h.postDelayed(this::startFloat, 500);
        }
        setStatus(message);
        showPage(P_WIDGET);
    }

    private View widgetRow(String name, String detail, String buttonLabel, boolean on, Runnable action) {
        LinearLayout r = Ui.row(this);
        LinearLayout texts = Ui.col(this);
        texts.addView(Ui.text(this, name, 18f, Ui.TXT, true));
        texts.addView(Ui.text(this, detail, 14f, Ui.DIM, false));
        r.addView(texts, Ui.lp(0, WRAP, 1f));
        r.addView(Ui.button(this, buttonLabel, on ? Ui.OK : Ui.RAISE, on ? Ui.BG : Ui.TXT, action),
                Ui.lp(this, Ui.dp(this, 150), WRAP, 0, 4));
        r.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        return r;
    }

    private View buildWidget() {
        ScrollView sv = new ScrollView(this);
        LinearLayout pg = Ui.col(this);
        int p = Ui.dp(this, 14);
        pg.setPadding(p, p, p, p);
        LinearLayout c = Ui.card(this);

        final boolean shown = FloatService.running;
        c.addView(widgetRow("Floating widget", "A small soundboard that stays on top of other apps. It comes "
                + "back each time CarTalk is opened while this is on.", shown ? "On" : "Off", shown, () -> {
                    toggleFloat();
                    h.postDelayed(() -> {
                        if (HomeActivity.this.page == P_WIDGET) {
                            showPage(P_WIDGET);
                        }
                    }, 900);
                }));

        final boolean ptt = sp.getBoolean("float_ptt", false);
        c.addView(widgetRow("Push to talk", "Adds a Hold to talk bar that sends the cabin microphone to the "
                + "outside speaker.", ptt ? "On" : "Off", ptt, () -> {
                    sp.edit().putBoolean("float_ptt", !ptt).apply();
                    if (!ptt && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    }
                    applyWidget(ptt ? "Push to talk removed from the widget." : "Push to talk added to the widget.");
                }));

        final boolean whoopee = sp.getBoolean("float_whoopee", false);
        c.addView(widgetRow("Whoopee sounds", "Shows the Whoopee group and a seat picker in the widget. "
                + "These always play inside the cabin.", whoopee ? "On" : "Off", whoopee, () -> {
                    sp.edit().putBoolean("float_whoopee", !whoopee).apply();
                    applyWidget(whoopee ? "Whoopee removed from the widget." : "Whoopee added to the widget.");
                }));

        final float scale = Math.max(0.8f, Math.min(2.2f, sp.getFloat("float_scale", 1.25f)));
        LinearLayout size = Ui.row(this);
        LinearLayout st = Ui.col(this);
        st.addView(Ui.text(this, "Size: " + Math.round(scale * 100) + "%", 18f, Ui.TXT, true));
        st.addView(Ui.text(this, "You can also pinch the widget with two fingers.", 14f, Ui.DIM, false));
        size.addView(st, Ui.lp(0, WRAP, 1f));
        size.addView(Ui.button(this, "Smaller", Ui.RAISE, Ui.TXT, () -> {
            sp.edit().putFloat("float_scale", Math.max(0.8f, scale - 0.15f)).apply();
            applyWidget("Widget made smaller.");
        }), Ui.lp(this, WRAP, WRAP, 0, 4));
        size.addView(Ui.button(this, "Bigger", Ui.RAISE, Ui.TXT, () -> {
            sp.edit().putFloat("float_scale", Math.min(2.2f, scale + 0.15f)).apply();
            applyWidget("Widget made bigger.");
        }), Ui.lp(this, WRAP, WRAP, 0, 4));
        size.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        c.addView(size);

        final String[] stages = {"Icon", "Minimal panel", "Full board"};
        for (int i = 0; i < stages.length; i++) {
            final String key = "float_alpha_" + i;
            final String stage = stages[i];
            final int pct = Math.max(20, Math.min(100, sp.getInt(key, 100)));
            LinearLayout op = Ui.row(this);
            LinearLayout ot = Ui.col(this);
            ot.addView(Ui.text(this, stage + " opacity: " + pct + "%", 18f, Ui.TXT, true));
            if (i == 0) {
                ot.addView(Ui.text(this, "Lower values let more of the screen behind show through.",
                        14f, Ui.DIM, false));
            }
            op.addView(ot, Ui.lp(0, WRAP, 1f));
            op.addView(Ui.button(this, "Less", Ui.RAISE, Ui.TXT, () -> {
                sp.edit().putInt(key, Math.max(20, pct - 10)).apply();
                applyWidget(stage + " opacity set to " + Math.max(20, pct - 10) + "%.");
            }), Ui.lp(this, WRAP, WRAP, 0, 4));
            op.addView(Ui.button(this, "More", Ui.RAISE, Ui.TXT, () -> {
                sp.edit().putInt(key, Math.min(100, pct + 10)).apply();
                applyWidget(stage + " opacity set to " + Math.min(100, pct + 10) + "%.");
            }), Ui.lp(this, WRAP, WRAP, 0, 4));
            op.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            c.addView(op);
        }

        c.addView(widgetRow("Position", "Lost it off screen? This puts it back near the top left.",
                "Reset", false, () -> {
                    sp.edit().putInt("float_x", Ui.dp(this, 40)).putInt("float_y", Ui.dp(this, 160))
                            .putBoolean("float_docked", false).apply();
                    applyWidget("Widget position reset.");
                }));

        c.addView(Ui.text(this, "Favourites: long-press a sound in the widget's full view, or on the Sounds "
                + "page, to add it to the widget's middle view.", 14f, Ui.DIM, false));
        pg.addView(c, Ui.lp(this, MATCH, WRAP, 0, 6));
        sv.addView(pg);
        return sv;
    }

    // ------------------------------------------------------------------ setup

    private View buildSetup() {
        ScrollView sv = new ScrollView(this);
        LinearLayout page = Ui.col(this);
        int p = Ui.dp(this, 14);
        page.setPadding(p, p, p, p);

        LinearLayout app = Ui.card(this);
        app.addView(Ui.text(this, "App and updates", 20f, Ui.TXT, true));
        app.addView(Ui.text(this, "Installed: build " + Updater.currentBuild(this)
                + "  (" + versionName() + ")", 15f, Ui.DIM, false));
        updateText = Ui.text(this, updateMsg, 15f, Ui.TXT, false);
        updateText.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        app.addView(updateText);
        LinearLayout btns = Ui.row(this);
        btns.addView(Ui.button(this, "Check for updates", Ui.RAISE, Ui.TXT, () -> checkUpdate(true)),
                Ui.lp(this, WRAP, WRAP, 0, 4));
        btns.addView(Ui.button(this, "Install update", Ui.OK, Ui.BG, this::installUpdate),
                Ui.lp(this, WRAP, WRAP, 0, 4));
        btns.addView(Ui.button(this, "Open download in browser", Ui.RAISE, Ui.TXT, () -> {
            String url = latestUrl != null ? latestUrl : Updater.RELEASES_PAGE;
            if (!Updater.openInBrowser(this, url)) {
                setStatus("No browser could be opened. Releases are at " + Updater.RELEASES_PAGE);
            }
        }), Ui.lp(this, WRAP, WRAP, 0, 4));
        app.addView(btns);
        page.addView(app, Ui.lp(this, MATCH, WRAP, 0, 6));

        LinearLayout scan = Ui.card(this);
        scan.addView(Ui.text(this, "Car scan", 20f, Ui.TXT, true));
        scan.addView(Ui.text(this, "Read-only. Take a photo of this if something does not work.",
                15f, Ui.DIM, false));
        final TextView report = Ui.text(this, scanText(), 14f, Ui.TXT, false);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextIsSelectable(true);
        report.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        scan.addView(report);
        LinearLayout sb = Ui.row(this);
        sb.addView(Ui.button(this, "Scan again", Ui.RAISE, Ui.TXT, () -> report.setText(scanText())),
                Ui.lp(this, WRAP, WRAP, 0, 4));
        scan.addView(sb);
        page.addView(scan, Ui.lp(this, MATCH, WRAP, 0, 6));

        LinearLayout test = Ui.card(this);
        test.addView(Ui.text(this, "Speaker test", 20f, Ui.TXT, true));
        test.addView(Ui.text(this, "Plays a two-note tone on one audio channel at a time so you can find "
                + "which speakers each one drives. Note which seat you hear it from, and whether Left and "
                + "Right differ.", 15f, Ui.DIM, false));
        for (final AudioDeviceInfo d : audio.outputs()) {
            final String addr = d.getAddress() == null || d.getAddress().isEmpty() ? "type " + d.getType() : d.getAddress();
            LinearLayout r = Ui.row(this);
            TextView nm = Ui.text(this, addr, 15f, Ui.TXT, false);
            nm.setTypeface(Typeface.MONOSPACE);
            r.addView(nm, Ui.lp(0, WRAP, 1f));
            final String[] sideNames = {"Left", "Both", "Right"};
            for (int k = 0; k < 3; k++) {
                final int side = k - 1;
                r.addView(Ui.button(this, sideNames[k], Ui.RAISE, Ui.TXT, () -> {
                    final int token = ++playSeq;
                    setStatus("Test tone on " + addr + " (" + sideNames[side + 1].toLowerCase() + ").");
                    Player.play(this, "test/tone.wav", null, d, AudioAttributes.USAGE_MEDIA, true, side,
                            (ok, msg) -> runOnUiThread(() -> {
                                if (token == playSeq && !ok) {
                                    setStatus("No sound possible on " + addr + ". " + ascii(msg));
                                }
                            }));
                }), Ui.lp(this, WRAP, WRAP, 0, 3));
            }
            test.addView(r);
        }
        page.addView(test, Ui.lp(this, MATCH, WRAP, 0, 6));

        String crash = null;
        try {
            crash = Diag.lastCrash(this);
        } catch (Throwable ignored) {
        }
        if (crash != null && !crash.isEmpty()) {
            LinearLayout cr = Ui.card(this);
            cr.addView(Ui.text(this, "Last crash", 20f, Ui.TXT, true));
            TextView ct = Ui.text(this, crash.length() > 3000 ? crash.substring(0, 3000) : crash,
                    12f, Ui.DIM, false);
            ct.setTypeface(Typeface.MONOSPACE);
            ct.setTextIsSelectable(true);
            cr.addView(ct);
            page.addView(cr, Ui.lp(this, MATCH, WRAP, 0, 6));
        }
        sv.addView(page);
        return sv;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private String scanText() {
        StringBuilder b = new StringBuilder();
        try {
            b.append("Head unit: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(", Android ").append(Build.VERSION.RELEASE)
                    .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n\n");
            AudioDeviceInfo outer = audio.outer();
            b.append("Outside speaker (").append(AudioOut.OUTER_BUS).append("): ")
                    .append(outer != null ? "FOUND" : "NOT FOUND").append("\n\n");
            b.append("Audio outputs:\n");
            for (AudioDeviceInfo d : audio.outputs()) {
                b.append("  type ").append(d.getType()).append("  ")
                        .append(d.getAddress() == null || d.getAddress().isEmpty() ? "(no address)" : d.getAddress())
                        .append("  channels ").append(java.util.Arrays.toString(d.getChannelCounts()))
                        .append('\n');
            }
            b.append("\nMicrophones and inputs:\n");
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    b.append("  type ").append(d.getType()).append("  ")
                            .append(d.getAddress() == null || d.getAddress().isEmpty() ? "(no address)" : d.getAddress())
                            .append('\n');
                }
            }
            boolean ecarx = VehicleRead.carFunction(this) != null;
            boolean canInstall = false;
            try {
                canInstall = getPackageManager().canRequestPackageInstalls();
            } catch (Throwable ignored) {
            }
            b.append("\nIn-app update install allowed: ").append(canInstall ? "yes" : "NO").append('\n');
            b.append("\nVehicle interface (ECARX): ").append(ecarx ? "available" : "NOT available").append('\n');
            b.append("Power state signal:  ").append(signal((int) VehicleRead.ID_IGNITION)).append('\n');
            b.append("Sentry mode signal:  ").append(signal(CarCtl.SENTRY)).append('\n');
            b.append("Ambient light signal: ").append(signal(CarCtl.AMBIENCE)).append('\n');
        } catch (Throwable t) {
            b.append("\nScan failed: ").append(t.getClass().getSimpleName()).append(' ').append(t.getMessage());
        }
        return b.toString();
    }

    private String signal(int id) {
        int v = CarCtl.read(this, id);
        return v == Integer.MIN_VALUE ? "not readable" : v + " (0x" + Integer.toHexString(v).toUpperCase() + ")";
    }

    // ---------------------------------------------------------------- updates

    private void checkUpdate(final boolean userAsked) {
        if (userAsked) {
            setUpdateMsg("Checking GitHub for a newer build...");
        }
        Updater.check((build, url, error) -> runOnUiThread(() -> {
            int mine = Updater.currentBuild(this);
            if (error != null) {
                setUpdateMsg("Could not check for updates (" + error + ").");
                return;
            }
            latestBuild = build;
            latestUrl = url;
            if (build > mine) {
                setUpdateMsg("Build " + build + " is available. You have build " + mine + ".");
                updateBanner.setText("Update: build " + build);
                updateBanner.setVisibility(View.VISIBLE);
            } else {
                setUpdateMsg("You are up to date (build " + mine + ").");
                updateBanner.setVisibility(View.GONE);
            }
        }));
    }

    private void setUpdateMsg(String s) {
        updateMsg = s;
        if (updateText != null) {
            updateText.setText(s);
        }
    }

    private void installUpdate() {
        if (latestUrl == null || latestBuild <= Updater.currentBuild(this)) {
            setUpdateMsg("No newer build to install. Tap Check for updates first.");
            return;
        }
        final String url = latestUrl;
        setUpdateMsg("Downloading build " + latestBuild + "...");
        final int wanted = latestBuild;
        Updater.download(this, url, wanted, new Updater.Progress() {
            @Override
            public void at(final int percent) {
                runOnUiThread(() -> setUpdateMsg("Downloading build " + latestBuild + ": " + percent + "%"));
            }

            @Override
            public void done(final File apk, final String error) {
                runOnUiThread(() -> {
                    if (error != null || apk == null) {
                        setUpdateMsg("Download failed (" + error + "). Try Open download in browser.");
                        return;
                    }
                    int got = Updater.apkBuild(HomeActivity.this, apk);
                    if (got != wanted) {
                        setUpdateMsg("The download did not contain build " + wanted + " (it reads as build "
                                + got + ", " + (apk.length() / 1024) + " KB). Try Open download in browser.");
                        return;
                    }
                    boolean allowed = true;
                    try {
                        allowed = getPackageManager().canRequestPackageInstalls();
                    } catch (Throwable ignored) {
                    }
                    if (!allowed) {
                        // Android blocks installs from this app until the user allows it once.
                        boolean opened = false;
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    android.net.Uri.parse("package:" + getPackageName())));
                            opened = true;
                        } catch (Throwable ignored) {
                        }
                        if (opened) {
                            setUpdateMsg("Allow CarTalk to install apps on the screen that opened, come back, "
                                    + "and tap Install update again.");
                        } else if (Updater.openInBrowser(HomeActivity.this, url)) {
                            setUpdateMsg("This head unit does not let CarTalk install updates itself, so the "
                                    + "download was opened in the browser instead.");
                        } else {
                            setUpdateMsg("This head unit does not let CarTalk install updates itself. Download "
                                    + "build " + latestBuild + " from " + Updater.RELEASES_PAGE);
                        }
                        return;
                    }
                    if (Updater.install(HomeActivity.this, apk)) {
                        setUpdateMsg("Downloaded and checked build " + wanted + ". Installer opened; if it shows an "
                                + "older version or nothing appears, tap Open download in browser.");
                    } else if (Updater.openInBrowser(HomeActivity.this, url)) {
                        setUpdateMsg("The installer could not be opened from the app, so the download was "
                                + "opened in the browser instead.");
                    } else {
                        setUpdateMsg("The installer could not be opened. Download build " + latestBuild
                                + " from " + Updater.RELEASES_PAGE);
                    }
                });
            }
        });
    }

    // ----------------------------------------------------------------- errors

    private void installCrashSaver() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                Diag.saveCrash(getApplicationContext(), trace(ex));
            } catch (Throwable ignored) {
            }
            if (prev != null) {
                prev.uncaughtException(thread, ex);
            }
        });
    }

    private static String trace(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return w.toString();
    }

    private View errorView(Throwable t) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout c = Ui.col(this);
        int p = Ui.dp(this, 24);
        c.setPadding(p, p, p, p);
        c.addView(Ui.text(this, "The app could not start", 24f, Ui.TXT, true));
        c.addView(Ui.text(this, "Take a photo of this screen and send it.", 16f, Ui.DIM, false));
        TextView tr = Ui.text(this, trace(t), 12f, Ui.DIM, false);
        tr.setTypeface(Typeface.MONOSPACE);
        c.addView(tr);
        sv.addView(c);
        return sv;
    }
}
