package com.carpiano;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Brings the floating widget back after the head unit starts, if it was on.
 * Some head units never deliver this broadcast, in which case the widget
 * returns the next time CarTalk is opened.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            boolean wanted = c.getSharedPreferences("piano", Context.MODE_PRIVATE).getBoolean("float_on", false);
            if (wanted && !FloatService.running && android.provider.Settings.canDrawOverlays(c)) {
                c.startForegroundService(new Intent(c, FloatService.class));
            }
        } catch (Throwable ignored) {
        }
    }
}
