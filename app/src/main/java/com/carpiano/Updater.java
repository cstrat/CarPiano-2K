package com.carpiano;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Update check against this fork's GitHub Releases.
 *
 * <p>The only network access in the app. It fetches the public release list and,
 * if asked, the APK. Nothing about the user or the car is sent.</p>
 */
final class Updater {

    static final String REPO = "cstrat/CarPiano-2K";
    static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases?per_page=10";

    interface Found {
        void done(int build, String apkUrl, String error);
    }

    interface Progress {
        void at(int percent);

        void done(File apk, String error);
    }

    private Updater() {
    }

    /** CI build number baked into versionCode (1000 + GitHub run number). 0 for local builds. */
    @SuppressWarnings("deprecation")
    static int currentBuild(Context c) {
        try {
            int vc = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
            return Math.max(0, vc - 1000);
        } catch (Throwable t) {
            return 0;
        }
    }

    static void check(final Found f) {
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(API).openConnection();
                con.setConnectTimeout(10000);
                con.setReadTimeout(15000);
                con.setRequestProperty("Accept", "application/vnd.github+json");
                con.setRequestProperty("User-Agent", "CarPiano-2K");
                int code = con.getResponseCode();
                if (code != 200) {
                    f.done(-1, null, "GitHub answered " + code);
                    return;
                }
                String body;
                try (InputStream in = con.getInputStream()) {
                    body = new String(Diag.readAll(in), "UTF-8");
                }
                JSONArray arr = new JSONArray(body);
                int best = -1;
                String url = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    String tag = r.optString("tag_name", "");
                    if (!tag.startsWith("build-")) {
                        continue;
                    }
                    int n;
                    try {
                        n = Integer.parseInt(tag.substring(6));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    JSONArray assets = r.optJSONArray("assets");
                    if (assets == null) {
                        continue;
                    }
                    for (int a = 0; a < assets.length(); a++) {
                        JSONObject as = assets.getJSONObject(a);
                        if (as.optString("name", "").endsWith(".apk") && n > best) {
                            best = n;
                            url = as.optString("browser_download_url", null);
                        }
                    }
                }
                if (best < 0 || url == null) {
                    f.done(-1, null, "No builds published yet");
                } else {
                    f.done(best, url, null);
                }
            } catch (Throwable t) {
                f.done(-1, null, t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
        }, "update-check").start();
    }

    /** Build number inside a downloaded APK, or -1 if it cannot be read. */
    @SuppressWarnings("deprecation")
    static int apkBuild(Context c, File apk) {
        try {
            android.content.pm.PackageInfo pi =
                    c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            return pi == null ? -1 : pi.versionCode - 1000;
        } catch (Throwable t) {
            return -1;
        }
    }

    static void download(final Context ctx, final String apkUrl, final int build, final Progress p) {
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                File dir = new File(ctx.getCacheDir(), "updates");
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IllegalStateException("cannot create download folder");
                }
                // A fresh file name per build: the installer was showing a stale copy when
                // every download reused the same name. Older downloads are cleared first.
                File[] old = dir.listFiles();
                if (old != null) {
                    for (File f : old) {
                        f.delete();
                    }
                }
                File out = new File(dir, "CarTalk-build-" + build + ".apk");
                con = (HttpURLConnection) new URL(apkUrl).openConnection();
                con.setConnectTimeout(10000);
                con.setReadTimeout(30000);
                con.setInstanceFollowRedirects(true);
                con.setUseCaches(false);
                con.setRequestProperty("Cache-Control", "no-cache");
                con.setRequestProperty("User-Agent", "CarPiano-2K");
                int code = con.getResponseCode();
                if (code != 200) {
                    p.done(null, "Download answered " + code);
                    return;
                }
                long total = con.getContentLength();
                long got = 0;
                int last = -1;
                try (InputStream in = con.getInputStream();
                     FileOutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            int pct = (int) (got * 100 / total);
                            if (pct != last) {
                                last = pct;
                                p.at(pct);
                            }
                        }
                    }
                }
                p.done(out, null);
            } catch (Throwable t) {
                p.done(null, t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
        }, "update-download").start();
    }

    /** Hands the APK to the system installer. Returns false if that could not be started. */
    static boolean install(Context ctx, File apk) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".files", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Fallback: open a URL in the head unit's browser, the way APKs are installed by hand. */
    static boolean openInBrowser(Context ctx, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
