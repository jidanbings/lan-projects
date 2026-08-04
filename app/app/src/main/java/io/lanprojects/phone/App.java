package io.lanprojects.phone;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes any uncaught Java exception (any thread) to a file the user can
 * reach without adb, so crashes can be diagnosed from the phone itself:
 *
 *   /sdcard/Android/data/io.lanprojects.phone/files/crash.log
 *
 * Native crashes (SIGSEGV/SIGABRT from libnode) are NOT captured here;
 * those still need logcat. But Java-side failures (missing libs, JNI
 * signature mismatches, WebView errors, ...) land in crash.log.
 */
public class App extends Application {

    private static final String TAG = "lan-projects";
    /** crash.log is capped at this size so a crash loop can't bloat the app's data. */
    private static final long MAX_CRASH_LOG_BYTES = 512 * 1024;

    @Override
    public void onCreate() {
        super.onCreate();
        // onCreate runs in BOTH processes (main UI + :node server). The update
        // cleanup only needs to run once per launch, so skip the :node process.
        if (!getProcessName().endsWith(":node")) {
            cleanupInstalledUpdateApk();
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                File dir = getExternalFilesDir(null);
                if (dir != null) {
                    dir.mkdirs();
                    File f = new File(dir, "crash.log");
                    // Cap the log: keep the current file as crash.log.old and
                    // start fresh once it exceeds the limit. Otherwise a crash
                    // loop appends forever and inflates the app's data.
                    if (f.exists() && f.length() > MAX_CRASH_LOG_BYTES) {
                        File old = new File(dir, "crash.log.old");
                        //noinspection ResultOfMethodCallIgnored
                        old.delete();
                        //noinspection ResultOfMethodCallIgnored
                        f.renameTo(old);
                    }
                    String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                            .format(new Date());
                    // Tag each entry with the installed app version, the process
                    // (main UI process vs. the ":node" server process) and the
                    // crashing thread, so a crash.log entry can be tied to a
                    // build and a code path without logcat.
                    String ver = "?";
                    try {
                        ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                    } catch (Exception ignored) {
                    }
                    try (FileOutputStream os = new FileOutputStream(f, true);
                         PrintWriter w = new PrintWriter(os)) {
                        w.println("=== " + ts + "  apk v" + ver
                                + "  proc=" + getProcessName()
                                + "  thread=" + thread.getName() + " ===");
                        throwable.printStackTrace(w);
                        w.println();
                        w.flush();
                    }
                }
            } catch (Exception ignored) {
            }
            Log.e(TAG, "Uncaught exception on " + thread.getName(), throwable);
        });
    }

    /**
     * Deletes the in-app update APK once the version it was downloaded for is
     * actually installed. The system installer never removes the file, so every
     * in-app update used to leave a full APK (~30-50 MB) sitting in the app's
     * own storage (Android/data/.../files/Download/), which is what made the
     * reported 数据 size grow with each update.
     *
     * UpdateDownloadService records "downloaded_version" when a download
     * finishes; if the installed version now equals it, the update landed and
     * the leftover APK can be safely removed. The record is cleared either way
     * so a stale value can never delete an APK that a later, different-version
     * download just placed at the same path.
     */
    private void cleanupInstalledUpdateApk() {
        try {
            SharedPreferences sp = getSharedPreferences("updater", MODE_PRIVATE);
            String downloaded = sp.getString("downloaded_version", null);
            if (downloaded == null) return;
            try {
                String installed = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName;
                if (downloaded.equals(installed)) {
                    File f = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                            "lan-projects-update.apk");
                    if (f != null && f.exists() && f.delete()) {
                        ServerLog.log(this, "已自动删除安装完成的更新包 v" + installed);
                    }
                }
            } finally {
                sp.edit().remove("downloaded_version").apply();
            }
        } catch (Exception e) {
            // Best-effort cleanup; never let it break app startup.
        }
    }
}
