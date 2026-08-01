package io.lanprojects.phone;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Appends key lifecycle events (server start/stop, kill attempts, PIDs) to a
 * plain-text file in the app's internal files dir:
 *
 *   /data/user/0/io.lanprojects.phone/files/server.log
 *
 * logcat is not reachable from the phone (no adb), so this file is what the
 * in-app "查看日志" screen reads. Both the main process and the ":node"
 * process share the same files dir, so both can append here.
 *
 * Lines are short and each write is a single append, so cross-process
 * interleaving is harmless for diagnostics. The file is capped so it cannot
 * grow forever.
 */
public class ServerLog {

    private static final String FILE = "server.log";
    private static final int MAX_BYTES = 200 * 1024; // ~200 KB

    private ServerLog() {
    }

    public static void log(Context context, String msg) {
        try {
            File f = new File(context.getFilesDir(), FILE);
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "  [" + android.os.Process.myPid() + "]  " + msg + "\n";
            FileOutputStream os = new FileOutputStream(f, true);
            try {
                os.write(line.getBytes("UTF-8"));
            } finally {
                os.close();
            }
            trim(f);
        } catch (Exception ignored) {
        }
    }

    /** Read the whole log, newest lines last. Empty/missing -> friendly hint. */
    public static String read(Context context) {
        StringBuilder sb = new StringBuilder();
        File f = new File(context.getFilesDir(), FILE);
        if (!f.exists()) return "（暂无日志）";
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            return "读取失败: " + e;
        }
        if (sb.length() == 0) return "（暂无日志）";
        return sb.toString();
    }

    public static void clear(Context context) {
        try {
            new File(context.getFilesDir(), FILE).delete();
        } catch (Exception ignored) {
        }
    }

    /** Keep only the last MAX_BYTES, dropping the oldest chunk. */
    private static void trim(File f) {
        if (f.length() <= MAX_BYTES) return;
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            long len = raf.length();
            long skip = len - MAX_BYTES;
            raf.seek(skip);
            byte[] buf = new byte[(int) (len - skip)];
            raf.readFully(buf);
            raf.setLength(0);
            raf.seek(0);
            raf.write(buf);
        } catch (Exception ignored) {
        }
    }
}
