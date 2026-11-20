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

    static final String[] PRESETS = {"My sounds", "F1", "Memes", "TV and film", "Useful", "Whoopee"};

    /** Groups the user created, in the order they made them. */
    static List<String> customGroups(Context c) {
        List<String> out = new ArrayList<>();
        String raw = c.getSharedPreferences("groups_meta", Context.MODE_PRIVATE).getString("custom", "");
        for (String g : raw.split("\n")) {
            if (!g.trim().isEmpty()) {
                out.add(g.trim());
            }
        }
        return out;
    }

    /** Presets first, then the user's own groups. */
    static List<String> allGroups(Context c) {
        List<String> out = new ArrayList<>(Arrays.asList(PRESETS));
        out.addAll(customGroups(c));
        return out;
    }

    static String groupOf(Context c, File f) {
        return c.getSharedPreferences("groups", Context.MODE_PRIVATE).getString(f.getName(), "My sounds");
    }

    private static final int[] PALETTE = {0xFFFF6B6B, 0xFFB79CFF, 0xFF5FD3A0, 0xFFFF8FC7, 0xFF6CB4FF,
            0xFFF5C451, 0xFF5CD6D6, 0xFFFFA36C, 0xFFA8D65C, 0xFFD69CFF};

    /** A group's colour: the user's choice if they made one, otherwise assigned by position. */
    static int colorOf(Context c, String group) {
        android.content.SharedPreferences p = c.getSharedPreferences("group_colors", Context.MODE_PRIVATE);
        if (p.contains(group)) {
            return p.getInt(group, PALETTE[0]);
        }
        int i = allGroups(c).indexOf(group);
        return PALETTE[(i < 0 ? 0 : i) % PALETTE.length];
    }

    static int[] palette() {
        return PALETTE.clone();
    }

    static void setColor(Context c, String group, int color) {
        c.getSharedPreferences("group_colors", Context.MODE_PRIVATE).edit().putInt(group, color).apply();
    }

    static boolean isFav(Context c, File f) {
        return c.getSharedPreferences("favs", Context.MODE_PRIVATE).getBoolean(f.getName(), false);
    }

    static void setFav(Context c, File f, boolean on) {
        android.content.SharedPreferences.Editor e = c.getSharedPreferences("favs", Context.MODE_PRIVATE).edit();
        if (on) {
            e.putBoolean(f.getName(), true);
        } else {
            e.remove(f.getName());
        }
        e.apply();
    }

    static boolean isZip(String name) {
        return name.toLowerCase(Locale.US).endsWith(".zip");
    }

    /**
     * Unpacks the audio files in a zip into the user's sounds. Only the file name is
     * used for the destination, so entries cannot escape the sounds folder.
     *
     * @param folders filled with file name to top-level folder inside the zip ("" if none)
     * @return number of sounds unpacked
     */
    static int unpackZip(Context c, File zip, java.util.Map<String, String> folders) throws Exception {
        int count = 0;
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new FileInputStream(zip))) {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                String path = e.getName().replace('\\', '/');
                String base = path.substring(path.lastIndexOf('/') + 1);
                if (e.isDirectory() || base.isEmpty() || base.startsWith(".") || path.startsWith("__MACOSX")
                        || !isAudio(base)) {
                    continue;
                }
                String[] parts = path.split("/");
                String folder = parts.length >= 2 ? parts[parts.length - 2].trim() : "";
                try (OutputStream os = new FileOutputStream(new File(dir(c), base))) {
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
                folders.put(base, folder);
                count++;
            }
        }
        return count;
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

    /** Audio files and zip archives in Downloads, Music and on any plugged-in USB storage. */
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
                if ((isAudio(f.getName()) || isZip(f.getName())) && !out.contains(f)) {
                    out.add(f);
                }
            } else if (f.isDirectory() && !f.getName().startsWith(".") && !f.getName().equals("Android")) {
                walk(f, depth + 1, out);
            }
        }
    }
}
