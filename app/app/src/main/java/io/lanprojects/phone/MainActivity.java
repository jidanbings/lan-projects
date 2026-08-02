package io.lanprojects.phone;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import android.webkit.ConsoleMessage;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.net.Inet4Address;
import java.security.MessageDigest;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Hosts the lan-projects web UI in a WebView. Two modes, chosen on the
 * launch screen:
 *
 *  - host mode: starts the in-app Node.js server and loads its UI via the
 *    phone's LAN address (shares the server's IP room with LAN clients);
 *    other devices connect to this phone's LAN address.
 *  - client mode: does NOT start a server; instead it loads another server's
 *    UI (e.g. a second phone running the same app), so this device becomes a
 *    peer on that server and two phones can exchange files.
 *
 * Adds the native bits a bare WebView lacks in both modes: the system file
 * picker (to send files) and blob-download persistence (to save files).
 */
public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_TARGET = "target";
    public static final String MODE_HOST = "host";
    public static final String MODE_CLIENT = "client";

    private static final int SERVER_PORT = 3000;

    private WebView webView;
    private TextView statusText;
    private ValueCallback<Uri[]> filePathCallback;
    private LanProjectsBridge lanProjectsBridge;
    private SaveFileServer saveFileServer;

    private String mode;
    private String targetUrl;

    private final ActivityResultLauncher<String[]> pickFilesLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(uris.toArray(new Uri[0]));
                    filePathCallback = null;
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        statusText = findViewById(R.id.statusText);

        // 返回 button in the top bar: forcibly return to the launch screen.
        // finish() triggers onDestroy, which tears down the Node server (host
        // mode) so all services are stopped.
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // 刷新 button (left of 返回): reload the page so the WebView re-joins
        // the server room / reconnects the WebSocket. Useful when a peer's UI
        // looks stuck or a new device did not appear.
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            if (webView != null) {
                ServerLog.log(this, "用户点击刷新，重新加载页面");
                webView.reload();
            }
        });

        // 二维码 button: show this phone's LAN address as a QR others can scan.
        findViewById(R.id.btnShowQr).setOnClickListener(v -> showQrDialog());

        // Android 15 (targetSdk 35) forces edge-to-edge: both system bars become
        // transparent and our content is drawn behind them. Extend the window into
        // the display cutout (punch-hole camera) so the dark top strip covers it,
        // then pad by the system-bar insets so nothing is hidden underneath.
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        // Dark background behind both bars -> keep the icons light (white).
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // ---- WebView setup common to both modes ----
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // This is a LAN-only tool: skip Google SafeBrowsing's blocklist lookups
        // on every page load (faster first paint, no queries leave the device
        // for LAN addresses).
        s.setSafeBrowsingEnabled(false);
        // Pre-raster the page while the WebView is off-screen so the transfer
        // UI appears as soon as the page is ready.
        s.setOffscreenPreRaster(true);
        webView.setBackgroundColor(Color.WHITE);

        // Bridge used by the injected blob-download routine. Kept as a field so
        // the DownloadListener can ask it for the real filename the web UI set
        // just before triggering the download.
        lanProjectsBridge = new LanProjectsBridge(this);
        webView.addJavascriptInterface(lanProjectsBridge, "LanProjectsBridge");

        // Local HTTP server that received blobs are POSTed to as raw binary and
        // streamed straight to Downloads (fast, no JS-bridge base64 bottleneck).
        saveFileServer = new SaveFileServer(this);
        saveFileServer.start();

        // Native file picker for <input type="file"> so the phone can send files.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                pickFilesLauncher.launch(new String[]{"*/*"});
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                // Forward ONLY the frontend's explicit [debug] lines (peer-list
                // reports, connection events) into the in-app log. Everything
                // else (every WS message, etc.) is far too noisy to keep.
                String m = consoleMessage.message();
                if (m != null && m.contains("[debug]")) {
                    ServerLog.log(MainActivity.this, "[web] " + m);
                }
                return super.onConsoleMessage(consoleMessage);
            }
        });

        // Persist received files: blob: downloads are saved via the JS bridge.
        // The WebView does NOT hand over the anchor's `download` attribute, so
        // prefer the real name the web UI announced via LanProjectsBridge and
        // only fall back to the Content-Disposition / blob URL when it is absent.
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url != null && url.startsWith("blob:")) {
                String filename = lanProjectsBridge.takePendingFileName();
                if (filename == null || filename.isEmpty()) {
                    filename = downloadName(contentDisposition, url);
                }
                downloadBlob(url, filename, mimetype);
            }
        });

        mode = getIntent().getStringExtra(EXTRA_MODE);
        targetUrl = getIntent().getStringExtra(EXTRA_TARGET);

        // Foreground notification (Android 13+): needed in host mode for the
        // NodeService server notification. (No keep-alive service anymore - the
        // user explicitly wants no background persistence; leaving the transfer
        // page tears everything down.)
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }

        if (MODE_CLIENT.equals(mode)) {
            setupClient(targetUrl);
        } else {
            setupHost();
        }
    }

    /** Host mode: run the in-app server and load its UI. */
    private void setupHost() {
        webView.setWebViewClient(new WebViewClient());

        String lanIp = getLanIpAddress();
        String ipText = lanIp == null ? "未知" : lanIp;

        // ALWAYS start with a clean server. We must never "reconnect to the
        // running server" from a previous host session: if that server survived
        // its exit-kill, reconnecting would load a room still full of the
        // previous session's peers - the "很多我自己的这台设备" bug (each enter
        // adds one more copy of this phone, because every WebView session's
        // peer stays connected on the surviving server). Kill any stale :node
        // process by every means available, WAIT for port 3000 to actually be
        // free, then start a brand-new server with an empty room.
        NodeService.killNodeProcess(this);
        try {
            stopService(new Intent(this, NodeService.class));
        } catch (Exception ignored) {
        }
        ServerLog.log(this, "host 模式进入：已尝试杀旧服务器");

        statusText.setText("正在清理旧服务器…");

        new Thread(() -> {
            // Give the old server (if any) time to die and release port 3000,
            // so the "server up" poll below cannot latch onto a stale server
            // still answering. killNodeProcess + stopService above have already
            // SIGKILLed the old process, so the port frees in milliseconds; a
            // fast local bind probe is enough. NodeService additionally waits
            // for the port itself (waitForPortFree) before launching node, so
            // there is no EADDRINUSE risk even if we raced.
            long cleanDeadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < cleanDeadline && !isPortFree()) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                }
            }
            ServerLog.log(MainActivity.this, "旧服务器清理结束，端口已释放，开始启动新服务器");

            runOnUiThread(() -> {
                if (isFinishing()) return;  // user left during cleanup
                statusText.setText("服务器启动中…\n局域网地址：\nhttp://" + ipText + ":" + SERVER_PORT);

                Intent svc = new Intent(this, NodeService.class);
                svc.setAction(NodeService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }

                // Wait until the new server is listening, then load the UI.
                new Thread(() -> {
                    long deadline = System.currentTimeMillis() + 30_000;
                    while (System.currentTimeMillis() < deadline) {
                        if (isServerUp()) break;
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    runOnUiThread(() -> {
                        if (isFinishing()) return;  // user left before the server came up
                        statusText.setText("局域网地址：\nhttp://" + ipText + ":" + SERVER_PORT);
                        webView.loadUrl(serverBase());
                    });
                }, "server-wait").start();
            });
        }, "server-clean-wait").start();
    }

    /** Client mode: join another server (e.g. a second phone running this app). */
    private void setupClient(String targetUrl) {
        final String target = targetUrl == null ? "" : targetUrl;
        statusText.setText("正在连接：\n" + target);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                statusText.setText("已连接：\n" + url);
                // Remember the server so the launch screen offers it as a quick
                // re-connect entry in the "recently connected" list.
                DeviceHistory.add(MainActivity.this, targetUrl);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    statusText.setText("✗ 无法连接 " + target
                            + "\n请检查地址后重试（按返回键重新选择）");
                }
            }
        });

        webView.loadUrl(target);
    }

    /** Extract a sensible filename from the download request. */
    private String downloadName(String contentDisposition, String fallback) {
        if (contentDisposition != null) {
            int idx = contentDisposition.indexOf("filename=");
            if (idx >= 0) {
                String name = contentDisposition.substring(idx + "filename=".length());
                if (name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
                if (!name.isEmpty()) return name;
            }
        }
        // Fall back to something derived from the URL.
        String f = fallback.replace("blob:", "");
        return f.isEmpty() ? ("lan-projects-" + System.currentTimeMillis()) : f;
    }

    /**
     * Fetch a blob: URL inside the page, stream it to LanProjectsBridge in
     * base64 chunks, which writes it to the phone's Downloads.
     */
    private void downloadBlob(String blobUrl, String filename, String mime) {
        String safeFilename = escapeJs(filename);
        String safeMime = escapeJs(mime != null ? mime : "application/octet-stream");
        // Save via the local SaveFileServer: fetch the blob, then POST it as a
        // RAW BINARY HTTP body (no base64, no JS bridge). The server streams it
        // straight to Downloads - near-native speed like a desktop browser.
        // The old path base64-encoded the blob through the addJavascriptInterface
        // bridge in 256 KB chunks: 1 GB became 1.33 GB of base64 crossing a slow
        // JNI bridge (~4000 calls) and took ~20 s on a weak phone.
        String js = "fetch('" + blobUrl + "').then(r=>r.blob()).then(b=>{"
                + "return fetch('http://127.0.0.1:3900/save?name='+encodeURIComponent('" + safeFilename + "')"
                + "+'&mime='+encodeURIComponent('" + safeMime + "')+'&size='+b.size,{"
                + "method:'POST',body:b});"
                + "}).then(r=>{"
                + "if(r.ok){window.LanProjectsBridge.saveDone('" + safeFilename + "');}"
                + "else{return r.text().then(t=>window.LanProjectsBridge.failBlobSave('保存失败: '+t));}"
                + "}).catch(e=>window.LanProjectsBridge.failBlobSave(String(e)));";
        webView.evaluateJavascript(js, null);
    }

    private String escapeJs(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private boolean isServerUp() {
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", SERVER_PORT), 300);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True if nothing is listening on port 3000 yet (fast local bind probe,
     * used during host-mode startup cleanup so the UI never latches onto a
     * stale server's room). Mirrors NodeService.portFree().
     */
    private boolean isPortFree() {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(SERVER_PORT, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            return true; // bound successfully -> port was free
        } catch (Exception e) {
            return false; // port in use
        }
    }

    /**
     * The device's LAN IPv4 address, or null if not connected. Prefers a WiFi or
     * Ethernet interface (wlan, eth, en). On phones with several active
     * interfaces (WiFi + mobile data + USB tethering) the naive "first IPv4"
     * version returned whichever the OS enumerated first, so the launch screen
     * could show a different address each time - and the user then connected to
     * a different IP every session, which filled "最近连接" with one entry per IP.
     * Cellular (rmnet/wwan/ccmni), USB tethering (rndis) and VPN (tun) addresses
     * are never on the LAN the user shares files over, so they are skipped.
     */
    static String getLanIpAddress() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase();
                boolean lanLike = name.startsWith("wlan") || name.startsWith("eth")
                        || name.startsWith("en") || name.contains("wlan");
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    if (lanLike) return addr.getHostAddress();
                    if (fallback == null) fallback = addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /**
     * URL the host's own WebView loads. Use the LAN address (not loopback
     * 127.0.0.1) so this device joins the server's shared IP room alongside
     * LAN clients and derives the same encryption key; fall back to loopback
     * only when no LAN address is available.
     */
    private String serverBase() {
        String ip = getLanIpAddress();
        return "http://" + (ip == null ? "127.0.0.1" : ip) + ":" + SERVER_PORT + "/";
    }

    /**
     * The LAN server address this phone is currently using, shown as the QR.
     * host mode -> this phone IS the server (its own LAN address). client mode
     * -> the server this phone connected to (targetUrl). Using the ACTIVE
     * server keeps every device in the same session showing the SAME QR, so
     * scanning always lands on a real server instead of a client's own IP.
     */
    private String activeServerUrl() {
        if (MODE_CLIENT.equals(mode) && targetUrl != null && !targetUrl.isEmpty()) {
            return targetUrl;
        }
        String ip = getLanIpAddress();
        return ip == null ? null : "http://" + ip + ":3000/";
    }

    /** QR bitmap of the given URL, or null if it could not be generated. */
    static Bitmap qrBitmap(String url) {
        if (url == null) return null;
        try {
            return new BarcodeEncoder().encodeBitmap(url, BarcodeFormat.QR_CODE, 480, 480);
        } catch (Exception e) {
            return null;
        }
    }

    /** Show the active server's address as a QR code others can scan. */
    private void showQrDialog() {
        String url = activeServerUrl();
        Bitmap qr = qrBitmap(url);
        if (qr == null) {
            android.widget.Toast.makeText(this, "未检测到局域网地址，请检查网络连接",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        ImageView iv = new ImageView(this);
        iv.setImageBitmap(qr);

        TextView tv = new TextView(this);
        tv.setText("地址: " + url + "\n对方用「扫码连接」扫这个码即可加入");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 16, 0, 0);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(24, 24, 24, 24);
        layout.addView(iv);
        layout.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("本机二维码")
                .setView(layout)
                .setPositiveButton("关闭", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        // Always return to the launch screen. The web UI is a single-screen app
        // (its views are overlays, no browser history), and the server 301-redirects
        // unknown routes, so navigating WebView history here caused reload loops
        // ("闪退到主页面").
        // The server is NOT re-added to history here: it is already remembered on
        // successful connection (onPageFinished), and adding it again on every back
        // press accumulated duplicate rows in "最近连接".
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        // Stop the local save server (its only job is receiving downloads).
        if (saveFileServer != null) {
            saveFileServer.stop();
            saveFileServer = null;
        }

        // Destroy the WebView EXPLICITLY before anything else. If the WebView
        // is not destroyed, its page keeps running and its ServerConnection
        // auto-reconnects to whatever server is next on port 3000 - so every
        // re-entry into host mode re-attaches the previous session's WebView as
        // an extra peer of this same phone ("很多我自己的这台设备", one copy per
        // leaked WebView). Killing the server alone is not enough, the WebView
        // must actually be torn down so it can never reconnect.
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }

        // The Android WebView persists received-file blobs to DISK under
        // app_webview/Default/blob_storage/ and never auto-cleans it (a desktop
        // browser keeps blobs in memory instead). Leaving that data in place
        // inflates the app's size by every transferred file, so wipe it as soon
        // as the transfer page closes. The WebView is destroyed above, so this
        // is safe; the directory is recreated on the next WebView.
        clearWebViewBlobStorage(this);

        super.onDestroy();
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        // Tear down the server for real. When the user leaves the host screen
        // (back key / top-bar back) this activity is finishing and the app is
        // transitioning to the background, so startService(ACTION_STOP) would
        // be rejected by Android 8+ background-start restrictions - the old
        // :node process survived, port 3000 stayed open, and re-entering host
        // mode reconnected to the still-running server with all its old peers
        // ("历史设备又全部被加载出来了"). stopService() is allowed in the
        // background and triggers NodeService.onDestroy(), which hard-kills the
        // :node process. killNodeProcess() is a direct SIGKILL backup that
        // reads the PID from a cross-process-safe file.
        if (MODE_HOST.equals(mode)) {
            ServerLog.log(this, "退出 host 传输页：正在杀掉服务器");
            try {
                stopService(new Intent(this, NodeService.class));
            } catch (Exception ignored) {
            }
            NodeService.killNodeProcess(this);
            ServerLog.log(this, "退出 host 传输页：服务器已处理");
        }
    }

    /** Delete the WebView's persisted blob_storage (received-file blobs). */
    public static void clearWebViewBlobStorage(android.content.Context ctx) {
        try {
            File blobDir = new File(ctx.getDataDir(), "app_webview/Default/blob_storage");
            if (blobDir.exists()) {
                deleteRecursively(blobDir);
                ServerLog.log(ctx, "已清理 WebView blob_storage");
            }
        } catch (Exception e) {
            ServerLog.log(ctx, "清理 blob_storage 失败: " + e);
        }
    }

    private static void deleteRecursively(File f) {
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

    /** SHA-256 fingerprint of the official release signing certificate. */
    private static final String OFFICIAL_SIGNATURE =
            "EA89B630D74212BC87A1C17FB2E0233DA8A14502E121479EC98FE2071171BCFF";

    /** True if the installed app is signed with the official release key. */
    public static boolean isOfficialBuild(android.content.Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] signers = info.signingInfo.getApkContentsSigners();
            if (signers == null || signers.length == 0) return false;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(signers[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02X", b));
            return OFFICIAL_SIGNATURE.equalsIgnoreCase(sb.toString());
        } catch (Exception e) {
            // Fail closed: an unknown signature state must not count as official.
            return false;
        }
    }
}
