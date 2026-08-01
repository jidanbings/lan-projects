package io.lanprojects.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Foreground service that unpacks the bundled nodejs-project into
 * the app's data directory (once) and starts the Node.js lan-projects
 * server on port 3000 inside this process.
 */
public class NodeService extends Service {

    private static final String TAG = "NodeService";
    private static final String PROJECT_DIR = "nodejs-project";
    private static final String CHANNEL_ID = "lan-projects-server";

    // The PID is recorded in a plain file (getFilesDir()/node.pid), NOT in
    // SharedPreferences. SharedPreferences is cached per-process and NOT
    // cross-process safe: the UI process caches the "nodejs" prefs file on its
    // first read (before any server is started, node_pid = -1) and then never
    // sees the PID the :node process later writes, so killNodeProcess() became
    // a silent no-op and "back to home" never killed the server.
    private static final String PID_FILE = "node.pid";

    public static final String ACTION_START = "io.lanprojects.phone.START_SERVER";
    public static final String ACTION_STOP = "io.lanprojects.phone.STOP_SERVER";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            // Forget the PID file so a stale value can never be reused to kill
            // an unrelated recycled process later. (onDestroy then hard-exits
            // this process so the detached node thread dies and port 3000 is
            // freed immediately.)
            new File(getFilesDir(), PID_FILE).delete();
            getSharedPreferences("nodejs", MODE_PRIVATE).edit().remove("node_pid").apply();
            ServerLog.log(this, "ACTION_STOP 收到，进程将自杀");
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(1, buildNotification());
        ServerLog.log(this, "NodeService onStartCommand: ACTION_START");

        // All the heavyweight startup (stale-server cleanup, port wait, PID
        // recording, asset extraction, node launch) runs on the background
        // thread inside startNodeServer() so the service main thread never
        // blocks (an ANR would otherwise be possible when a previous server is
        // still dying and port 3000 is briefly occupied).
        startNodeServer();

