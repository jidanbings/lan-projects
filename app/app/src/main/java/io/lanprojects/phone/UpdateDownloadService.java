package io.lanprojects.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Foreground service that downloads the update APK with a progress notification
 * that CANNOT be swiped away and offers 暂停/继续 and 取消 action buttons. The
 * download is resumable (HTTP Range). On success the APK is handed to the
 * system installer via FileProvider.
 */
public class UpdateDownloadService extends Service {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_VERSION = "version";

    private static final String CHANNEL_ID = "update-download";
    private static final int NOTIF_ID = 100;
    private static final String ACTION_PAUSE = "io.lanprojects.phone.UPDATE_PAUSE";
    private static final String ACTION_RESUME = "io.lanprojects.phone.UPDATE_RESUME";
    private static final String ACTION_CANCEL = "io.lanprojects.phone.UPDATE_CANCEL";

    private Thread thread;
    private volatile boolean paused = false;
    private volatile boolean cancelled = false;
    private String url;
    private String version;
    private File target;
    private long total = -1;
    private long offset = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PAUSE.equals(action)) {
            paused = true;
            updateNotif("已暂停", offset);
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            paused = false;
            updateNotif("继续下载…", offset);
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(action)) {
            cancelled = true;
            if (thread != null) thread.interrupt();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        url = intent.getStringExtra(EXTRA_URL);
        version = intent.getStringExtra(EXTRA_VERSION);
        target = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "lan-projects-update.apk");
        showForegroundNotification(NOTIF_ID, buildNotification("开始下载…", 0, false));
        thread = new Thread(this::download, "update-download");
        thread.start();
        return START_NOT_STICKY;
    }

    private void showForegroundNotification(int id, Notification notif) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(id, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(id, notif);
        }
    }

    private void download() {
        try {
            HttpURLConnection head = (HttpURLConnection) new URL(url).openConnection();
            head.setRequestMethod("HEAD");
            head.setRequestProperty("User-Agent", "lan-projects-android/update");
            head.setConnectTimeout(15000);
            total = head.getContentLengthLong();
            head.disconnect();
        } catch (Exception ignored) {
        }

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "lan-projects-android/update");
            if (offset > 0) conn.setRequestProperty("Range", "bytes=" + offset + "-");
            int code = conn.getResponseCode();
            if (code == 416) {           // range not satisfiable: already fully downloaded
                complete();
                return;
            }
            if (code != 200 && code != 206) {
                fail("下载失败 (HTTP " + code + ")");
                return;
            }

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(target, offset > 0)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while (!cancelled && (n = in.read(buf)) != -1) {
                    while (paused && !cancelled) Thread.sleep(200);
                    if (cancelled) break;
                    out.write(buf, 0, n);
                    offset += n;
                    updateNotif("正在下载 v" + version + "…", offset);
                }
            }
            conn.disconnect();
        } catch (InterruptedException ie) {
            // paused during cancel
        } catch (Exception e) {
            if (!cancelled) fail("下载失败: " + e);
            return;
        }
        if (cancelled) return;
        complete();
    }

    private void complete() {
        installApk(target);
        stopForeground(true);
        stopSelf();
    }

    private void fail(String msg) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("lan-projects 更新")
                .setContentText(msg)
                .setSmallIcon(R.drawable.ic_stat_transparent)
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, n);
        stopForeground(true);
        stopSelf();
    }

    private void installApk(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            ServerLog.log(this, "打开安装器失败: " + e);
        }
    }

    private Notification buildNotification(String text, long progressBytes, boolean isPaused) {
        PendingIntent pause = PendingIntent.getService(this, 1,
                new Intent(this, UpdateDownloadService.class).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent resume = PendingIntent.getService(this, 2,
                new Intent(this, UpdateDownloadService.class).setAction(ACTION_RESUME),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 3,
                new Intent(this, UpdateDownloadService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("lan-projects 更新")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_transparent)
                .setOngoing(true)          // cannot be swiped away
                .setOnlyAlertOnce(true)
                .addAction(0, isPaused ? "继续" : "暂停", isPaused ? resume : pause)
                .addAction(0, "取消", cancel);
        if (total > 0) {
            int pct = (int) (100.0 * progressBytes / total);
            b.setProgress(100, Math.min(100, pct), false);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    private void updateNotif(String text, long progressBytes) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_ID, buildNotification(text, progressBytes, paused));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "更新下载",
                    NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
