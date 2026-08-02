package io.lanprojects.phone;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.List;

/**
 * First screen shown on launch:
 *
 *  1. 用本设备作为启动 - this device hosts the server; others connect to it.
 *  2. 扫码连接         - scan a connect / pairing / room QR to join a server,
 *                        pair with a device, or enter a public room.
 */
public class LaunchActivity extends AppCompatActivity {

    /**
     * Scan a QR code -> join its server as a client. The scanned content may be
     * a plain connect QR (http://host:port/) or a pairing/room QR carrying
     * ?pair_key= / ?room_id= - both are loaded as URLs and the web UI's
     * evaluateUrlParams acts on any query param.
     */
    private final ActivityResultLauncher<ScanOptions> qrScanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                String contents = result.getContents();
                if (contents != null && !contents.isEmpty()) {
                    String target = normalizeTarget(contents);
                    if (target != null) {
                        startMain(MainActivity.MODE_CLIENT, target);
                    } else {
                        Toast.makeText(this, "无法识别的二维码", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 正式签名校验：release 包必须是官方签名，否则拒绝运行。
        // debug 包（开发用）跳过校验，本地调试不受影响。
        if (!BuildConfig.DEBUG && !MainActivity.isOfficialBuild(this)) {
            ServerLog.log(this, "签名校验失败：非官方构建，拒绝启动");
            showNotOfficialAndExit();
            return;
        }

        setContentView(R.layout.activity_launch);

        // Same edge-to-edge handling as MainActivity: extend into the cutout and
        // keep white icons over the dark background.
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);

        View root = findViewById(R.id.launchRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // Show the full server URL (not the bare IP) so the address can be
        // opened directly in a browser or forwarded to a friend as-is.
        refreshIpStatus();

        // Pull down at the top of the home screen to re-read the LAN address
        // (the IP changes when switching WiFi networks). The custom
        // PullRefreshLayout drags the page down with the finger and shows a
        // spinner (a native SwipeRefreshLayout cannot be added - the build
        // machine is offline, so the behaviour is reimplemented).
        ((PullRefreshLayout) findViewById(R.id.launchRoot)).setOnRefresh(() -> {
            refreshIpStatus();
            Toast.makeText(this, "已刷新局域网地址", Toast.LENGTH_SHORT).show();
        });

        // Copy the full server URL (http://<ip>:3000) so it can be pasted into
        // a browser or sent to a friend on the LAN.
        findViewById(R.id.btnCopyIp).setOnClickListener(v -> {
            String lan = MainActivity.getLanIpAddress();
            if (lan == null) {
                Toast.makeText(this, "未检测到局域网地址，请检查网络连接", Toast.LENGTH_SHORT).show();
                return;
            }
            String url = "http://" + lan + ":3000";
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("lan-projects 局域网地址", url));
            Toast.makeText(this, "已复制：" + url, Toast.LENGTH_SHORT).show();
        });

        // Show the build version so it is obvious which APK is installed
        // (every update bumps it - useful when debugging stale builds).
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((android.widget.TextView) findViewById(R.id.versionText)).setText("v" + v);
        } catch (Exception ignored) {
        }

        Button btnHost = findViewById(R.id.btnHost);
        btnHost.setOnClickListener(v -> startMain(MainActivity.MODE_HOST, null));

        // 扫码连接: scan a connect / pairing / room QR and join its server.
        findViewById(R.id.btnScan).setOnClickListener(v -> {
            ScanOptions options = new ScanOptions();
            options.setPrompt("将对方二维码放入取景框");
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setBeepEnabled(false);
            options.setCaptureActivity(ScanActivity.class);
            qrScanLauncher.launch(options);
        });


        findViewById(R.id.recentClear).setOnClickListener(v -> {
            DeviceHistory.clear(this);
            refreshRecent();
        });

        // Top-right corner: settings (version, log viewer, about, GitHub).
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        refreshRecent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Show the persisted "recently connected" servers (managed via the
        // 清空 button). The list survives returning from a transfer session.
        refreshRecent();

        // Guarantee "on the home page = all connections cut": kill the node
        // server process directly (the equivalent of swiping the app away).
        // killNodeProcess is a no-op if no server is (or was) running, so it is
        // safe on every launch-screen visit. stopService(NodeService) is a
        // belt-and-suspenders second path: it is allowed in the background and
        // makes the :node process kill itself in onDestroy, covering the case
        // where the PID file is missing/stale.
        NodeService.killNodeProcess(this);
        try {
            stopService(new Intent(this, NodeService.class));
        } catch (Exception ignored) {
        }
        // Belt-and-suspenders: MainActivity.onDestroy already cleans the WebView's
        // persisted blob_storage on closing the transfer page; clean it here too
        // in case the transfer activity was killed by the system (e.g. low memory)
        // without a clean onDestroy.
        MainActivity.clearWebViewBlobStorage(this);
        ServerLog.log(this, "首页已显示：已清历史、尝试杀掉服务器");
    }

    /** Re-read the LAN address and refresh the status card (URL + dot + label). */
    private void refreshIpStatus() {
        String ip = MainActivity.getLanIpAddress();
        ((TextView) findViewById(R.id.lanIp)).setText(
                ip == null ? "未知" : "http://" + ip + ":3000");
        View statusDot = findViewById(R.id.statusDot);
        statusDot.setBackgroundResource(
                ip == null ? R.drawable.bg_dot_offline : R.drawable.bg_dot_online);
        ((TextView) findViewById(R.id.connectionText)).setText(
                ip == null ? "未连接网络" : "局域网已连接");
    }

    /** Show the list of previously connected servers (tap to reconnect). */
    private void refreshRecent() {
        LinearLayout container = findViewById(R.id.recentList);
        TextView hint = findViewById(R.id.recentHint);
        container.removeAllViews();

        List<String> history = DeviceHistory.getAll(this);
        if (history.isEmpty()) {
            hint.setVisibility(View.VISIBLE);
            return;
        }
        hint.setVisibility(View.GONE);

        for (final String url : history) {
            TextView row = new TextView(this);
            row.setText(url);
            row.setTextColor(0xFFECEFF1);
            row.setTextSize(14);
            row.setPadding(12, 12, 12, 12);
            row.setTextIsSelectable(false);
            // Dark card matching the launch screen's theme.
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x1AFFFFFF);
            bg.setCornerRadius(12);
            bg.setStroke(1, 0x22FFFFFF);
            row.setBackground(bg);

            row.setOnClickListener(v -> startMain(MainActivity.MODE_CLIENT, url));
            row.setOnLongClickListener(v -> {
                DeviceHistory.remove(this, url);
                refreshRecent();
                Toast.makeText(this, "已从列表移除", Toast.LENGTH_SHORT).show();
                return true;
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 8;
            container.addView(row, lp);
        }
    }

    private void startMain(String mode, String target) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra(MainActivity.EXTRA_MODE, mode);
        if (target != null) i.putExtra(MainActivity.EXTRA_TARGET, target);
        startActivity(i);
    }

    /** Turn a scanned value into a URL to load, or null if invalid. */
    private String normalizeTarget(String input) {
        if (input == null || input.isEmpty()) return null;
        String a = input.trim();
        // A scanned QR may be a full URL carrying ?pair_key= or ?room_id=
        // (pairing code / public-room code). Keep such URLs intact so the web
        // UI's evaluateUrlParams can act on the param. A plain connect QR is
        // just http://host:port/ and loads normally.
        if (a.matches("^https?://.*")) return a;
        // Otherwise a bare host[:port] address (typed manually) -> build URL.
        if (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        // host or host:port - allow IPv4, hostnames and bracketed IPv6
        if (!a.matches("^[a-zA-Z0-9._:\\-\\[\\]]+$")) return null;
        if (!a.contains(":")) a = a + ":3000";   // default port
        return "http://" + a + "/";
    }

    /** Block the app and tell the user this is not an official build. */
    private void showNotOfficialAndExit() {
        new AlertDialog.Builder(this)
                .setTitle("非官方构建")
                .setMessage("当前安装的不是官方发布的版本（签名不符），已拒绝运行。\n"
                        + "请卸载后从官方渠道重新安装。")
                .setCancelable(false)
                .setPositiveButton("退出", (d, w) -> {
                    finish();
                    android.os.Process.killProcess(android.os.Process.myPid());
                })
                .show();
    }
}
