package com.carpiano;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view helpers and the colour palette for the English interface. */
final class Ui {

    static final int BG = 0xFF0D1015;
    static final int SURF = 0xFF161A21;
    static final int RAISE = 0xFF1F2530;
    static final int LINE = 0xFF2B3340;
    static final int TXT = 0xFFF2F4F7;
    static final int DIM = 0xFFA7B0BD;
    static final int OUT = 0xFFFF9447;
    static final int CAB = 0xFF6CB4FF;
    static final int OK = 0xFF5FD3A0;
    static final int WARN_BG = 0xFF33251A;

    private Ui() {
    }

    /** Mixes a colour into a base colour; amount 0 is all base, 1 is all colour. */
    static int blend(int color, int base, float amount) {
        int r = Math.round(android.graphics.Color.red(color) * amount + android.graphics.Color.red(base) * (1 - amount));
        int g = Math.round(android.graphics.Color.green(color) * amount + android.graphics.Color.green(base) * (1 - amount));
        int b = Math.round(android.graphics.Color.blue(color) * amount + android.graphics.Color.blue(base) * (1 - amount));
        return android.graphics.Color.rgb(r, g, b);
    }

    static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    static GradientDrawable box(Context c, int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) {
            g.setStroke(Math.max(1, dp(c, 1)), stroke);
        }
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    static TextView button(Context c, String label, int fill, int textColor, final Runnable onClick) {
        TextView t = text(c, label, 16f, textColor, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(box(c, fill, fill == SURF || fill == BG ? LINE : 0, 14));
        t.setPadding(dp(c, 18), dp(c, 10), dp(c, 18), dp(c, 10));
        t.setMinHeight(dp(c, 52));
        t.setClickable(true);
        t.setFocusable(true);
        if (onClick != null) {
            t.setOnClickListener(v -> onClick.run());
        }
        return t;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(box(c, SURF, LINE, 20));
        int p = dp(c, 20);
        l.setPadding(p, p, p, p);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static LinearLayout.LayoutParams lp(Context c, int w, int h, float weight, int marginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        int m = dp(c, marginDp);
        p.setMargins(m, m, m, m);
        return p;
    }
}
