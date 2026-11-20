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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Floating soundboard drawn over other apps. Collapsed it is a small round
 * button; tapped, it opens a panel with the user's groups and sounds. Both
 * can be dragged, and the position is remembered.
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
    private boolean expanded;
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
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = sp.getInt("float_x", Ui.dp(this, 40));
            lp.y = sp.getInt("float_y", Ui.dp(this, 160));
            shell = new LinearLayout(this);
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
        try {
            if (wm != null && shell != null) {
                wm.removeView(shell);
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ views

    private void render() {
        shell.removeAllViews();
        shell.addView(expanded ? panel() : bubble());
    }

    private View bubble() {
        ImageView b = new ImageView(this);
        b.setImageResource(R.mipmap.ic_launcher_round);
        int s = Ui.dp(this, 68);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        b.setOnTouchListener(new Drag(() -> {
            expanded = true;
            render();
        }));
        return b;
    }

    private View panel() {
        LinearLayout p = Ui.col(this);
        p.setBackground(Ui.box(this, Ui.SURF, Ui.LINE, 20));
        int pad = Ui.dp(this, 10);
        p.setPadding(pad, pad, pad, pad);
        p.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 460), WRAP));

        LinearLayout head = Ui.row(this);
        TextView handle = Ui.text(this, "CarTalk", 17f, Ui.TXT, true);
        handle.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));
        handle.setOnTouchListener(new Drag(null));
        head.addView(handle, Ui.lp(0, WRAP, 1f));
        final boolean outside = Prefs.outer(this);
        head.addView(small(outside ? "Outside" : "Cabin", outside ? Ui.OUT : Ui.CAB, Ui.BG, () -> {
            Prefs.setChan(this, outside ? 0 : 3);
            render();
        }), Ui.lp(this, WRAP, WRAP, 0, 3));
        head.addView(small("Stop", Ui.TXT, Ui.BG, Player::stop), Ui.lp(this, WRAP, WRAP, 0, 3));
        head.addView(small("Hide", Ui.RAISE, Ui.TXT, () -> {
            expanded = false;
            render();
        }), Ui.lp(this, WRAP, WRAP, 0, 3));
        p.addView(head, Ui.lp(MATCH, WRAP, 0));

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
            TextView none = Ui.text(this, "No sounds yet. Open CarTalk and tap Add sounds.", 15f, Ui.DIM, false);
            none.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
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

        ScrollView sv = new ScrollView(this);
        LinearLayout grid = Ui.col(this);
        final int perRow = 3;
        LinearLayout row = null;
        int n = 0;
        for (final File f : all) {
            if (!group.equals(Library.groupOf(this, f))) {
                continue;
            }
            if (n % perRow == 0) {
                row = new LinearLayout(this);
                grid.addView(row, Ui.lp(MATCH, WRAP, 0));
            }
            TextView t = Ui.text(this, Library.label(f), 15f, Ui.TXT, true);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(2);
            t.setBackground(Ui.box(this, Ui.RAISE, 0, 14));
            t.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
            t.setClickable(true);
            t.setOnClickListener(v -> play(f));
            row.addView(t, Ui.lp(this, 0, Ui.dp(this, 64), 1f, 3));
            n++;
        }
        if (row != null) {
            for (int i = n % perRow; i != 0 && i < perRow; i++) {
                row.addView(new View(this), Ui.lp(this, 0, Ui.dp(this, 64), 1f, 3));
            }
        }
        sv.addView(grid);
        int rows = (n + perRow - 1) / perRow;
        p.addView(sv, Ui.lp(MATCH, Ui.dp(this, Math.min(rows, 3) * 70), 0));
        return p;
    }

    private TextView small(String label, int fill, int textColor, Runnable onClick) {
        TextView b = Ui.button(this, label, fill, textColor, onClick);
        b.setTextSize(14f);
        b.setMinHeight(Ui.dp(this, 44));
        b.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
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
                        lp.x = Math.max(0, startX + (int) dx);
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
                        sp.edit().putInt("float_x", lp.x).putInt("float_y", lp.y).apply();
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
