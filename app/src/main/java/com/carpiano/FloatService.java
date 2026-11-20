package com.carpiano;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
 * Floating soundboard drawn over other apps, in three sizes: a small round
 * button, a compact panel of favourite sounds, and the full board with every
 * group. Dragging it off the left or right edge parks it as a thin tab.
 */
public class FloatService extends Service {

    private static final String CH = "float";
    private static final int NOTE_ID = 6101;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    static volatile boolean running;
    static volatile String lastError = "";

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout shell;
    private SharedPreferences sp;
    private AudioOut audio;
    private static final int MINI = 0, FAVS = 1, FULL = 2;
    private int mode = MINI;
    private boolean docked;
    private float scale = 1.25f;
    private final LiveMic mic = new LiveMic();
    private boolean dockLeft;
    private String group;

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
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            lp = new WindowManager.LayoutParams(WRAP, WRAP,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = sp.getInt("float_x", d(40));
            lp.y = sp.getInt("float_y", d(160));
            scale = Math.max(0.8f, Math.min(2.2f, sp.getFloat("float_scale", 1.25f)));
            Player.setGainPercent(sp.getInt("vol_pct", 60));
            docked = sp.getBoolean("float_docked", false);
            dockLeft = sp.getBoolean("float_dock_left", true);
            shell = new Shell();
            render();
            wm.addView(shell, lp);
            running = true;
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
        mic.stop();
        try {
            if (wm != null && shell != null) {
                wm.removeView(shell);
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ views

    /** dp to pixels, multiplied by the widget's own size setting. */
    private int d(int dp) {
        return Ui.dp(this, Math.round(dp * scale));
    }

    private TextView tx(String s, float sp, int color, boolean bold) {
        return Ui.text(this, s, sp * scale, color, bold);
    }

    private void setScale(float v) {
        scale = Math.max(0.8f, Math.min(2.2f, v));
        sp.edit().putFloat("float_scale", scale).apply();
        render();
    }

    /** Window root that watches for a two-finger pinch anywhere on the widget. */
    private final class Shell extends LinearLayout {
        private final ScaleGestureDetector detector;
        private float pending = 1f;

        Shell() {
            super(FloatService.this);
            detector = new ScaleGestureDetector(FloatService.this,
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
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            detector.onTouchEvent(e);
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

    /** Hold-to-talk row, shown only when the user has switched it on in Setup. */
    private void addTalk(LinearLayout parent) {
        if (!sp.getBoolean("float_ptt", false)) {
            return;
        }
        final boolean allowed = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        final TextView b = tx(allowed ? "Hold to talk (outside)" : "Open CarTalk, Live mic, to allow the microphone",
                15f, Ui.TXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.box(this, Ui.WARN_BG, Ui.OUT, 14));
        b.setClickable(true);
        b.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN && allowed) {
                AudioDeviceInfo out = audio.outer();
                if (out != null) {
                    Player.stop();
                    mic.gain = Player.gainPercent() / 100.0 * 2.5;
                    mic.start(out);
                    b.setBackground(Ui.box(this, Ui.OUT, 0, 14));
                    b.setTextColor(Ui.BG);
                    b.setText("Talking");
                }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                mic.stop();
                b.setBackground(Ui.box(this, Ui.WARN_BG, Ui.OUT, 14));
                b.setTextColor(Ui.TXT);
                b.setText(allowed ? "Hold to talk (outside)" : "Open CarTalk, Live mic, to allow the microphone");
            }
            return true;
        });
        parent.addView(b, Ui.lp(this, MATCH, d(52), 0, 3));
    }

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private void render() {
        shell.removeAllViews();
        if (docked) {
            shell.addView(tab());
        } else if (mode == FULL) {
            shell.addView(panel());
        } else if (mode == FAVS) {
            shell.addView(favourites());
        } else {
            shell.addView(bubble());
        }
    }

    private void setMode(int m) {
        mode = m;
        render();
    }

    private View bubble() {
        ImageView b = new ImageView(this);
        b.setImageResource(R.mipmap.ic_launcher_round);
        int s = d(68);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        b.setOnTouchListener(new Drag(() -> setMode(FAVS)));
        return b;
    }

    /** Thin tab left at the screen edge after the widget is dragged off it. */
    private View tab() {
        View t = new View(this);
        t.setBackground(Ui.box(this, Ui.OUT, 0, 10));
        t.setLayoutParams(new LinearLayout.LayoutParams(d(22), d(110)));
        t.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                undock();
            }
            return true;
        });
        return t;
    }

    private void dock(boolean left) {
        docked = true;
        dockLeft = left;
        mode = MINI;
        lp.x = left ? 0 : screenW() - d(22);
        lp.y = Math.max(0, lp.y);
        sp.edit().putBoolean("float_docked", true).putBoolean("float_dock_left", left)
                .putInt("float_x", lp.x).putInt("float_y", lp.y).apply();
        render();
        try {
            wm.updateViewLayout(shell, lp);
        } catch (Throwable ignored) {
        }
    }

