package com.carpiano;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The floating soundboard, drawn over other apps, in three sizes: a round icon,
 * a minimal panel of favourite pads, and the full board with a tab per group.
 *
 * <p>Each kind of control has its own look: sounds are tinted pads, groups are
 * underlined tabs, Cabin / Outside is one sliding switch, hold-to-talk is the
 * only round button, and window controls are small icons. Pads, tabs and the
 * switch update in place rather than rebuilding the panel.</p>
 */
public class FloatService extends Service {

    private static final String CH = "float";
    private static final int NOTE_ID = 6101;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int MINI = 0, FAVS = 1, FULL = 2;
    private static final String TAB_FAVS = "Favs";
    private static final String[] SEAT_NAMES = {"Cabin", "Driver", "Front L", "Rear L", "Rear R"};
    private static final int GOLD = 0xFFF5C451;

    static volatile boolean running;
    static volatile String lastError = "";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final LiveMic mic = new LiveMic();
    private final List<Pad> pads = new ArrayList<>();
    private final List<String> tabNames = new ArrayList<>();
    private final List<TextView> tabLabels = new ArrayList<>();
    private final List<View> tabLines = new ArrayList<>();
    private final List<TextView> seatViews = new ArrayList<>();

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private Shell shell;
    private SharedPreferences sp;
    private AudioOut audio;

    private int mode = MINI;
    private boolean docked;
    private boolean dockLeft;
    private float scale = 1.25f;
    private int seat;
    private String tab = TAB_FAVS;

    private LinearLayout gridHost;
    private LinearLayout bottomHost;
    private GradientDrawable panelBg;
    private TextView segCabin;
    private TextView segOutside;