        // NOT sticky: if this process is killed (by us or by the system), do
        // NOT let Android restart it - the server must stay dead once the
        // user has left the host screen.
        return START_NOT_STICKY;
    }

    /**
     * Directly kills the node process (the server) by its recorded PID, from any
     * process in the app. Used when the user returns to the launch screen so the
     * server is guaranteed dead - the equivalent of swiping the app away. The
     * PID is recorded by NodeService when it starts, so this is a no-op if no
     * server is (or was) running.
     */
    public static void killNodeProcess(android.content.Context context) {
        try {
            File pidFile = new File(context.getFilesDir(), PID_FILE);
            if (pidFile.exists()) {
                int pid = readPid(pidFile);
                if (pid > 0) {
                    android.util.Log.i(TAG, "killNodeProcess: killing node process " + pid);
                    ServerLog.log(context, "killNodeProcess: 杀掉 node 进程 " + pid);
                    android.os.Process.killProcess(pid);
                } else {
                    android.util.Log.w(TAG, "killNodeProcess: pid file present but unreadable (" + pid + ")");
                    ServerLog.log(context, "killNodeProcess: PID 文件存在但无法解析 (" + pid + ")");
                }
                // Drop the PID regardless, so a stale value can never be reused
                // to kill an unrelated recycled process later.
                pidFile.delete();
            } else {
                android.util.Log.i(TAG, "killNodeProcess: no pid file, nothing to kill");
                ServerLog.log(context, "killNodeProcess: 无 PID 文件，无需杀进程");
            }
            // Also drop any legacy prefs PID written by older builds.
            context.getSharedPreferences("nodejs", android.content.Context.MODE_PRIVATE)
                    .edit().remove("node_pid").apply();
        } catch (Exception e) {
            android.util.Log.e(TAG, "killNodeProcess failed", e);
        }
    }

    /** Write our PID to the cross-process-visible pid file. */
    private void writeNodePid(int pid) {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(
                new File(getFilesDir(), PID_FILE))) {
            fos.write(String.valueOf(pid).getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    private static int readPid(File f) {
        try (java.io.FileInputStream is = new java.io.FileInputStream(f);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[32];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return Integer.parseInt(bos.toString("UTF-8").trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Block until the given TCP port can be bound on 127.0.0.1, or timeout.
     * Guards against starting Node while a freshly-killed previous server still
     * holds the port (node would exit with EADDRINUSE and never come up).
     */
    private void waitForPortFree(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (portFree(port)) return;
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                return;
            }
        }
    }

    private boolean portFree(int port) {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(port, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            return true; // bound successfully -> port was free
        } catch (Exception e) {
            return false; // port in use
        }
    }

    /**
     * This service owns the entire ":node" process, so stopping the service IS
     * stopping the server. Kill this process so libnode stops and port 3000 is
     * freed - otherwise a stopService() from the UI process (which is allowed in
     * the background, unlike startService) would stop the service but leave the
     * detached node thread alive and the old server still answering on 3000.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        ServerLog.log(this, "NodeService onDestroy：进程自杀，服务器停止");
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void startNodeServer() {
        new Thread(() -> {
            try {
                // Self-clean: if a PID file is left over from a PREVIOUS server
                // that somehow survived (e.g. an earlier kill raced), kill that
                // process now so we never serve alongside a stale server full of
                // old peers. Runs before we overwrite the pid file with OUR pid.
                int recordedPid = readPid(new File(getFilesDir(), PID_FILE));
                if (recordedPid > 0 && recordedPid != android.os.Process.myPid()) {
                    Log.i(TAG, "Killing leftover node process " + recordedPid + " before starting fresh");
                    ServerLog.log(NodeService.this, "启动前发现残留进程 " + recordedPid + "，先杀掉");
                    android.os.Process.killProcess(recordedPid);
                } else if (recordedPid == android.os.Process.myPid()) {
                    ServerLog.log(NodeService.this, "启动时 PID 文件指向当前进程(复用旧进程)，不再自杀");
                }

                // Wait until port 3000 is actually free before launching Node. A
                // freshly-killed previous server may still be closing its socket;
                // if we start before it is gone, node exits with EADDRINUSE and
                // the new server never comes up (and setupHost() would then
                // appear to "keep the old peers", because the old server was
                // never replaced).
                waitForPortFree(3000, 10_000);

                // Record this process's PID so the UI process can kill it
                // directly (Process.killProcess) when the user returns to the
                // launch screen - the equivalent of swiping the app away.
                // Written to a plain file (not SharedPreferences) because the UI
                // process reads it from its own process and SharedPreferences is
                // not reliably visible across processes (see PID_FILE comment).
                writeNodePid(android.os.Process.myPid());
                Log.i(TAG, "Server starting, pid=" + android.os.Process.myPid());
                ServerLog.log(NodeService.this, "服务器开始启动，pid=" + android.os.Process.myPid());

                File projectDir = new File(getFilesDir(), PROJECT_DIR);
                int versionCode;
                try {
                    // getLongVersionCode() since API 28 (minSdk); versionCode
                    // field is deprecated.
                    versionCode = (int) getPackageManager()
                            .getPackageInfo(getPackageName(), 0).getLongVersionCode();
                } catch (Exception e) {
                    versionCode = 1;
                }
                int extractedVersion = readExtractedVersion();
                boolean needsExtract = !projectDir.exists()
                        || !new File(projectDir, "server/index.js").exists()
                        || extractedVersion != versionCode;
                if (needsExtract) {
                    deleteRecursively(projectDir);
                    projectDir.mkdirs();
                    copyAssetsToDir("nodejs-project", projectDir);
                    writeExtractedVersion(versionCode);
                    Log.i(TAG, "nodejs-project unpacked (apk v" + versionCode + ") to " + projectDir);
                } else {
                    Log.i(TAG, "nodejs-project already present, reusing " + projectDir);
                }

                // Give Node a writable temp/home inside the app sandbox.
                NodeBridge.setEnv("TMPDIR", getCacheDir().getAbsolutePath());
                NodeBridge.setEnv("HOME", getFilesDir().getAbsolutePath());

                // The Android WebView cannot do WebRTC, so file transfers must go
                // through the WebSocket relay (WSPeer). That path also carries the
                // mandatory end-to-end encryption. Without WS_FALLBACK the server
                // refuses to create any peer connection and every transfer fails.
                // (An unknown CLI flag would make Node exit with "bad option", so
                // enable it via the environment instead.)
                NodeBridge.setEnv("WS_FALLBACK", "true");

                String mainJs = new File(projectDir, "server/index.js").getAbsolutePath();
                Log.i(TAG, "Starting Node: " + mainJs);

                // IMPORTANT: Node MUST run in its own ":node" process (declared in
                // AndroidManifest.xml). When it shared the UI process, the WebView's
                // Chromium engine had already installed a SIGSEGV handler; V8's
                // WebAssembly trap-handler setup (EnableTrapHandler) detected that
                // and aborted with SIGTRAP on Android 15/16, crashing the whole app
                // at every startup. In a clean ":node" process there is no Chromium,
                // so the trap handler installs fine. No CLI flag disables it in this
                // nodejs-mobile build (--no-wasm-trap-handler and
                // --disable-wasm-trap-handler both exit with "bad option"), so the
                // process separation is the fix.
                NodeBridge.startNodeWithArguments(new String[]{"node", mainJs});
            } catch (Exception e) {
                Log.e(TAG, "Failed to start Node server", e);
            }
        }, "node-server").start();
    }

    private int readExtractedVersion() {
        return getSharedPreferences("nodejs", MODE_PRIVATE).getInt("extracted_version", 0);
    }

    private void writeExtractedVersion(int version) {
        getSharedPreferences("nodejs", MODE_PRIVATE)
                .edit().putInt("extracted_version", version).apply();
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Recursively copy an asset tree to a directory on the file system. */
    private void copyAssetsToDir(String assetPath, File destDir) throws IOException {
        AssetManager am = getAssets();
        String[] children = am.list(assetPath);
        if (children != null && children.length > 0) {
            if (!destDir.exists()) destDir.mkdirs();
            for (String child : children) {
                copyAssetsToDir(assetPath + "/" + child, new File(destDir, child));
            }
        } else {
            File parent = destDir.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (InputStream is = am.open(assetPath);
                 OutputStream os = new FileOutputStream(destDir)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
            }
        }
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "lan-projects 服务器",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        // minSdk 28 guarantees API 26+ (O), and createNotificationChannel() ran
        // in onCreate(), so the channel-aware constructor is always valid.
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        // Use a fully transparent icon so the notification shows NO icon at
        // all (status bar stays clean, drawer shows text only). The server
        // foreground service notification itself is mandatory on Android 8+,
        // but its icon can be invisible.
        builder.setSmallIcon(R.drawable.ic_stat_transparent);
        try {
            builder.setLargeIcon(android.graphics.BitmapFactory.decodeResource(
                    getResources(), R.drawable.ic_stat_transparent));
        } catch (Exception ignored) {
        }
        return builder
                .setContentTitle("lan-projects")
                .setContentText("局域网文件共享服务器运行中")
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
