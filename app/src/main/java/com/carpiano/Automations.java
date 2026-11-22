package com.carpiano;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "When this happens, do that" rules. The floating widget's service watches for
 * triggers and calls {@link #fire}; rules therefore only run while the widget is on.
 */
final class Automations {

    static final String[] TRIGGERS = {"power_on", "power_off", "widget_start"};
    static final String[] TRIGGER_NAMES = {"the car powers on", "the car powers off", "the widget starts"};
    static final String[] ACTIONS = {"play", "sentry_on", "sentry_off", "ambient_on", "ambient_off"};
    static final String[] ACTION_NAMES = {"play a sound", "turn Sentry mode on", "turn Sentry mode off",
            "turn ambient lighting on", "turn ambient lighting off"};

    private static final long COOLDOWN_MS = 30000L;
    private static final Map<String, Long> lastRun = new HashMap<>();

    static final class Rule {
        String id;
        String trigger;
        String action;
        String file = "";
        String target = "cabin";
        boolean enabled = true;
    }

    private Automations() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("auto", Context.MODE_PRIVATE);
    }

    static boolean masterOn(Context c) {
        return prefs(c).getBoolean("on", false);
    }

    static void setMaster(Context c, boolean on) {
        prefs(c).edit().putBoolean("on", on).apply();
    }

    static boolean quietHours(Context c) {
        return prefs(c).getBoolean("quiet", true);
    }

    static void setQuietHours(Context c, boolean on) {
        prefs(c).edit().putBoolean("quiet", on).apply();
    }

    static String lastEvent(Context c) {
        return prefs(c).getString("last", "Nothing has run yet.");
    }

    /** Raw power-signal values the user has marked as "the car is on". */
    static List<Integer> onValues(Context c) {
        List<Integer> out = new ArrayList<>();
        for (String s : prefs(c).getString("on_values", "").split(",")) {
            try {
                if (!s.trim().isEmpty()) {
                    out.add(Integer.parseInt(s.trim()));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static void addOnValue(Context c, int v) {
        List<Integer> cur = onValues(c);
        if (!cur.contains(v)) {
            cur.add(v);
        }
        StringBuilder b = new StringBuilder();
        for (int x : cur) {
            b.append(x).append(',');
        }
        prefs(c).edit().putString("on_values", b.toString()).apply();
    }

    static void clearOnValues(Context c) {
        prefs(c).edit().remove("on_values").apply();
    }

    static List<Rule> load(Context c) {
        List<Rule> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("rules", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Rule r = new Rule();
                r.id = o.optString("id", "r" + i);
                r.trigger = o.optString("trigger", TRIGGERS[0]);
                r.action = o.optString("action", ACTIONS[0]);
                r.file = o.optString("file", "");
                r.target = o.optString("target", "cabin");
                r.enabled = o.optBoolean("enabled", true);
                out.add(r);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static void save(Context c, List<Rule> rules) {
        JSONArray a = new JSONArray();
        try {
            for (Rule r : rules) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("trigger", r.trigger);
                o.put("action", r.action);
                o.put("file", r.file);
                o.put("target", r.target);
                o.put("enabled", r.enabled);
                a.put(o);
            }
        } catch (Throwable ignored) {
        }
        prefs(c).edit().putString("rules", a.toString()).apply();
    }

    private static String nameOf(String[] keys, String[] names, String key) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) {
                return names[i];
            }
        }
        return key;
    }

    static String describe(Rule r) {
        String what = nameOf(ACTIONS, ACTION_NAMES, r.action);
        if ("play".equals(r.action)) {
            what = "play “" + Library.label(new File(r.file)) + "” "
                    + ("outside".equals(r.target) ? "outside" : "in the cabin");
        }
        return "When " + nameOf(TRIGGERS, TRIGGER_NAMES, r.trigger) + ", " + what;
    }

    /** Runs every enabled rule for a trigger, respecting the master switch and a per-rule cooldown. */
    static void fire(Context c, String trigger) {
        if (!masterOn(c)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Rule r : load(c)) {
            if (!r.enabled || !trigger.equals(r.trigger)) {
                continue;
            }
            Long prev = lastRun.get(r.id);
            if (prev != null && now - prev < COOLDOWN_MS) {
                continue;
            }
            lastRun.put(r.id, now);
            run(c, r, false);
        }
    }

    /** Carries out one rule and records what happened. A test run ignores quiet hours. */
    static String run(Context c, Rule r, boolean test) {
        String result;
        try {
            if ("play".equals(r.action)) {
                result = play(c, r, test);
            } else if ("sentry_on".equals(r.action)) {
                result = CarCtl.write(c, CarCtl.SENTRY, 1) ? "Sentry mode on" : "Sentry mode did not change";
            } else if ("sentry_off".equals(r.action)) {
                result = CarCtl.write(c, CarCtl.SENTRY, 0) ? "Sentry mode off" : "Sentry mode did not change";
            } else if ("ambient_on".equals(r.action)) {
                result = CarCtl.write(c, CarCtl.AMBIENCE, 1) ? "ambient lighting on" : "ambient lighting did not change";
            } else if ("ambient_off".equals(r.action)) {
                result = CarCtl.write(c, CarCtl.AMBIENCE, 0) ? "ambient lighting off" : "ambient lighting did not change";
            } else {
                result = "unknown action";
            }
        } catch (Throwable t) {
            result = "failed: " + t.getClass().getSimpleName();
        }
        String stamp = new SimpleDateFormat("EEE HH:mm:ss", Locale.US).format(new Date());
        String line = stamp + (test ? " (test) " : " ") + describe(r) + " → " + result + ".";
        prefs(c).edit().putString("last", line).apply();
        return result;
    }

    private static String play(Context c, Rule r, boolean test) {
        File f = new File(Library.dir(c), r.file);
        if (!f.isFile()) {
            f = new File(c.getFilesDir(), r.file);
        }
        if (!f.isFile()) {
            return "the sound is no longer in the library";
        }
        boolean outside = "outside".equals(r.target);
        if (outside && !test && quietHours(c)) {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            if (hour >= 21 || hour < 7) {
                return "skipped, quiet hours (9 pm to 7 am)";
            }
        }
        AudioOut audio = new AudioOut(c);
        AudioDeviceInfo dev;
        if (outside) {
            dev = audio.outer();
            if (dev == null) {
                return "no outside speaker found";
            }
        } else {
            dev = audio.notifyBus();
            if (dev == null) {
                dev = audio.inner();
            }
        }
        Player.play(c, null, f, dev,
                outside ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_NOTIFICATION,
                (ok, msg) -> {
                });
        return "played";
    }
}