    private File playing;
    private long playingMs;
    private int playToken;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        lastError = "";
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(new NotificationChannel(CH, "Floating soundboard",
                        NotificationManager.IMPORTANCE_LOW));
            }
            startForeground(NOTE_ID, new Notification.Builder(this, CH)
                    .setContentTitle("CarTalk")
                    .setContentText("Floating soundboard is on")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .build());

            audio = new AudioOut(this);
            sp = getSharedPreferences("piano", MODE_PRIVATE);
            Player.setGainPercent(sp.getInt("vol_pct", 60));
            Player.loadOutside(this);
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            lp = new WindowManager.LayoutParams(WRAP, WRAP,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = Math.max(0, sp.getInt("float_x", Ui.dp(this, 40)));
            lp.y = Math.max(0, sp.getInt("float_y", Ui.dp(this, 160)));
            docked = sp.getBoolean("float_docked", false);
            dockLeft = sp.getBoolean("float_dock_left", true);
            shell = new Shell();
            render(false);
            wm.addView(shell, lp);
            running = true;
            h.postDelayed(() -> Automations.fire(this, "widget_start"), 1500);
            h.postDelayed(powerWatch, 3000);
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            running = false;
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacks(powerWatch);
        mic.stop();
        try {
            if (wm != null && shell != null) {
                wm.removeView(shell);
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    /** Car power state as last seen: null until the first reading, so starting up fires nothing. */
    private Boolean carOn;

    /** Polls the car's power signal and fires the power on / off automations on a change. */
    private final Runnable powerWatch = new Runnable() {
        @Override
        public void run() {
            try {
                if (Automations.masterOn(FloatService.this)) {
                    List<Integer> onValues = Automations.onValues(FloatService.this);
                    int raw = CarCtl.read(FloatService.this, (int) VehicleRead.ID_IGNITION);
                    if (!onValues.isEmpty() && raw != Integer.MIN_VALUE) {
                        boolean on = onValues.contains(raw);
                        if (carOn != null && on != carOn) {
                            Automations.fire(FloatService.this, on ? "power_on" : "power_off");
                        }
                        carOn = on;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (running) {
                h.postDelayed(this, 2000);
            }
        }
    };

    // ---------------------------------------------------------------- helpers

    /** dp to pixels, multiplied by the widget's own size setting. */
    private int d(int dp) {
        return Ui.dp(this, Math.round(dp * scale));
    }

    private TextView tx(String s, float size, int color, boolean bold) {
        return Ui.text(this, s, size * scale, color, bold);
    }

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private static int blend(int color, int base, float amount) {
        int r = Math.round(Color.red(color) * amount + Color.red(base) * (1 - amount));
        int g = Math.round(Color.green(color) * amount + Color.green(base) * (1 - amount));
        int b = Math.round(Color.blue(color) * amount + Color.blue(base) * (1 - amount));
        return Color.rgb(r, g, b);
    }

    private void updateWindow() {
        try {
            wm.updateViewLayout(shell, lp);
        } catch (Throwable ignored) {
        }
    }

    /** Each size (icon, minimal, full) keeps its own scale. */
    private float scaleFor(int m) {
        float base = sp.getFloat("float_scale", 1.25f);
        return Math.max(0.5f, Math.min(2.5f, sp.getFloat("float_scale_" + m, base)));
    }

    private void setScale(float v) {
        sp.edit().putFloat("float_scale_" + mode, Math.max(0.5f, Math.min(2.5f, v))).apply();
        render(false);
    }

    private boolean whoopeeOn() {
        return sp.getBoolean("float_whoopee", false);
    }

    // ------------------------------------------------------------------ frame

    private void setMode(int m) {
        mode = m;
        render(true);
    }

    private void render(boolean animate) {
        scale = scaleFor(docked ? MINI : mode);
        pads.clear();
        gridHost = null;
        bottomHost = null;
        panelBg = null;
        segCabin = null;
        segOutside = null;
        View v;
        if (docked) {
            v = edgeTab();
        } else if (mode == FULL) {
            v = fullPanel();
        } else if (mode == FAVS) {
            v = minimalPanel();
        } else {
            v = bubble();
        }
        // Each size has its own opacity, set on the Widget page. The edge tab stays solid.
        float alpha = docked ? 1f
                : Math.max(20, Math.min(100, sp.getInt("float_alpha_" + mode, 100))) / 100f;
        shell.removeAllViews();
        shell.addView(v);
        if (animate) {
            v.setAlpha(0f);
            v.setScaleX(0.94f);
            v.setScaleY(0.94f);
            v.animate().alpha(alpha).scaleX(1f).scaleY(1f).setDuration(160).start();
        } else {
            v.setAlpha(alpha);
        }
    }

    private View bubble() {
        FrameLayout f = new FrameLayout(this);
        int s = d(68);
        f.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher_round);
        f.addView(icon, new FrameLayout.LayoutParams(MATCH, MATCH));
        if (Prefs.outer(this)) {
            View ring = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Color.TRANSPARENT);
            g.setStroke(d(3), Ui.OUT);
            ring.setBackground(g);
            f.addView(ring, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        f.setOnTouchListener(new Drag(() -> setMode(FAVS)));
        return f;
    }

    /** Thin tab parked on a screen edge. The widget itself never leaves the screen. */
    private View edgeTab() {
        View t = new View(this);
        t.setBackground(Ui.box(this, Ui.OUT, 0, 10));
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 110)));
        t.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                h.post(this::undock);
            }
            return true;
        });
        return t;
    }

    private void dock(boolean left) {
        docked = true;
        dockLeft = left;
        mode = MINI;
        lp.x = left ? 0 : Math.max(0, screenW() - Ui.dp(this, 22));
        lp.y = Math.max(0, lp.y);
        sp.edit().putBoolean("float_docked", true).putBoolean("float_dock_left", left)
                .putInt("float_x", lp.x).putInt("float_y", lp.y).apply();
        render(false);
        updateWindow();
    }

    private void undock() {
        docked = false;
        mode = MINI;
        scale = scaleFor(MINI);
        lp.x = dockLeft ? Ui.dp(this, 12) : Math.max(0, screenW() - d(68) - Ui.dp(this, 12));
        sp.edit().putBoolean("float_docked", false).putInt("float_x", lp.x).apply();
        render(true);
        updateWindow();
    }

    private LinearLayout panelShell(int widthDp) {
        LinearLayout p = Ui.col(this);
        panelBg = Ui.box(this, Ui.SURF, Ui.LINE, 26);
        p.setBackground(panelBg);
        p.setPadding(d(12), d(4), d(12), d(12));
        p.setLayoutParams(new LinearLayout.LayoutParams(d(widthDp), WRAP));
        return p;
    }

    /** Grab bar in the middle, shrink-to-icon on the left, grow or shrink on the right. */
    private View topBar(boolean full) {
        FrameLayout bar = new FrameLayout(this);
        View grab = new View(this);
        grab.setBackground(Ui.box(this, Ui.LINE, 0, 3));
        bar.addView(grab, new FrameLayout.LayoutParams(d(56), d(6), Gravity.CENTER));
        bar.addView(iconButton(IconDrawable.MINUS, () -> setMode(MINI)),
                new FrameLayout.LayoutParams(d(44), d(44), Gravity.START | Gravity.CENTER_VERTICAL));
        bar.addView(iconButton(full ? IconDrawable.CHEVRON_DOWN : IconDrawable.EXPAND,
                        () -> setMode(full ? FAVS : FULL)),
                new FrameLayout.LayoutParams(d(44), d(44), Gravity.END | Gravity.CENTER_VERTICAL));
        bar.setOnTouchListener(new Drag(null));
        return bar;
    }

    private ImageView iconButton(int kind, final Runnable onClick) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(new IconDrawable(kind, Ui.DIM));
        int p = d(11);
        b.setPadding(p, p, p, p);
        b.setClickable(true);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private View minimalPanel() {
        LinearLayout p = panelShell(380);
        p.addView(topBar(false), Ui.lp(MATCH, d(44), 0));
        gridHost = Ui.col(this);
        p.addView(gridHost, Ui.lp(MATCH, WRAP, 0));
        LinearLayout bottom = Ui.row(this);
        bottom.setPadding(0, d(8), 0, 0);
        bottom.addView(targetSwitch(), Ui.lp(WRAP, WRAP, 0));
        bottom.addView(new View(this), Ui.lp(0, 1, 1f));
        View m = micButton();
        if (m != null) {
            bottom.addView(m, Ui.lp(d(54), d(54), 0));
        }
        p.addView(bottom, Ui.lp(MATCH, WRAP, 0));
        refreshGrid();
        refreshTarget();
        return p;
    }

    private View fullPanel() {
        LinearLayout p = panelShell(540);
        p.addView(topBar(true), Ui.lp(MATCH, d(44), 0));

        tabNames.clear();
        tabLabels.clear();
        tabLines.clear();
        tabNames.add(TAB_FAVS);
        List<File> all = Library.userSounds(this);
        for (String g : Library.allGroups(this)) {
            if ("Whoopee".equals(g) && !whoopeeOn()) {
                continue;
            }
            for (File f : all) {
                if (g.equals(Library.groupOf(this, f))) {
                    tabNames.add(g);
                    break;
                }
            }
        }
        if (!tabNames.contains(tab)) {
            tab = tabNames.size() > 1 && favourites().isEmpty() ? tabNames.get(1) : TAB_FAVS;
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = Ui.row(this);
        for (final String name : tabNames) {
            LinearLayout one = Ui.col(this);
            one.setPadding(d(10), d(4), d(10), 0);
            TextView label = tx(name, 16f, Ui.DIM, true);
            label.setSingleLine(true);
            label.setPadding(0, 0, 0, d(8));
            View line = new View(this);
            one.addView(label);
            one.addView(line, Ui.lp(MATCH, d(3), 0));
            one.setClickable(true);
            one.setOnClickListener(v -> selectTab(name));
            tabLabels.add(label);
            tabLines.add(line);
            strip.addView(one);
        }
        hs.addView(strip);
        p.addView(hs, Ui.lp(MATCH, WRAP, 0));
        View rule = new View(this);
        rule.setBackgroundColor(Ui.LINE);
        p.addView(rule, Ui.lp(MATCH, Math.max(1, d(1)), 0));

        gridHost = Ui.col(this);
        gridHost.setPadding(0, d(8), 0, 0);
        p.addView(gridHost, Ui.lp(MATCH, WRAP, 0));
        bottomHost = Ui.col(this);
        bottomHost.setPadding(0, d(8), 0, 0);
        p.addView(bottomHost, Ui.lp(MATCH, WRAP, 0));

        refreshTabs();
        refreshGrid();
        refreshBottom();
        refreshTarget();
        return p;
    }

    // ------------------------------------------------------------------- tabs

    private void selectTab(String name) {
        if (name.equals(tab)) {
            return;
        }
        tab = name;
        refreshTabs();
        refreshGrid();
        refreshBottom();
    }

    private void stepTab(int by) {
        int i = tabNames.indexOf(tab) + by;
        if (i >= 0 && i < tabNames.size()) {
            selectTab(tabNames.get(i));
        }
    }

    private void refreshTabs() {
        for (int i = 0; i < tabNames.size() && i < tabLabels.size(); i++) {
            String name = tabNames.get(i);
            boolean on = name.equals(tab);
            int color = TAB_FAVS.equals(name) ? GOLD : Library.colorOf(this, name);
            tabLabels.get(i).setTextColor(on ? Ui.TXT : Ui.DIM);
            tabLines.get(i).setBackground(on ? Ui.box(this, color, 0, 2) : null);
        }
    }

    // ------------------------------------------------------------------- pads

    private List<File> favourites() {
        List<File> out = new ArrayList<>();
        for (File f : Library.userSounds(this)) {
            boolean w = "Whoopee".equals(Library.groupOf(this, f));
            if (Library.isFav(this, f) && (!w || whoopeeOn())) {
                out.add(f);
            }
        }
        return out;
    }

    private void refreshGrid() {
        if (gridHost == null) {
            return;
        }
        gridHost.removeAllViews();
        pads.clear();
        boolean full = mode == FULL;
        List<File> files;
        if (!full || TAB_FAVS.equals(tab)) {
            files = favourites();
            if (!full && files.size() > 6) {
                files = files.subList(0, 6);
            }
        } else {
            files = new ArrayList<>();
            for (File f : Library.userSounds(this)) {
                if (tab.equals(Library.groupOf(this, f))) {
                    files.add(f);
                }
            }
        }
        if (files.isEmpty()) {
            TextView none = tx(full ? "No favourites yet. Long-press a sound on another tab to add it here."
                    : "No favourites yet. Open the full board and long-press a sound.", 14f, Ui.DIM, false);
            none.setPadding(d(8), d(14), d(8), d(14));
            gridHost.addView(none);
            return;
        }
        int cols = full ? 4 : 3;
        LinearLayout grid = Ui.col(this);
        LinearLayout row = null;
        for (int n = 0; n < files.size(); n++) {
            if (n % cols == 0) {
                row = new LinearLayout(this);
                grid.addView(row, Ui.lp(MATCH, WRAP, 0));
            }
            Pad pad = new Pad(files.get(n));
            pads.add(pad);
            LinearLayout.LayoutParams pl = Ui.lp(0, d(70), 1f);
            int m = d(4);
            pl.setMargins(m, m, m, m);
            row.addView(pad, pl);
        }
        if (row != null) {
            for (int i = files.size() % cols; i != 0 && i < cols; i++) {
                LinearLayout.LayoutParams pl = Ui.lp(0, d(70), 1f);
                int m = d(4);
                pl.setMargins(m, m, m, m);
                row.addView(new View(this), pl);
            }
        }
        int rows = (files.size() + cols - 1) / cols;
        if (full && rows > 3) {
            ScrollView sv = new ScrollView(this);
            sv.addView(grid);
            gridHost.addView(sv, Ui.lp(MATCH, d(78) * 3, 0));
        } else {
            gridHost.addView(grid, Ui.lp(MATCH, WRAP, 0));
        }
        markPlaying();
    }

    /** One sound. Tap to play, tap again to stop, long-press to toggle favourite. */
    private final class Pad extends FrameLayout {
        final File file;
        final int color;
        private final GradientDrawable bg;
        private final View bar;
        private final View stopMark;
        private ValueAnimator anim;

        Pad(File f) {
            super(FloatService.this);
            file = f;
            color = Library.colorOf(FloatService.this, Library.groupOf(FloatService.this, f));
            bg = Ui.box(FloatService.this, blend(color, Ui.SURF, 0.24f), 0, 16);
            setBackground(bg);

            TextView label = tx(Library.label(f), 15f, Ui.TXT, true);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setPadding(d(8), 0, d(8), 0);
            addView(label, new FrameLayout.LayoutParams(MATCH, MATCH));

            bar = new View(FloatService.this);
            bar.setBackground(Ui.box(FloatService.this, color, 0, 3));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(MATCH, d(5), Gravity.BOTTOM);
            bl.setMargins(d(10), 0, d(10), d(7));
            bar.setPivotX(0f);
            bar.setVisibility(INVISIBLE);
            addView(bar, bl);

            ImageView stop = new ImageView(FloatService.this);
            stop.setImageDrawable(new IconDrawable(IconDrawable.STOP, color));
            FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(d(16), d(16), Gravity.TOP | Gravity.END);
            sl.setMargins(0, d(6), d(6), 0);
            stop.setVisibility(INVISIBLE);
            addView(stop, sl);
            stopMark = stop;

            if (Library.isFav(FloatService.this, f) && !(mode == FAVS || TAB_FAVS.equals(tab))) {
                View dot = new View(FloatService.this);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(GOLD);
                dot.setBackground(g);
                FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(d(8), d(8), Gravity.TOP | Gravity.START);
                dl.setMargins(d(8), d(8), 0, 0);
                addView(dot, dl);
            }

            setClickable(true);
            setOnTouchListener((v, e) -> {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).start();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                }
                return false;
            });
            setOnClickListener(v -> toggle(file));
            setOnLongClickListener(v -> {
                Library.setFav(FloatService.this, file, !Library.isFav(FloatService.this, file));
                refreshGrid();
                return true;
            });
        }

        void setPlaying(boolean on, long durationMs) {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            bg.setStroke(on ? d(2) : 0, color);
            stopMark.setVisibility(on ? VISIBLE : INVISIBLE);
            bar.setVisibility(on ? VISIBLE : INVISIBLE);
            bar.setAlpha(1f);
            bar.setScaleX(1f);
            if (!on) {
                return;
            }
            if (durationMs > 0) {
                bar.setScaleX(0f);
                ObjectAnimator a = ObjectAnimator.ofFloat(bar, "scaleX", 0f, 1f);
                a.setDuration(durationMs);
                a.setInterpolator(new LinearInterpolator());
                anim = a;
            } else {
                ObjectAnimator a = ObjectAnimator.ofFloat(bar, "alpha", 1f, 0.25f);
                a.setDuration(600);
                a.setRepeatCount(ValueAnimator.INFINITE);
                a.setRepeatMode(ValueAnimator.REVERSE);
                anim = a;
            }
            anim.start();
        }
    }

    private void markPlaying() {
        for (Pad p : pads) {
            boolean on = playing != null && p.file.equals(playing);
            long left = on ? Math.max(0, playingMs) : 0;
            p.setPlaying(on, left);
        }
    }

    private long durationMs(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String v = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return v == null ? 0 : Long.parseLong(v);
        } catch (Throwable t) {
            return 0;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void toggle(File f) {
        if (playing != null && playing.equals(f)) {
            playToken++;
            playing = null;
            Player.stop();
            markPlaying();
            return;
        }
        boolean outside = Prefs.outer(this);
        // Whoopee sounds use the seat picker in the cabin, or the outside speaker like any other.
        boolean whoopee = "Whoopee".equals(Library.groupOf(this, f)) && !outside;
        AudioDeviceInfo dev;
        if (whoopee) {
            dev = audio.mediaBus();
            if (dev == null) {
                dev = audio.inner();
            }
        } else if (outside) {
            dev = audio.outer();
            if (dev == null) {
                return;
            }
        } else {
            dev = audio.notifyBus();
            if (dev == null) {
                dev = audio.inner();
            }
        }
        mic.stop();
        final int token = ++playToken;
        playing = f;
        playingMs = durationMs(f);
        markPlaying();
        Player.Cb cb = (ok, msg) -> h.post(() -> {
            if (token == playToken) {
                playing = null;
                markPlaying();
            }
        });
        int tuned = whoopee && seat != 0 ? sp.getInt("seat_bits_" + seat, 0) : 0;
        if (tuned != 0) {
            Player.playOnChannels(this, null, f, dev, AudioAttributes.USAGE_MEDIA, tuned, cb);
        } else if (whoopee && seat != 0) {
            int channel = seat == 1 ? Player.FRONT_RIGHT : seat == 2 ? Player.FRONT_LEFT
                    : seat == 3 ? Player.BACK_LEFT : Player.BACK_RIGHT;
            Player.playAtSeat(this, null, f, dev, AudioAttributes.USAGE_MEDIA, channel, cb);
        } else if (whoopee) {
            Player.play(this, null, f, dev, AudioAttributes.USAGE_MEDIA, true, 0f, cb);
        } else {
            Player.play(this, null, f, dev,
                    outside ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_NOTIFICATION, cb);
        }
    }

    // --------------------------------------------------------------- controls

    /** Cabin / Outside as a single sliding switch. */
    private View targetSwitch() {
        LinearLayout seg = Ui.row(this);
        seg.setBackground(Ui.box(this, Ui.BG, 0, 24));
        int p = d(4);
        seg.setPadding(p, p, p, p);
        segCabin = segment("Cabin", () -> setOutside(false));
        segOutside = segment("Outside", () -> setOutside(true));
        seg.addView(segCabin);
        seg.addView(segOutside);
        return seg;
    }

    private TextView segment(String label, final Runnable onClick) {
        TextView t = tx(label, 14f, Ui.DIM, true);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(d(16), d(10), d(16), d(10));
        t.setClickable(true);
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    private void setOutside(boolean outside) {
        Prefs.setChan(this, outside ? 3 : 0);
        if (mode == FULL && "Whoopee".equals(tab)) {
            refreshBottom();
        }
        refreshTarget();
    }

    private void refreshTarget() {
        boolean out = Prefs.outer(this);
        if (segCabin != null && segOutside != null) {
            segCabin.setBackground(out ? null : Ui.box(this, Ui.CAB, 0, 20));
            segCabin.setTextColor(out ? Ui.DIM : Ui.BG);
            segOutside.setBackground(out ? Ui.box(this, Ui.OUT, 0, 20) : null);
            segOutside.setTextColor(out ? Ui.BG : Ui.DIM);
        }
        if (panelBg != null) {
            panelBg.setStroke(Math.max(1, d(2)), out ? blend(Ui.OUT, Ui.SURF, 0.6f) : Ui.LINE);
        }
    }

    /** The only round control. Null when push to talk is switched off in the app. */
    private View micButton() {
        if (!sp.getBoolean("float_ptt", false)) {
            return null;
        }
        final boolean allowed = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        final ImageView b = new ImageView(this);
        final GradientDrawable idle = new GradientDrawable();
        idle.setShape(GradientDrawable.OVAL);
        idle.setColor(Ui.WARN_BG);
        idle.setStroke(d(2), allowed ? Ui.OUT : Ui.LINE);
        final GradientDrawable live = new GradientDrawable();
        live.setShape(GradientDrawable.OVAL);
        live.setColor(Ui.OUT);
        b.setBackground(idle);
        b.setImageDrawable(new IconDrawable(IconDrawable.MIC, allowed ? Ui.OUT : Ui.DIM));
        int p = d(13);
        b.setPadding(p, p, p, p);
        b.setClickable(true);
        b.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN && allowed) {
                AudioDeviceInfo out = audio.outer();
                if (out != null) {
                    playToken++;
                    playing = null;
                    Player.stop();
                    markPlaying();
                    mic.gain = Player.gainPercent() / 100.0 * 2.5;
                    mic.start(out);
                    b.setBackground(live);
                    b.setImageDrawable(new IconDrawable(IconDrawable.MIC, Ui.BG));
                    b.animate().scaleX(1.12f).scaleY(1.12f).setDuration(90).start();
                }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                mic.stop();
                b.setBackground(idle);
                b.setImageDrawable(new IconDrawable(IconDrawable.MIC, allowed ? Ui.OUT : Ui.DIM));
                b.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            }
            return true;
        });
        return b;
    }

    /**
     * Bottom of the full board: switch, volume and mic. On the Whoopee tab, with Cabin
     * selected, a seat picker is added underneath.
     */
    private void refreshBottom() {
        if (bottomHost == null) {
            return;
        }
        bottomHost.removeAllViews();
        seatViews.clear();
        LinearLayout row = Ui.row(this);
        row.addView(targetSwitch(), Ui.lp(WRAP, WRAP, 0));
        ImageView spk = new ImageView(this);
        spk.setImageDrawable(new IconDrawable(IconDrawable.SPEAKER, Ui.DIM));
        LinearLayout.LayoutParams sl = Ui.lp(d(24), d(24), 0);
        sl.leftMargin = d(12);
        row.addView(spk, sl);
        LinearLayout.LayoutParams vl = Ui.lp(0, d(40), 1f);
        vl.leftMargin = d(6);
        vl.rightMargin = d(8);
        row.addView(new VolumeBar(), vl);
        View m = micButton();
        if (m != null) {
            row.addView(m, Ui.lp(d(54), d(54), 0));
        }
        bottomHost.addView(row, Ui.lp(MATCH, WRAP, 0));
        if ("Whoopee".equals(tab) && !Prefs.outer(this)) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout seg = Ui.row(this);
            seg.setBackground(Ui.box(this, Ui.BG, 0, 24));
            int p = d(4);
            seg.setPadding(p, p, p, p);
            for (int i = 0; i < SEAT_NAMES.length; i++) {
                final int idx = i;
                TextView t = segment(SEAT_NAMES[i], () -> {
                    seat = idx;
                    refreshSeats();
                });
                seatViews.add(t);
                seg.addView(t);
            }
            hs.addView(seg);
            LinearLayout.LayoutParams hl = Ui.lp(MATCH, WRAP, 0);
            hl.topMargin = d(8);
            bottomHost.addView(hs, hl);
            refreshSeats();
        }
        refreshTarget();
    }

    /**
     * Volume slider drawn by the widget itself, so the track and thumb scale with the
     * widget. The system slider kept a full-size thumb and lost its track when small.
     */
    private final class VolumeBar extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private int value = Player.gainPercent();

        VolumeBar() {
            super(FloatService.this);
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth();
            float hgt = getHeight();
            float r = hgt * 0.30f;
            float t = hgt * 0.20f;
            float x0 = r;
            float x1 = Math.max(x0 + 1, w - r);
            float cy = hgt / 2f;
            float x = x0 + (x1 - x0) * value / 100f;
            paint.setColor(Ui.LINE);
            rect.set(x0, cy - t / 2, x1, cy + t / 2);
            c.drawRoundRect(rect, t / 2, t / 2, paint);
            paint.setColor(Ui.TXT);
            rect.set(x0, cy - t / 2, x, cy + t / 2);
            c.drawRoundRect(rect, t / 2, t / 2, paint);
            c.drawCircle(x, cy, r, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                float r = getHeight() * 0.30f;
                float span = Math.max(1f, getWidth() - 2 * r);
                value = Math.max(0, Math.min(100, Math.round((e.getX() - r) * 100f / span)));
                Player.setGainPercent(value);
                mic.gain = value / 100.0 * 2.5;
                invalidate();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                sp.edit().putInt("vol_pct", value).apply();
            }
            return true;
        }
    }

    private void refreshSeats() {
        for (int i = 0; i < seatViews.size(); i++) {
            boolean on = i == seat;
            seatViews.get(i).setBackground(on ? Ui.box(this, Ui.CAB, 0, 20) : null);
            seatViews.get(i).setTextColor(on ? Ui.BG : Ui.DIM);
        }
    }

    // ---------------------------------------------------------------- touches

    /** Window root: watches for a two-finger pinch (resize) and a sideways flick (change tab). */
    private final class Shell extends LinearLayout {
        private final ScaleGestureDetector pinch;
        private final GestureDetector flick;
        private float pending = 1f;

        Shell() {
            super(FloatService.this);
            pinch = new ScaleGestureDetector(FloatService.this,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector g) {
                            pending *= g.getScaleFactor();
                            if (Math.abs(pending - 1f) > 0.08f) {
                                setScale(scale * pending);
                                pending = 1f;
                            }
                            return true;
                        }
                    });
            flick = new GestureDetector(FloatService.this, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    if (mode != FULL || gridHost == null || a == null
                            || Math.abs(vx) < Ui.dp(FloatService.this, 700) || Math.abs(vx) < Math.abs(vy) * 1.6f) {
                        return false;
                    }
                    int[] at = new int[2];
                    gridHost.getLocationOnScreen(at);
                    float y = a.getRawY();
                    if (y < at[1] || y > at[1] + gridHost.getHeight()) {
                        return false;
                    }
                    stepTab(vx < 0 ? 1 : -1);
                    return false;
                }
            });
        }

        /**
         * The widget has no use for window insets, and dispatching them to its children
         * crashed the app on the head unit when the window reached a screen edge.
         */
        @Override
        public android.view.WindowInsets dispatchApplyWindowInsets(android.view.WindowInsets insets) {
            return insets;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            try {
                pinch.onTouchEvent(e);
                flick.onTouchEvent(e);
            } catch (Throwable ignored) {
            }
            return super.dispatchTouchEvent(e);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            return e.getPointerCount() >= 2;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return true;
        }
    }

    /**
     * Drags the whole window, keeping it on screen. A touch that barely moves counts as a
     * tap. Letting go with the finger at the left or right edge parks the widget as a tab.
     */
    private final class Drag implements View.OnTouchListener {
        private final Runnable onTap;
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        Drag(Runnable onTap) {
            this.onTap = onTap == null ? null : () -> h.post(onTap);
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            try {
                int slop = Ui.dp(FloatService.this, 8);
                int w = Math.max(1, shell.getWidth());
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                            moved = true;
                        }
                        if (moved) {
                            lp.x = Math.max(0, Math.min(startX + (int) dx, Math.max(0, screenW() - w)));
                            lp.y = Math.max(0, startY + (int) dy);
                            updateWindow();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            int edge = Ui.dp(FloatService.this, 28);
                            float x = e.getRawX();
                            // Posted, so the views are not swapped while this touch is still
                            // being delivered to them.
                            if (x <= edge) {
                                h.post(() -> dock(true));
                            } else if (x >= screenW() - edge) {
                                h.post(() -> dock(false));
                            } else {
                                sp.edit().putInt("float_x", lp.x).putInt("float_y", lp.y).apply();
                            }
                        } else if (onTap != null && e.getActionMasked() == MotionEvent.ACTION_UP) {
                            onTap.run();
                        }
                        return true;
                    default:
                        return false;
                }
            } catch (Throwable t) {
                return true;
            }
        }
    }
}
