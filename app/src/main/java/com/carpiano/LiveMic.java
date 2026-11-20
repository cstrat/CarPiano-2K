package com.carpiano;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

/**
 * Live microphone pass-through: cabin mic straight to a chosen output device.
 * Uses the same AudioTrack + setPreferredDevice route the original app proved
 * for the outside speaker.
 */
final class LiveMic {

    private static final int RATE = 44100;

    private volatile boolean run;
    private Thread th;
    /** Peak level of the last chunk, 0 to 100. */
    volatile int level;
    /** Set when the loop stops because of a failure. */
    volatile String error;
    /** Linear gain applied to the microphone signal. */
    volatile double gain = 1.0;

    boolean running() {
        return run;
    }

    synchronized void start(final AudioDeviceInfo out) {
        if (run) {
            return;
        }
        run = true;
        error = null;
        th = new Thread(() -> {
            AudioRecord rec = null;
            AudioTrack trk = null;
            try {
                int rb = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                int tb = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (rb <= 0 || tb <= 0) {
                    throw new IllegalStateException("audio format not supported");
                }
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(rb, 4096));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("microphone could not be opened");
                }
                trk = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(tb, 4096))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                if (out != null) {
                    trk.setPreferredDevice(out);
                }
                rec.startRecording();
                trk.play();
                short[] buf = new short[512];
                while (run) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n < 0) {
                        throw new IllegalStateException("microphone read failed (" + n + ")");
                    }
                    double g = gain;
                    int peak = 0;
                    for (int i = 0; i < n; i++) {
                        int v = (int) Math.round(buf[i] * g);
                        if (v > 32767) {
                            v = 32767;
                        } else if (v < -32768) {
                            v = -32768;
                        }
                        buf[i] = (short) v;
                        int a = Math.abs(v);
                        if (a > peak) {
                            peak = a;
                        }
                    }
                    level = peak * 100 / 32768;
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
                run = false;
                level = 0;
            }
        }, "live-mic");
        th.start();
    }

    void stop() {
        run = false;
    }
}
