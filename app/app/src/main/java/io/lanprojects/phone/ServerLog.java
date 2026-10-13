package io.lanprojects.phone;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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
 * interleaving is harmless for diagnostics. Only the newest MAX_LINES lines
 * are kept - older lines are dropped on the next append, so the file can
 * never grow without bound.
 */
public class ServerLog {

    private static final String FILE = "server.log";
    /** Keep only the newest 100 lines; anything older is dropped on append. */
    private static final int MAX_LINES = 100;

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

    /**
     * Keep only the newest MAX_LINES lines, dropping the oldest. Writes are
     * rare (lifecycle events, not per-request), so reading the small file here
     * is cheap. Two processes can append to the same file; if their trims
     * interleave a line may occasionally be lost - acceptable for a diagnostic
     * log.
     */
    private static void trim(File f) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        } catch (Exception e) {
            return;
        }
        if (lines.size() <= MAX_LINES) return;
        int from = lines.size() - MAX_LINES;
        try (FileOutputStream os = new FileOutputStream(f, false)) {
            for (int i = from; i < lines.size(); i++) {
                os.write((lines.get(i) + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }
}
