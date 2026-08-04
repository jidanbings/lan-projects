package io.lanprojects.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the MAIN process (and the WebView/WebSocket living in it) running for
 * the whole transfer session, in host mode AND client mode.
 *
 * WHY: the Node.js server runs in its own ":node" process guarded by its own
 * foreground service, but the main process that hosts the WebView driving the
 * transfer page has NO foreground component. The moment the user opens the
 * system file picker ("选择文件") the app is pushed to the background, the main
 * process drops to the cached bucket and Android may freeze or kill it - which
 * tears down the WebSocket and the other device sees the server disconnect
 * ("收文件的手机自动断开"). A foreground service keeps this process in the
 * foreground-service bucket for the whole session, so the connection survives
 * the file picker being open.
 *
 * Started in MainActivity.onCreate for both host and client modes, stopped in
 * onDestroy. It performs no work itself; it exists only to hold the process.
 */
public class HostSessionService extends Service {

    private static final String CHANNEL_ID = "host-session";
    private static final int NOTIF_ID = 2;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        // Hold a partial wake lock for the whole session so this phone never
        // drops into Doze while the user is picking files on the peer device:
        // Doze would let the wireless radio sleep and drop the peer's TCP
        // connection (WebSocket close code 1006). Released in onDestroy.
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "lan-projects:transfer-session");
            wakeLock.acquire();
        }

        // Hold a WifiLock for the same period. The wake lock above only keeps
        // the CPU on; it does NOT keep the WiFi / hotspot RADIO out of
        // power-save. While the system file picker is open our own Activity is
        // stopped, so FLAG_KEEP_SCREEN_ON stops working - when the screen then
        // times out (~30s), the radio can drop the TCP link, which kills BOTH
        // phones' WebSocket connections at once (the sender's own and the
        // receiver's, which is relayed through this server) - the "选择文件超过
        // 10~35 秒就双双断联" symptom. A WifiLock keeps the radio awake for the
        // whole session regardless of screen state, so the link between the two
        // phones survives a long file-picker stay. Released in onDestroy.
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "lan-projects:transfer-session-wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            wifiLock = null;
        }
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Transparent icon + text only: no status-bar icon, no sound. */
    private Notification buildNotification() {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        builder.setSmallIcon(R.drawable.ic_stat_transparent);
        builder.setContentTitle("lan-projects");
        builder.setContentText("传输会话保持连接中");
        builder.setOngoing(true);
        builder.setLocalOnly(true);
        builder.setCategory(Notification.CATEGORY_SERVICE);
        builder.setOnlyAlertOnce(true);
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "传输会话保持连接",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
