package com.carpiano;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

/**
 * Experimental: copies whatever other apps are playing (music, radio) to the
 * outside speaker, using Android's playback capture. Android only allows this
 * with the user's consent, from a foreground service, and only for apps that
 * permit capture, so it may capture nothing on some head units or for some apps.
 */
public class MirrorService extends Service {

    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    private static final String CH = "mirror";
    private static final int NOTE_ID = 5201;
    private static final int RATE = 44100;

    static volatile boolean running;
    static volatile int level;
    static volatile String error;
    /** Total non-silent chunks seen, to tell "capturing silence" from "capturing audio". */
    static volatile long heard;

    private volatile boolean run;
    private MediaProjection projection;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        error = null;
        heard = 0;
        try {
            if (Build.VERSION.SDK_INT < 29) {
                throw new IllegalStateException("needs Android 10 or newer");
            }
            if (intent == null) {
                throw new IllegalStateException("no capture permission passed");
            }
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(new NotificationChannel(CH, "Outside mirror",
                        NotificationManager.IMPORTANCE_LOW));
            }
            Notification n = new Notification.Builder(this, CH)
                    .setContentTitle("CarPiano 2K")
                    .setContentText("Mirroring car audio to the outside speaker")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .build();
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null || data == null) {
                throw new IllegalStateException("screen capture service not available");
            }
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) {
                throw new IllegalStateException("capture permission was not granted");
            }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    run = false;
                }
            }, new Handler(Looper.getMainLooper()));
            final AudioDeviceInfo out = new AudioOut(this).outer();
            if (out == null) {
                throw new IllegalStateException("outside speaker not found on this car");
            }
            final MediaProjection mp = projection;
            run = true;
            running = true;
            new Thread(() -> loop(mp, out), "mirror").start();
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            running = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void loop(MediaProjection mp, AudioDeviceInfo out) {
        AudioRecord rec = null;
        AudioTrack trk = null;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .excludeUid(Process.myUid())
                    .build();
            AudioFormat inFmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int rb = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder()
                    .setAudioFormat(inFmt)
                    .setBufferSizeInBytes(Math.max(rb * 2, 16384))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("playback capture could not be opened");
            }
            int tb = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            trk = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(Math.max(tb * 2, 16384))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            trk.setPreferredDevice(out);
            rec.startRecording();
            trk.play();
            short[] buf = new short[2048];
            while (run) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) {
                    throw new IllegalStateException("capture read failed (" + n + ")");
                }
                double g = Player.gainPercent() / 100.0;
                int peak = 0;
                for (int i = 0; i < n; i++) {
                    int v = (int) Math.round(buf[i] * g);
                    if (v > 32767) {
                        v = 32767;
                    } else if (v < -32768) {
                        v = -32768;
                    }
                    buf[i] = (short) v;
                    int a = Math.abs((int) buf[i]);
                    if (a > peak) {
                        peak = a;
                    }
                }
                level = peak * 100 / 32768;
                if (peak > 200) {
                    heard++;
                }
                if (n > 0) {
                    trk.write(buf, 0, n);
                }
            }
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Throwable ignored) {
                }
                try {
                    rec.release();
                } catch (Throwable ignored) {
                }
            }
            if (trk != null) {
                try {
                    trk.pause();
                    trk.flush();
                } catch (Throwable ignored) {
                }
                try {
                    trk.release();
                } catch (Throwable ignored) {
                }
            }
            running = false;
            level = 0;
            run = false;
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        run = false;
        running = false;
        try {
            if (projection != null) {
                projection.stop();
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
