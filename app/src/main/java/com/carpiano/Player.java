package com.carpiano;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Plays one sound at a time to a named output device.
 *
 * <p>WAV (16-bit PCM) is read directly. Everything else (MP3, M4A, OGG, FLAC)
 * is decoded with the platform codecs and streamed, so long music files work
 * without loading them into memory. Output always goes through AudioTrack with
 * setPreferredDevice, the route proven for the outside speaker.</p>
 *
 * <p>After the last sample a buffer of silence is written before the track is
 * released, so short sounds are not cut off or skipped.</p>
 */
final class Player {

    interface Cb {
        void done(boolean ok, String message);
    }

    /** Bumped to stop whatever is playing. */
    private static volatile int gen;
    private static volatile int gainPct = 60;
    /** Extra gain for the outside speaker, shaped by a soft limiter. 1.0 is off. */
    static volatile double outsideBoost = 1.0;
    /** Send the sound on every channel the outside speaker's bus offers, not only the first two. */
    static volatile boolean outsideAllChannels;

    static final double[] BOOST_LEVELS = {1.0, 2.5, 5.0};
    static final String[] BOOST_NAMES = {"Normal", "Loud", "Max"};

    /** Reads the outside loudness settings saved by the Setup page. */
    static void loadOutside(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("piano", Context.MODE_PRIVATE);
        int level = Math.max(0, Math.min(BOOST_LEVELS.length - 1, p.getInt("out_boost", 0)));
        outsideBoost = BOOST_LEVELS[level];
        outsideAllChannels = p.getBoolean("out_allch", false);
    }

    static boolean isOutside(AudioDeviceInfo dev) {
        return dev != null && dev.getAddress() != null && dev.getAddress().toUpperCase().contains("OUTER");
    }

    /** Soft limiter: louder without the harsh crackle of hard clipping. */
    static double limit(double v) {
        return 32767.0 * Math.tanh(v / 32767.0);
    }

    private Player() {
    }

    static void setGainPercent(int pct) {
        gainPct = Math.max(0, Math.min(100, pct));
    }

    static int gainPercent() {
        return gainPct;
    }

    static void stop() {
        gen++;
    }

    /** Exactly one of asset (path under assets/) or file is non-null. */
    static void play(final Context ctx, final String asset, final File file,
                     final AudioDeviceInfo dev, final int usage, final Cb cb) {
        play(ctx, asset, file, dev, usage, false, 0f, cb);
    }

    /**
     * @param stereo force a stereo output so the sound can be placed left or right
     * @param pan    -1 = left only, 0 = centre, 1 = right only (used when stereo is true)
     */
    static void play(final Context ctx, final String asset, final File file,
                     final AudioDeviceInfo dev, final int usage, final boolean stereo,
                     final float pan, final Cb cb) {
        play(ctx, asset, file, dev, usage, stereo, pan, -1, cb);
    }

    /** Index of each speaker position in Android's 5.1 channel order. */
    static final int FRONT_LEFT = 0, FRONT_RIGHT = 1, BACK_LEFT = 4, BACK_RIGHT = 5;

    /**
     * Plays the sound on one channel of a 5.1 stream so the car's amplifier can place it
     * at a single seat. If the output cannot take 5.1 it falls back to stereo, left or right.
     */
    static void playAtSeat(final Context ctx, final String asset, final File file,
                           final AudioDeviceInfo dev, final int usage, final int seatChannel,
                           final Cb cb) {
        play(ctx, asset, file, dev, usage, true, 0f, seatChannel, cb);
    }

    private static void play(final Context ctx, final String asset, final File file,
                             final AudioDeviceInfo dev, final int usage, final boolean stereo,
                             final float pan, final int seat, final Cb cb) {
        final int my = ++gen;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                String name = asset != null ? asset : file.getName();
                boolean played = false;
                if (name.toLowerCase().endsWith(".wav")) {
                    played = playWav(app, asset, file, dev, usage, my, stereo, pan, seat);
                }
                if (!played) {
                    playDecoded(app, asset, file, dev, usage, my, stereo, pan, seat);
                }
                cb.done(true, gen == my ? "finished" : "stopped");
            } catch (Throwable t) {
                cb.done(false, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "player").start();
    }

    // ------------------------------------------------------------------ output

    private static final class Out {
        final AudioTrack track;
        final int bufBytes;
        final int my;
        final int inCh;
        final int outCh;
        final int seat;
        final boolean fan;
        final double boost;
        final double gl;
        final double gr;

