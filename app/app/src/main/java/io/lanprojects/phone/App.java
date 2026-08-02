package io.lanprojects.phone;

import android.app.Application;
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

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                File dir = getExternalFilesDir(null);
                if (dir != null) {
                    dir.mkdirs();
                    File f = new File(dir, "crash.log");
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
}
