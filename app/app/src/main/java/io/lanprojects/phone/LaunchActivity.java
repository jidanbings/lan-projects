package io.lanprojects.phone;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
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

import java.security.MessageDigest;
import java.util.List;

/**
 * First screen shown on launch. Two ways to use the app:
 *
 *  1. 用本设备作为启动  - this device hosts the server; others connect to it.
 *  2. 连接其他设备      - this device joins another server (e.g. a second phone
 *                         running the same app), so two phones can exchange files.
 */
public class LaunchActivity extends AppCompatActivity {

    /** Scan a connect QR code -> join that server as a client. */
    private final ActivityResultLauncher<ScanOptions> qrScanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                String contents = result.getContents();
                if (contents != null && !contents.isEmpty()) {
                    String target = normalizeTarget(contents);
                    if (target != null) {
                        startMain(MainActivity.MODE_CLIENT, target);
                    } else {
                        Toast.makeText(this, "二维码内容不是有效的地址", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    /**
     * SHA-256 fingerprint of the official release signing certificate (hex,
     * no colons). A build whose signing cert does not match this is a
     * repackaged / unofficial build and is refused at startup. Debug builds
     * skip the check (see BuildConfig.DEBUG in onCreate), so local
     * development keeps working.
     */
    private static final String OFFICIAL_SIGNATURE =
            "EA89B630D74212BC87A1C17FB2E0233DA8A14502E121479EC98FE2071171BCFF";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 正式签名校验：release 包必须是官方签名，否则拒绝运行。
        // debug 包（开发用）跳过校验，本地调试不受影响。
        if (!BuildConfig.DEBUG && !isOfficialBuild()) {
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

        TextView lanIp = findViewById(R.id.lanIp);
        String ip = MainActivity.getLanIpAddress();
        lanIp.setText(ip == null ? "未知" : ip);

        // Show the build version so it is obvious which APK is installed
        // (every update bumps it - useful when debugging stale builds).
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((android.widget.TextView) findViewById(R.id.versionText)).setText("v" + v);
        } catch (Exception ignored) {
        }

        Button btnHost = findViewById(R.id.btnHost);
        btnHost.setOnClickListener(v -> startMain(MainActivity.MODE_HOST, null));

        Button btnClient = findViewById(R.id.btnClient);
        btnClient.setOnClickListener(v -> promptForTarget());

        // 扫码连接: scan another device's connect QR and join its server.
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
        // Any time the launch screen is shown, forget all previously connected
        // devices - the home page should always start with a clean slate.
        DeviceHistory.clear(this);
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
        ServerLog.log(this, "首页已显示：已清历史、尝试杀掉服务器");
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
            row.setTextColor(Color.WHITE);
            row.setTextSize(14);
            row.setPadding(12, 12, 12, 12);
            row.setTextIsSelectable(false);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF37474F);
            bg.setCornerRadius(10);
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

    /** Ask for the other device's server address, then connect in client mode. */
    private void promptForTarget() {
        EditText input = new EditText(this);
        input.setHint("例如 192.168.1.8 或 192.168.1.8:3000");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle("连接其他设备")
                .setMessage("输入对方服务器地址（端口默认 3000）")
                .setView(input)
                .setPositiveButton("连接", (dialog, which) -> {
                    String target = normalizeTarget(input.getText().toString().trim());
                    if (target == null) {
                        Toast.makeText(this, "地址格式不正确", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startMain(MainActivity.MODE_CLIENT, target);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** Turn user input into a full http URL, or null if invalid. */
    private String normalizeTarget(String input) {
        if (input == null || input.isEmpty()) return null;
        String a = input.trim();
        a = a.replaceAll("^https?://", "");   // strip a scheme if one was typed
        if (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        // host or host:port - allow IPv4, hostnames and bracketed IPv6
        if (!a.matches("^[a-zA-Z0-9._:\\-\\[\\]]+$")) return null;
        if (!a.contains(":")) a = a + ":3000";   // default port
        return "http://" + a + "/";
    }

    /** True if the installed app is signed with the official release key. */
    private boolean isOfficialBuild() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] signers = info.signingInfo.getApkContentsSigners();
            if (signers == null || signers.length == 0) return false;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(signers[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02X", b));
            return OFFICIAL_SIGNATURE.equalsIgnoreCase(sb.toString());
        } catch (Exception e) {
            // Fail closed: an unknown signature state must not run.
            ServerLog.log(this, "签名校验异常: " + e);
            return false;
        }
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