        Out(int rate, int channels, AudioDeviceInfo dev, int usage, int my, boolean stereo, float pan, int seat) {
            this.my = my;
            inCh = channels >= 2 ? 2 : 1;
            boolean outside = isOutside(dev);
            boost = outside ? outsideBoost : 1.0;
            AudioTrack t = null;
            int size = 0;
            int wide = 0;
            if (outside && outsideAllChannels && seat < 0) {
                // Fill every channel the bus reports, in case it drives more than two speakers.
                int n = 0;
                for (int c : dev.getChannelCounts()) {
                    n = Math.max(n, c);
                }
                if (n > 2 && n <= 16) {
                    try {
                        size = Math.max(rate * n * 2 / 4, 16384);
                        t = new AudioTrack.Builder()
                                .setAudioAttributes(new AudioAttributes.Builder()
                                        .setUsage(usage)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build())
                                .setAudioFormat(new AudioFormat.Builder()
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .setSampleRate(rate)
                                        .setChannelIndexMask((1 << n) - 1)
                                        .build())
                                .setBufferSizeInBytes(size)
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build();
                        wide = n;
                    } catch (Throwable ignored) {
                        t = null;
                    }
                }
            }
            boolean six = false;
            if (t == null && seat >= 0) {
                try {
                    int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_5POINT1,
                            AudioFormat.ENCODING_PCM_16BIT);
                    if (min > 0) {
                        size = Math.max(min * 2, 24576);
                        t = build(rate, AudioFormat.CHANNEL_OUT_5POINT1, size, usage);
                        six = true;
                    }
                } catch (Throwable ignored) {
                    t = null;
                }
            }
            if (t != null && wide > 0) {
                outCh = wide;
                this.seat = -1;
                fan = true;
                gl = 1.0;
                gr = 1.0;
            } else if (t != null && six) {
                outCh = 6;
                this.seat = seat;
                fan = false;
                gl = 1.0;
                gr = 1.0;
            } else {
                // Plain mono or stereo; a seat request falls back to left or right.
                float p = seat >= 0 ? (seat == FRONT_LEFT || seat == BACK_LEFT ? -1f : 1f) : pan;
                this.seat = -1;
                fan = false;
                outCh = stereo ? 2 : inCh;
                gl = stereo && p > 0 ? 1.0 - p : 1.0;
                gr = stereo && p < 0 ? 1.0 + p : 1.0;
                int mask = outCh == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
                int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
                size = Math.max(min * 2, 8192);
                t = build(rate, mask, size, usage);
            }
            track = t;
            bufBytes = size;
            if (dev != null) {
                track.setPreferredDevice(dev);
            }
            track.play();
        }

        private static AudioTrack build(int rate, int mask, int size, int usage) {
            return new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(mask)
                            .build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        }

        boolean live() {
            return gen == my;
        }

        private static void put(byte[] o, int at, double v) {
            int x = (int) Math.round(v);
            if (x > 32767) {
                x = 32767;
            } else if (x < -32768) {
                x = -32768;
            }
            o[at] = (byte) (x & 0xFF);
            o[at + 1] = (byte) ((x >> 8) & 0xFF);
        }

        /** Applies volume and left/right placement, then writes 16-bit little-endian PCM. */
        void write(byte[] b, int off, int len) {
            double g = gainPct / 100.0;
            byte[] o;
            int oo;
            int ol;
            if (boost > 1.0) {
                g *= boost;
            }
            if (fan) {
                int frameBytes = inCh * 2;
                int frames = len / frameBytes;
                int outFrame = outCh * 2;
                o = new byte[frames * outFrame];
                for (int f = 0; f < frames; f++) {
                    int at = off + f * frameBytes;
                    double v = (short) ((b[at] & 0xFF) | (b[at + 1] << 8));
                    if (inCh == 2) {
                        v = (v + (short) ((b[at + 2] & 0xFF) | (b[at + 3] << 8))) / 2.0;
                    }
                    double y = boost > 1.0 ? limit(v * g) : v * g;
                    for (int ch = 0; ch < outCh; ch++) {
                        put(o, f * outFrame + ch * 2, y);
                    }
                }
                oo = 0;
                ol = o.length;
            } else if (outCh == 6) {
                int frameBytes = inCh * 2;
                int frames = len / frameBytes;
                o = new byte[frames * 12];
                for (int f = 0; f < frames; f++) {
                    int at = off + f * frameBytes;
                    double v = (short) ((b[at] & 0xFF) | (b[at + 1] << 8));
                    if (inCh == 2) {
                        v = (v + (short) ((b[at + 2] & 0xFF) | (b[at + 3] << 8))) / 2.0;
                    }
                    put(o, f * 12 + seat * 2, v * g);
                }
                oo = 0;
                ol = o.length;
            } else if (outCh == 2 && inCh == 1) {
                o = new byte[len * 2];
                for (int i = 0; i + 1 < len; i += 2) {
                    int s = (short) ((b[off + i] & 0xFF) | (b[off + i + 1] << 8));
                    put(o, i * 2, boost > 1.0 ? limit(s * g * gl) : s * g * gl);
                    put(o, i * 2 + 2, boost > 1.0 ? limit(s * g * gr) : s * g * gr);
                }
                oo = 0;
                ol = len * 2;
            } else {
                for (int i = 0; i + 1 < len; i += 2) {
                    int s = (short) ((b[off + i] & 0xFF) | (b[off + i + 1] << 8));
                    double k = outCh == 2 ? ((i / 2) % 2 == 0 ? gl : gr) : 1.0;
                    put(b, off + i, boost > 1.0 ? limit(s * g * k) : s * g * k);
                }
                o = b;
                oo = off;
                ol = len;
            }
            int end = oo + ol;
            while (oo < end && live()) {
                int n = track.write(o, oo, Math.min(4096, end - oo));
                if (n <= 0) {
                    break;
                }
                oo += n;
            }
        }

