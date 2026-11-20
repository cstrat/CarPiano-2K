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
        final int my = ++gen;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                String name = asset != null ? asset : file.getName();
                boolean played = false;
                if (name.toLowerCase().endsWith(".wav")) {
                    played = playWav(app, asset, file, dev, usage, my);
                }
                if (!played) {
                    playDecoded(app, asset, file, dev, usage, my);
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

        Out(int rate, int channels, AudioDeviceInfo dev, int usage, int my) {
            this.my = my;
            int mask = channels >= 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
            bufBytes = Math.max(min * 2, 8192);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(mask)
                            .build())
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (dev != null) {
                track.setPreferredDevice(dev);
            }
            track.play();
        }

        boolean live() {
            return gen == my;
        }

        /** Applies the current volume and writes 16-bit little-endian PCM. */
        void write(byte[] b, int off, int len) {
            double g = gainPct / 100.0;
            if (g != 1.0) {
                for (int i = off; i + 1 < off + len; i += 2) {
                    int s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
                    int v = (int) Math.round(s * g);
                    b[i] = (byte) (v & 0xFF);
                    b[i + 1] = (byte) ((v >> 8) & 0xFF);
                }
            }
            int end = off + len;
            while (off < end && live()) {
                int n = track.write(b, off, Math.min(4096, end - off));
                if (n <= 0) {
                    break;
                }
                off += n;
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
                                   int usage, int my) throws Exception {
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
        Out out = new Out(rate, channels, dev, usage, my);
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
                                    int usage, int my) throws Exception {
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
                                    of.getInteger(MediaFormat.KEY_CHANNEL_COUNT), dev, usage, my);
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