    private void undock() {
        docked = false;
        mode = MINI;
        lp.x = dockLeft ? d(12) : screenW() - d(80);
        sp.edit().putBoolean("float_docked", false).putInt("float_x", lp.x).apply();
        render();
        try {
            wm.updateViewLayout(shell, lp);
        } catch (Throwable ignored) {
        }
    }

    private LinearLayout header(String titleText, Runnable onTitleTap) {
        LinearLayout head = Ui.row(this);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher_round);
        icon.setOnTouchListener(new Drag(onTitleTap));
        head.addView(icon, Ui.lp(d(44), d(44), 0));
        TextView handle = tx(titleText, 16f, Ui.TXT, true);
        handle.setPadding(d(8), d(10), d(4), d(10));
        handle.setOnTouchListener(new Drag(onTitleTap));
        head.addView(handle, Ui.lp(0, WRAP, 1f));
        final boolean outside = Prefs.outer(this);
        head.addView(small(outside ? "Outside" : "Cabin", outside ? Ui.OUT : Ui.CAB, Ui.BG, () -> {
            Prefs.setChan(this, outside ? 0 : 3);
            render();
        }), Ui.lp(this, WRAP, WRAP, 0, 3));
        head.addView(small("Stop", Ui.TXT, Ui.BG, Player::stop), Ui.lp(this, WRAP, WRAP, 0, 3));
        return head;
    }

    private TextView soundTile(final File f, boolean highlight) {
        TextView t = tx(Library.label(f), 15f, highlight ? Ui.OUT : Ui.TXT, true);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setBackground(Ui.box(this, Ui.RAISE, 0, 14));
        t.setPadding(d(6), 0, d(6), 0);
        t.setClickable(true);
        t.setOnClickListener(v -> play(f));
        return t;
    }

    private void addGrid(LinearLayout parent, List<TextView> tiles, int perRow, int maxRows) {
        ScrollView sv = new ScrollView(this);
        LinearLayout grid = Ui.col(this);
        LinearLayout row = null;
        for (int n = 0; n < tiles.size(); n++) {
            if (n % perRow == 0) {
                row = new LinearLayout(this);
                grid.addView(row, Ui.lp(MATCH, WRAP, 0));
            }
            row.addView(tiles.get(n), Ui.lp(this, 0, d(64), 1f, 3));
        }
        if (row != null) {
            for (int i = tiles.size() % perRow; i != 0 && i < perRow; i++) {
                row.addView(new View(this), Ui.lp(this, 0, d(64), 1f, 3));
            }
        }
        sv.addView(grid);
        int rows = (tiles.size() + perRow - 1) / perRow;
        parent.addView(sv, Ui.lp(MATCH, d(Math.min(rows, maxRows) * 70), 0));
    }

    /** Middle size: favourite sounds only. Tapping the icon or title opens the full board. */
    private View favourites() {
        LinearLayout p = Ui.col(this);
        p.setBackground(Ui.box(this, Ui.SURF, Ui.LINE, 20));
        int pad = d(10);
        p.setPadding(pad, pad, pad, pad);
        p.setLayoutParams(new LinearLayout.LayoutParams(d(400), WRAP));
        LinearLayout head = header("Favourites", () -> setMode(FULL));
        head.addView(small("All", Ui.RAISE, Ui.TXT, () -> setMode(FULL)), Ui.lp(this, WRAP, WRAP, 0, 3));
        head.addView(small("Hide", Ui.RAISE, Ui.TXT, () -> setMode(MINI)), Ui.lp(this, WRAP, WRAP, 0, 3));
        p.addView(head, Ui.lp(MATCH, WRAP, 0));
        addTalk(p);
        List<TextView> tiles = new ArrayList<>();
        for (File f : Library.userSounds(this)) {
            if (Library.isFav(this, f) && !"Whoopee".equals(Library.groupOf(this, f))) {
                tiles.add(soundTile(f, false));
            }
        }
        if (tiles.isEmpty()) {
            TextView none = tx("No favourites yet. Tap All, then long-press a sound to make it a "
                    + "favourite.", 15f, Ui.DIM, false);
            none.setPadding(d(8), d(12), d(8), d(12));
            p.addView(none);
        } else {
            addGrid(p, tiles, 3, 2);
        }
        return p;
    }

    private View panel() {
        LinearLayout p = Ui.col(this);
        p.setBackground(Ui.box(this, Ui.SURF, Ui.LINE, 20));
        int pad = d(10);
        p.setPadding(pad, pad, pad, pad);
        p.setLayoutParams(new LinearLayout.LayoutParams(d(460), WRAP));

        LinearLayout head = header("CarTalk", () -> setMode(FAVS));
        head.addView(small("Favs", Ui.RAISE, Ui.TXT, () -> setMode(FAVS)), Ui.lp(this, WRAP, WRAP, 0, 3));
        head.addView(small("Hide", Ui.RAISE, Ui.TXT, () -> setMode(MINI)), Ui.lp(this, WRAP, WRAP, 0, 3));
        p.addView(head, Ui.lp(MATCH, WRAP, 0));

        LinearLayout tools = Ui.row(this);
        final TextView volText = tx("Vol " + Player.gainPercent() + "%", 13f, Ui.DIM, true);
        tools.addView(volText, Ui.lp(this, d(70), WRAP, 0, 3));
        SeekBar vol = new SeekBar(this);
        vol.setMax(100);
        vol.setProgress(Player.gainPercent());
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                Player.setGainPercent(v);
                mic.gain = v / 100.0 * 2.5;
                volText.setText("Vol " + v + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                sp.edit().putInt("vol_pct", s.getProgress()).apply();
            }
        });
        tools.addView(vol, Ui.lp(0, d(44), 1f));
        tools.addView(small("Smaller", Ui.RAISE, Ui.TXT, () -> setScale(scale - 0.15f)), Ui.lp(this, WRAP, WRAP, 0, 3));
        tools.addView(small("Bigger", Ui.RAISE, Ui.TXT, () -> setScale(scale + 0.15f)), Ui.lp(this, WRAP, WRAP, 0, 3));
        p.addView(tools, Ui.lp(MATCH, WRAP, 0));
        addTalk(p);

        final List<String> groups = new ArrayList<>();
        final List<File> all = Library.userSounds(this);
        for (String g : Library.allGroups(this)) {
            if ("Whoopee".equals(g)) {
                continue;
            }
            for (File f : all) {
                if (g.equals(Library.groupOf(this, f))) {
                    groups.add(g);
                    break;
                }
            }
        }
        if (groups.isEmpty()) {
            TextView none = tx("No sounds yet. Open CarTalk and tap Add sounds.", 15f, Ui.DIM, false);
            none.setPadding(d(8), d(12), d(8), d(12));
            p.addView(none);
            return p;
        }
        if (group == null || !groups.contains(group)) {
            group = groups.get(0);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(this);
        for (final String g : groups) {
            boolean on = g.equals(group);
            chips.addView(small(g, on ? Ui.TXT : Ui.BG, on ? Ui.BG : Ui.DIM, () -> {
                group = g;
                render();
            }), Ui.lp(this, WRAP, WRAP, 0, 3));
        }
        hs.addView(chips);
        p.addView(hs, Ui.lp(MATCH, WRAP, 0));

        List<TextView> tiles = new ArrayList<>();
        for (final File s : all) {
            if (!group.equals(Library.groupOf(this, s))) {
                continue;
            }
            final boolean fav = Library.isFav(this, s);
            TextView t = soundTile(s, fav);
            t.setOnLongClickListener(v -> {
                Library.setFav(this, s, !fav);
                render();
                return true;
            });
            tiles.add(t);
        }
        addGrid(p, tiles, 3, 3);
        TextView hint = tx("Long-press a sound to add or remove it from favourites (shown in orange).",
                12f, Ui.DIM, false);
        hint.setPadding(d(8), d(4), d(8), 0);
        p.addView(hint);
        return p;
    }

    private TextView small(String label, int fill, int textColor, Runnable onClick) {
        TextView b = Ui.button(this, label, fill, textColor, onClick);
        b.setTextSize(14f * scale);
        b.setMinHeight(d(44));
        b.setPadding(d(14), d(6), d(14), d(6));
        return b;
    }

    private void play(File f) {
        boolean outside = Prefs.outer(this);
        AudioDeviceInfo dev;
        if (outside) {
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
        Player.play(this, null, f, dev,
                outside ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_NOTIFICATION,
                (ok, msg) -> {
                });
    }

    /** Drags the whole window; a touch that barely moves counts as a tap. */
    private final class Drag implements View.OnTouchListener {
        private final Runnable onTap;
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        Drag(Runnable onTap) {
            this.onTap = onTap;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
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
                    if (Math.abs(dx) > Ui.dp(FloatService.this, 8) || Math.abs(dy) > Ui.dp(FloatService.this, 8)) {
                        moved = true;
                    }
                    if (moved) {
                        lp.x = startX + (int) dx;
                        lp.y = Math.max(0, startY + (int) dy);
                        try {
                            wm.updateViewLayout(shell, lp);
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved) {
                        int w = Math.max(1, shell.getWidth());
                        if (lp.x < -w / 4) {
                            dock(true);
                        } else if (lp.x + w > screenW() + w / 4) {
                            dock(false);
                        } else {
                            lp.x = Math.max(0, Math.min(lp.x, Math.max(0, screenW() - w)));
                            try {
                                wm.updateViewLayout(shell, lp);
                            } catch (Throwable ignored) {
                            }
                            sp.edit().putInt("float_x", lp.x).putInt("float_y", lp.y).apply();
                        }
                    } else if (onTap != null && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        onTap.run();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
}