        void finish() {
            try {
                if (live()) {
                    // Push the real audio out of the buffer before releasing.
                    byte[] silence = new byte[4096];
                    int left = bufBytes + 4096;
                    while (left > 0 && live()) {
                        int n = track.write(silence, 0, Math.min(silence.length, left));
                        if (n <= 0) {
                            break;
                        }
                        left -= n;
                    }
                    if (live()) {
                        Thread.sleep(250);
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                track.pause();
                track.flush();
            } catch (Throwable ignored) {
            }
            try {
                track.release();
            } catch (Throwable ignored) {
            }
        }
    }

    // --------------------------------------------------------------------- wav

    /** Returns false if the file is not plain 16-bit PCM, so the decoder path can try it. */
    private static boolean playWav(Context ctx, String asset, File file, AudioDeviceInfo dev,
                                   int usage, int my, boolean stereo, float pan, int seat) throws Exception {
        byte[] b;
        try (InputStream in = asset != null ? ctx.getAssets().open(asset) : new FileInputStream(file)) {
            b = Diag.readAll(in);
        }
        if (b.length < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') {
            return false;
        }
        int rate = 0, channels = 0, bits = 0, format = 0, dataOff = -1, dataLen = 0;
        int p = 12;
        while (p + 8 <= b.length) {
            String id = new String(b, p, 4, "US-ASCII");
            int size = le32(b, p + 4);
            if (size < 0) {
                break;
            }
            if (id.equals("fmt ") && p + 24 <= b.length) {
                format = le16(b, p + 8);
                channels = le16(b, p + 10);
                rate = le32(b, p + 12);
                bits = le16(b, p + 22);
            } else if (id.equals("data")) {
                dataOff = p + 8;
                dataLen = Math.min(size, b.length - dataOff);
                break;
            }
            p += 8 + size + (size & 1);
        }
        if (dataOff < 0 || dataLen <= 0 || format != 1 || bits != 16 || rate <= 0
                || channels < 1 || channels > 2) {
            return false;
        }
        Out out = new Out(rate, channels, dev, usage, my, stereo, pan, seat);
        try {
            out.write(b, dataOff, dataLen & ~1);
        } finally {
            out.finish();
        }
        return true;
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    // ----------------------------------------------------------------- decoder

    private static void playDecoded(Context ctx, String asset, File file, AudioDeviceInfo dev,
                                    int usage, int my, boolean stereo, float pan, int seat) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        Out out = null;
        try {
            if (asset != null) {
                try (AssetFileDescriptor afd = ctx.getAssets().openFd(asset)) {
                    ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                }
            } else {
                ex.setDataSource(file.getAbsolutePath());
            }
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    ex.selectTrack(i);
                    fmt = f;
                    break;
                }
            }
            if (fmt == null) {
                throw new IllegalStateException("no audio found in this file");
            }
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false;
            boolean outDone = false;
            byte[] chunk = new byte[16384];
            while (!outDone && gen == my) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int n = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi >= 0) {
                    if (info.size > 0) {
                        if (out == null) {
                            MediaFormat of = codec.getOutputFormat();
                            out = new Out(of.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                    of.getInteger(MediaFormat.KEY_CHANNEL_COUNT), dev, usage, my, stereo, pan, seat);
                        }
                        ByteBuffer ob = codec.getOutputBuffer(oi);
                        if (ob != null) {
                            if (chunk.length < info.size) {
                                chunk = new byte[info.size];
                            }
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            ob.get(chunk, 0, info.size);
                            out.write(chunk, 0, info.size & ~1);
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outDone = true;
                    }
                }
            }
        } finally {
            if (out != null) {
                out.finish();
            }
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                try {
                    codec.release();
                } catch (Throwable ignored) {
                }
            }
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
