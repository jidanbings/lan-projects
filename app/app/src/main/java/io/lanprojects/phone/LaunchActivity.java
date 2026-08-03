package io.lanprojects.phone;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

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

    /** Last detected network state, used by the copy button and the toggles. */
    private NetworkStatus currentStatus;

    /** Requests the SSID permission (NEARBY_WIFI_DEVICES on 13+, location on 9-12). */
    private boolean wifiNamePermRequested = false;
    private final ActivityResultLauncher<String> wifiNamePermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> refreshNetwork());

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
                    String target = LanTargets.normalizeTarget(contents);
                    if (target != null) {
                        startMain(MainActivity.MODE_CLIENT, target);
                    } else {
                        Toast.makeText(this,
                                "无法识别的二维码：仅支持局域网服务器的 http 连接码 / 配对码 / 房间码",
                                Toast.LENGTH_SHORT).show();
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
        // keep dark icons over the light background.
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(true);
        insetsController.setAppearanceLightNavigationBars(true);

        View root = findViewById(R.id.launchRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // Show the current network type / name / IP (WiFi / mobile data /
        // hotspot) so the address can be opened directly in a browser or
        // forwarded to a friend as-is.
        refreshNetwork();

        // Pull down at the top of the home screen to re-read the network state
        // (the IP changes when switching WiFi networks). The custom
        // PullRefreshLayout drags the page down with the finger and shows a
        // spinner (a native SwipeRefreshLayout cannot be added - the build
        // machine is offline, so the behaviour is reimplemented).
        ((PullRefreshLayout) findViewById(R.id.launchRoot)).setOnRefresh(() -> {
            refreshNetwork();
            Toast.makeText(this, "已刷新网络状态", Toast.LENGTH_SHORT).show();
        });

        // Copy the full server URL (http://<ip>:3000) so it can be pasted into
        // a browser or sent to a friend on the LAN.
        findViewById(R.id.btnCopyIp).setOnClickListener(v -> {
            String ip = currentStatus == null ? null : currentStatus.ip;
            if (ip == null) {
                Toast.makeText(this, "未检测到局域网地址，请检查网络连接", Toast.LENGTH_SHORT).show();
                return;
            }
            String url = "http://" + ip + ":3000";
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

        // Network switches: flip WiFi / mobile data / personal hotspot. The
        // helpers try the direct toggle first; only when the system rejects it
        // do they open the matching panel (onResume then refreshes on return).
        findViewById(R.id.btnWifi).setOnClickListener(v -> {
            NetworkStatus s = NetworkStatus.detect(this);
            NetworkStatus.toggleWifi(this, !s.wifiOn);
            refreshNetworkLater();
        });
        findViewById(R.id.btnMobile).setOnClickListener(v -> {
            NetworkStatus s = NetworkStatus.detect(this);
            NetworkStatus.toggleMobileData(this, !s.mobileOn);
            refreshNetworkLater();
        });
        findViewById(R.id.btnHotspot).setOnClickListener(v -> {
            NetworkStatus s = NetworkStatus.detect(this);
            NetworkStatus.toggleHotspot(this, !s.hotspotOn);
            refreshNetworkLater();
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
        // Refresh network state: the user may have flipped the WiFi / data /
        // hotspot switches in the system panel while we were paused.
        refreshNetwork();
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

    /** Re-read the network type / name / IP and refresh the whole status block. */
    private void refreshNetwork() {
        currentStatus = NetworkStatus.detect(this);
        updateStatusCard(currentStatus);
        updateToggleButtons(currentStatus);
        // WiFi 网络名（SSID）需要额外权限：Android 13+ 用 NEARBY_WIFI_DEVICES，
        // Android 9-12 用位置权限；只在确实连着 WiFi 时才去申请。
        if (currentStatus.kind == NetworkStatus.Kind.WIFI) ensureWifiNamePermission();
    }

    /** Direct toggles change the state asynchronously; refresh once it settles. */
    private void refreshNetworkLater() {
        findViewById(R.id.launchRoot).postDelayed(this::refreshNetwork, 1500);
    }

    private void updateStatusCard(NetworkStatus s) {
        String conn;
        int color;
        switch (s.kind) {
            case WIFI:
                conn = s.name != null ? "已连接 WiFi「" + s.name + "」" : "已连接 WiFi";
                color = 0xFF4CAF50; // 连接状态用醒目的绿色
                break;
            case MOBILE:
                conn = "正在使用移动数据";
                color = 0xFF4CAF50;
                break;
            case HOTSPOT:
                conn = "已开启个人热点";
                color = 0xFF4CAF50;
                break;
            default:
                conn = "未连接网络";
                color = 0xFF78909C;
        }
        TextView tvConn = findViewById(R.id.connectionText);
        tvConn.setText(conn);
        tvConn.setTextColor(color);
        findViewById(R.id.statusDot).setBackgroundResource(
                s.kind == NetworkStatus.Kind.NONE
                        ? R.drawable.bg_dot_offline : R.drawable.bg_dot_online);
        ((TextView) findViewById(R.id.lanIp)).setText(
                s.ip == null ? "未知" : "http://" + s.ip + ":3000");

        // 启动按钮下方的红色提示，按当前网络类型告诉对方如何连上本机。
        TextView hostHint = findViewById(R.id.hostHint);
        switch (s.kind) {
            case WIFI:
                hostHint.setText("连接同一 WiFi 才能进行传输");
                break;
            case MOBILE:
            case HOTSPOT:
                hostHint.setText("需要连接本台设备的热点");
                break;
            default:
                hostHint.setText("本机当服务器，其他设备扫码加入");
        }
    }

    private void updateToggleButtons(NetworkStatus s) {
        setToggle(R.id.btnWifi, "WiFi", s.wifiOn);
        setToggle(R.id.btnMobile, "移动数据", s.mobileOn);
        setToggle(R.id.btnHotspot, "个人热点", s.hotspotOn);
    }

    /** 紧凑一行开关：图标在上，下方「WiFi / 已开启」；开启 = 绿色，关闭 = 深灰
        （浅色主题下不再用白色作关闭态——白字在浅灰背景上会看不见，改用深灰）。 */
    private void setToggle(int id, String label, boolean on) {
        Button b = findViewById(id);
        int stateColor = on ? 0xFF4CAF50 : 0xFF78909C; // 开启绿 / 关闭深灰
        String state = on ? "已开启" : "已关闭";
        String text = label + "\n" + state;
        SpannableString ss = new SpannableString(text);
        ss.setSpan(new ForegroundColorSpan(stateColor),
                label.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setText(ss);
        b.setCompoundDrawableTintList(ColorStateList.valueOf(stateColor));
    }

    private void ensureWifiNamePermission() {
        if (wifiNamePermRequested) return;
        String perm = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            wifiNamePermRequested = true;
            if (Build.VERSION.SDK_INT < 33) {
                Toast.makeText(this, "读取 WiFi 网络名需要位置权限（仅用于显示名称）",
                        Toast.LENGTH_LONG).show();
            }
            wifiNamePermLauncher.launch(perm);
        }
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
            row.setTextColor(0xFF263238);
            row.setTextSize(14);
            row.setPadding(12, 12, 12, 12);
            row.setTextIsSelectable(false);
            // Light card matching the launch screen's light theme.
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFFFFFFF);
            bg.setCornerRadius(12);
            bg.setStroke(1, 0xFFD9DEE3);
            row.setBackground(bg);

            row.setOnClickListener(v -> {
                // History may predate the strict scan validation and hold a
                // malicious URL; re-validate before loading, drop it if invalid.
                String valid = LanTargets.normalizeTarget(url);
                if (valid == null) {
                    DeviceHistory.remove(this, url);
                    Toast.makeText(this, "已移除无效的历史记录", Toast.LENGTH_SHORT).show();
                    refreshRecent();
                    return;
                }
                startMain(MainActivity.MODE_CLIENT, valid);
            });
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
