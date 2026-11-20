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

    private static final String[] PAGES = {"Sounds", "Live mic", "Whoopee", "Setup"};
    private static final int P_SOUNDS = 0, P_MIC = 1, P_WHOOPEE = 2, P_SETUP = 3;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int REQ_MIC = 77;
    private static final int REQ_FILES = 78;
    private static final long LATCH_LIMIT_MS = 30000L;

    /** label, category, file under assets/sounds. */
    private static final String[][] BUILT_IN = {
            {"Friendly chime", "Courtesy", "friendly-chime.wav"},
            {"Soft beep", "Courtesy", "soft-beep.wav"},
            {"Double beep", "Courtesy", "double-beep.wav"},
            {"Attention tone", "Courtesy", "attention-three-tone.wav"},
            {"Bike bell", "Courtesy", "bike-bell.wav"},
            {"Doorbell", "Courtesy", "doorbell.wav"},
            {"Referee whistle", "Courtesy", "referee-whistle.wav"},
            {"Welcome chime", "Greetings", "welcome-chime.wav"},
            {"Goodbye chime", "Greetings", "goodbye-chime.wav"},
    };
    /** label, file under assets/farts. */
    private static final String[][] FARTS = {
            {"Quick toot", "quick-toot.wav"},
            {"Tiny pip", "tiny-pip.wav"},
            {"Squeaker", "squeaker.wav"},
            {"Rising question", "rising-question.wav"},
            {"Sputter", "sputter.wav"},
            {"Two-parter", "two-parter.wav"},
            {"Trouser trumpet", "trouser-trumpet.wav"},
            {"Long rumble", "long-rumble.wav"},
    };
    /** Preset groups the user can file imported sounds under. They can add their own too. */
    private static final String[] USER_GROUPS = {"My sounds", "F1", "Memes", "TV and film", "Useful", "Whoopee"};
    private static final String[] BUILT_IN_CATEGORIES = {"Courtesy", "Greetings"};

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
    private final LiveMic mic = new LiveMic();
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

    private boolean micBoost = true;
    private boolean latched;
    private ProgressBar micLevel;
    private TextView micState;
    private TextView latchBtn;

    private int latestBuild = -1;
    private String latestUrl;
    private String updateMsg = "Not checked yet.";
    private TextView updateText;

    private final Runnable micTick = new Runnable() {
        @Override
        public void run() {
            if (page != P_MIC) {
                return;
            }
            if (micLevel != null) {
                micLevel.setProgress(mic.level);
            }
            String err = mic.error;
            if (err != null) {
                mic.error = null;
                latched = false;
                setStatus("Live mic stopped: " + err);
            }
            refreshMicState();
            h.postDelayed(this, 100);
        }
    };

    private final Runnable latchOff = () -> {
        if (latched) {
            latched = false;
            mic.stop();
            setStatus("Live mic switched off automatically after 30 seconds.");
        }
    };

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
        } catch (Throwable t) {
            setContentView(errorView(t));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopMic();
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
                applyMicGain();
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
        if (page == P_MIC && idx != P_MIC) {
            stopMic();
        }
        page = idx;
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
        } else if (idx == P_MIC) {
            v = buildLiveMic();
        } else if (idx == P_WHOOPEE) {
            v = buildWhoopee();
        } else {
            v = buildSetup();
        }
        host.addView(v, new FrameLayout.LayoutParams(MATCH, MATCH));
        if (idx == P_MIC) {
            h.removeCallbacks(micTick);
            h.post(micTick);
        }
    }

    private void setStatus(String s) {
        if (status != null) {
            status.setText(s);
        }
    }

    // ----------------------------------------------------------------- sounds

    private List<Sound> allSounds() {
        List<Sound> out = new ArrayList<>();
        for (String[] b : BUILT_IN) {
            out.add(new Sound(b[0], b[1], b[2], null));
        }
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
        for (String c : BUILT_IN_CATEGORIES) {
            cats.add(c);
        }
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
        top.addView(Ui.button(this, "Add sounds", Ui.RAISE, Ui.TXT, this::showImport),
                Ui.lp(this, WRAP, WRAP, 0, 4));
        top.addView(Ui.button(this, "Stop", Ui.TXT, Ui.BG, () -> {
            playSeq++;
            Player.stop();
            setStatus("Stopped.");
        }), Ui.lp(this, Ui.dp(this, 140), WRAP, 0, 4));
        page.addView(top, Ui.lp(MATCH, WRAP, 0));

        ScrollView sv = new ScrollView(this);
        LinearLayout grid = Ui.col(this);
        List<Sound> shown = new ArrayList<>();
        for (Sound s : everything) {
            if ("All".equals(category) || s.category.equals(category)) {
                shown.add(s);
            }
        }
        if (shown.isEmpty()) {
            TextView none = Ui.text(this, "My sounds".equals(category)
                    ? "Nothing here yet. Tap Add sounds to bring in MP3 or WAV files. Long-press a "
                    + "sound afterwards to move it to another group."
                    : "Nothing in this category yet.", 16f, Ui.DIM, false);
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
        t.setBackground(Ui.box(this, Ui.SURF, Ui.LINE, 18));
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p);
        t.setGravity(Gravity.BOTTOM);
        t.setClickable(true);
        t.setFocusable(true);
        TextView name = Ui.text(this, s.label, 18f, Ui.TXT, true);
        name.setSingleLine(true);
        t.addView(name);
        t.addView(Ui.text(this, s.category, 13f, Ui.DIM, false));
        t.setOnClickListener(v -> play(s));
        if (s.file != null) {
            t.setOnLongClickListener(v -> {
                final List<String> groups = userGroups();
                final String[] items = new String[groups.size() + 2];
                for (int k = 0; k < groups.size(); k++) {
                    items[k] = "Move to " + groups.get(k);
                }
                items[groups.size()] = "Move to a new group...";
                items[groups.size() + 1] = "Remove from the app";
                new android.app.AlertDialog.Builder(this)
                        .setTitle(s.label)
                        .setItems(items, (d, which) -> {
                            if (which < groups.size()) {
                                moveSound(s, groups.get(which));
                            } else if (which == groups.size()) {
                                promptNewGroup(created -> moveSound(s, created));
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
        stopMic();
        final int token = ++playSeq;
        setStatus("Playing " + s.label + (outside ? " through the outside speaker." : " in the cabin."));
        Player.play(this, s.asset != null ? "sounds/" + s.asset : null, s.file, dev, usage,
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
        for (String g : BUILT_IN_CATEGORIES) {
            if (g.equalsIgnoreCase(name)) {
                return true;
            }
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
                    showImport();
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    private void moveSound(Sound s, String group) {
        getSharedPreferences("groups", MODE_PRIVATE).edit().putString(s.file.getName(), group).apply();
        setStatus("Moved " + s.label + " to " + group + ".");
        if ("Whoopee".equals(group)) {
            showPage(P_WHOOPEE);
        } else {
            category = group;
            showPage(P_SOUNDS);
        }
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
            category = "Whoopee".equals(importGroup) ? "All" : importGroup;
            showPage("Whoopee".equals(importGroup) ? P_WHOOPEE : P_SOUNDS);
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

    // --------------------------------------------------------------- live mic

    private View buildLiveMic() {
        LinearLayout page = Ui.row(this);
        int p = Ui.dp(this, 20);
        page.setPadding(p, p, p, p);

        LinearLayout mid = Ui.col(this);
        mid.setGravity(Gravity.CENTER);
        final TextView hold = Ui.text(this, "Hold to talk", 24f, Ui.TXT, true);
        hold.setGravity(Gravity.CENTER);
        hold.setBackground(Ui.box(this, Ui.WARN_BG, Ui.OUT, 110));
        hold.setClickable(true);
        hold.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                latched = false;
                h.removeCallbacks(latchOff);
                if (startMic()) {
                    hold.setBackground(Ui.box(this, Ui.OUT, 0, 110));
                    hold.setTextColor(Ui.BG);
                    hold.setText("Talking");
                }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                mic.stop();
                hold.setBackground(Ui.box(this, Ui.WARN_BG, Ui.OUT, 110));
                hold.setTextColor(Ui.TXT);
                hold.setText("Hold to talk");
            }
            return true;
        });
        mid.addView(hold, Ui.lp(Ui.dp(this, 220), Ui.dp(this, 220), 0));

        micState = Ui.text(this, "", 16f, Ui.DIM, true);
        micState.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ms = Ui.lp(MATCH, WRAP, 0);
        ms.topMargin = Ui.dp(this, 16);
        mid.addView(micState, ms);

        micLevel = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        micLevel.setMax(100);
        LinearLayout.LayoutParams ll = Ui.lp(Ui.dp(this, 320), Ui.dp(this, 12), 0);
        ll.topMargin = Ui.dp(this, 12);
        mid.addView(micLevel, ll);
        page.addView(mid, Ui.lp(0, MATCH, 1f));

        LinearLayout side = Ui.card(this);
        side.addView(Ui.text(this, "Options", 20f, Ui.TXT, true));
        latchBtn = Ui.button(this, "", Ui.RAISE, Ui.TXT, () -> {
            if (latched) {
                latched = false;
                h.removeCallbacks(latchOff);
                mic.stop();
                setStatus("Live mic off.");
            } else if (startMic()) {
                latched = true;
                h.removeCallbacks(latchOff);
                h.postDelayed(latchOff, LATCH_LIMIT_MS);
            }
        });
        side.addView(latchBtn, Ui.lp(this, MATCH, WRAP, 0, 6));
        final TextView boostBtn = Ui.button(this, "", Ui.RAISE, Ui.TXT, null);
        boostBtn.setText(micBoost ? "Voice boost: on" : "Voice boost: off");
        boostBtn.setOnClickListener(v -> {
            micBoost = !micBoost;
            boostBtn.setText(micBoost ? "Voice boost: on" : "Voice boost: off");
            applyMicGain();
        });
        side.addView(boostBtn, Ui.lp(this, MATCH, WRAP, 0, 6));
        TextView note = Ui.text(this, "Your voice goes from the cabin microphone to the outside speaker "
                + "with a short delay. Loudness follows the volume slider. Latch stays on for 30 seconds. "
                + "With windows open the speaker can feed back into the microphone, so turn the volume "
                + "down if it squeals.", 14f, Ui.DIM, false);
        note.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), 0);
        side.addView(note);
        page.addView(side, Ui.lp(Ui.dp(this, 360), MATCH, 0));
        refreshMicState();
        return page;
    }

    private void refreshMicState() {
        if (micState != null) {
            micState.setText(mic.running() ? "Live to the outside speaker" : "Microphone is off");
            micState.setTextColor(mic.running() ? Ui.OUT : Ui.DIM);
        }
        if (latchBtn != null) {
            latchBtn.setText(latched && mic.running() ? "Latch: on (tap to stop)" : "Latch: off (tap to stay on)");
        }
    }

    private void applyMicGain() {
        mic.gain = Player.gainPercent() / 100.0 * (micBoost ? 2.5 : 1.0);
    }

    private boolean startMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            setStatus("Allow microphone access, then try again.");
            return false;
        }
        AudioDeviceInfo dev = audio.outer();
        if (dev == null) {
            setStatus("Outside speaker not found on this car. Setup lists the audio outputs it does have.");
            return false;
        }
        playSeq++;
        Player.stop();
        applyMicGain();
        mic.start(dev);
        setStatus("Live mic on.");
        return true;
    }

    private void stopMic() {
        latched = false;
        h.removeCallbacks(latchOff);
        mic.stop();
    }

    // ---------------------------------------------------------------- whoopee

    private int fartSide;
    private int fartDelaySec;
    private final java.util.Random random = new java.util.Random();

    private View buildWhoopee() {
        LinearLayout page = Ui.col(this);
        int p = Ui.dp(this, 20);
        page.setPadding(p, Ui.dp(this, 14), p, 0);

        LinearLayout top = Ui.row(this);
        top.addView(Ui.text(this, "From", 15f, Ui.DIM, true), Ui.lp(this, WRAP, WRAP, 0, 6));
        final String[] sides = {"Left side", "Both sides", "Right side"};
        for (int i = 0; i < sides.length; i++) {
            final int side = i - 1;
            boolean on = fartSide == side;
            top.addView(Ui.button(this, sides[i], on ? Ui.CAB : Ui.SURF, on ? Ui.BG : Ui.DIM, () -> {
                fartSide = side;
                showPage(P_WHOOPEE);
            }), Ui.lp(this, WRAP, WRAP, 0, 4));
        }
        View gap = new View(this);
        top.addView(gap, Ui.lp(0, 1, 1f));
        top.addView(Ui.text(this, "Delay", 15f, Ui.DIM, true), Ui.lp(this, WRAP, WRAP, 0, 6));
        final int[] delays = {0, 5, 10, 30};
        for (final int dsec : delays) {
            boolean on = fartDelaySec == dsec;
            top.addView(Ui.button(this, dsec == 0 ? "None" : dsec + " s", on ? Ui.TXT : Ui.SURF,
                    on ? Ui.BG : Ui.DIM, () -> {
                        fartDelaySec = dsec;
                        showPage(P_WHOOPEE);
                    }), Ui.lp(this, WRAP, WRAP, 0, 4));
        }
        page.addView(top, Ui.lp(MATCH, WRAP, 0));

        ScrollView sv = new ScrollView(this);
        LinearLayout grid = Ui.col(this);
        final int perRow = 3;
        LinearLayout rowView = null;
        final List<Sound> farts = new ArrayList<>();
        for (String[] f : FARTS) {
            farts.add(new Sound(f[0], "Whoopee", "farts/" + f[1], null));
        }
        for (Sound u : allSounds()) {
            if (u.file != null && "Whoopee".equals(u.category)) {
                farts.add(u);
            }
        }
        for (int i = 0; i <= farts.size(); i++) {
            if (i % perRow == 0) {
                rowView = new LinearLayout(this);
                rowView.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(rowView, Ui.lp(MATCH, WRAP, 0));
            }
            final int idx = i - 1;
            TextView b = Ui.text(this, idx < 0 ? "Surprise me" : farts.get(idx).label, 19f,
                    idx < 0 ? Ui.BG : Ui.TXT, true);
            b.setGravity(Gravity.CENTER);
            b.setBackground(Ui.box(this, idx < 0 ? Ui.OUT : Ui.SURF, idx < 0 ? 0 : Ui.LINE, 18));
            b.setClickable(true);
            b.setFocusable(true);
            b.setOnClickListener(v -> queueFart(farts.get(idx < 0 ? random.nextInt(farts.size()) : idx)));
            rowView.addView(b, Ui.lp(this, 0, Ui.dp(this, 104), 1f, 6));
        }
        sv.addView(grid);
        page.addView(sv, Ui.lp(MATCH, 0, 1f));
        TextView note = Ui.text(this, "Always plays inside the cabin, whatever the Cabin / Outside switch says. "
                + "Left and right use the car's stereo speakers. Sounds you file under Whoopee appear here too.", 13f, Ui.DIM, false);
        note.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), 0);
        page.addView(note, Ui.lp(MATCH, WRAP, 0));
        return page;
    }

    private void queueFart(final Sound s) {
        final int side = fartSide;
        if (fartDelaySec <= 0) {
            playFart(s, side);
            return;
        }
        setStatus("Armed. Going off in " + fartDelaySec + " seconds.");
        h.postDelayed(() -> playFart(s, side), fartDelaySec * 1000L);
    }

    private void playFart(final Sound s, int side) {
        AudioDeviceInfo dev = audio.mediaBus();
        if (dev == null) {
            dev = audio.inner();
        }
        stopMic();
        final int token = ++playSeq;
        setStatus(s.label + (side < 0 ? " from the left." : side > 0 ? " from the right." : " from both sides."));
        Player.play(this, s.asset, s.file, dev, AudioAttributes.USAGE_MEDIA, true, side,
                (ok, msg) -> runOnUiThread(() -> {
                    if (token == playSeq && !ok) {
                        setStatus("Could not play " + s.label + ". " + ascii(msg));
                    }
                }));
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
        Updater.download(this, url, new Updater.Progress() {
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
                    if (Updater.install(HomeActivity.this, apk)) {
                        setUpdateMsg("Installer opened. Confirm the install to finish.");
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
