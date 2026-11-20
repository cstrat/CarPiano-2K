package com.carpiano;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** The user's own sounds: imported audio files kept in the app's private storage. */
final class Library {

    private static final String[] EXT = {".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac"};
    private static final int MAX_FOUND = 400;

    private Library() {
    }

    static boolean isAudio(String name) {
        String n = name.toLowerCase(Locale.US);
        for (String e : EXT) {
            if (n.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "user-sounds");
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d;
    }

    /** Imported sounds, then recordings left by earlier builds (rec-*.wav), by name. */
    static List<File> userSounds(Context c) {
        List<File> out = new ArrayList<>();
        File[] a = dir(c).listFiles();
        if (a != null) {
            for (File f : a) {
                if (f.isFile() && isAudio(f.getName())) {
                    out.add(f);
                }
            }
        }
        File[] r = c.getFilesDir().listFiles();
        if (r != null) {
            for (File f : r) {
                if (f.isFile() && f.getName().startsWith("rec-") && isAudio(f.getName())) {
                    out.add(f);
                }
            }
        }
        out.sort((x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        return out;
    }

    static String label(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot > 0) {
            n = n.substring(0, dot);
        }
        if (n.startsWith("rec-")) {
            n = n.substring(4);
        }
        return n.replace('_', ' ').replace('-', ' ').trim();
    }

    static File importFile(Context c, File src) throws Exception {
        File dst = new File(dir(c), src.getName());
        try (InputStream in = new FileInputStream(src); OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
        return dst;
    }

    /** Audio files in Downloads, Music and on any plugged-in USB storage. */
    static List<File> findImportable() {
        List<File> out = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                roots.add(new File(ext, "Download"));
                roots.add(new File(ext, "Downloads"));
                roots.add(new File(ext, "Music"));
                roots.add(ext);
            }
        } catch (Throwable ignored) {
        }
        try {
            File[] vols = new File("/storage").listFiles();
            if (vols != null) {
                for (File v : vols) {
                    String n = v.getName();
                    if (v.isDirectory() && !n.equals("emulated") && !n.equals("self")) {
                        roots.add(v);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        for (File r : roots) {
            walk(r, 0, out);
        }
        return out;
    }

    private static void walk(File d, int depth, List<File> out) {
        if (depth > 3 || out.size() >= MAX_FOUND) {
            return;
        }
        File[] a;
        try {
            a = d.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (a == null) {
            return;
        }
        Arrays.sort(a, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        for (File f : a) {
            if (out.size() >= MAX_FOUND) {
                return;
            }
            if (f.isFile()) {
                if (isAudio(f.getName()) && !out.contains(f)) {
                    out.add(f);
                }
            } else if (f.isDirectory() && !f.getName().startsWith(".") && !f.getName().equals("Android")) {
                walk(f, depth + 1, out);
            }
        }
    }
}
