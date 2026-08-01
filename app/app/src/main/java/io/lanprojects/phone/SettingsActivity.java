package io.lanprojects.phone;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import io.noties.markwon.Markwon;

/**
 * Settings screen, opened from the 设置 button on the launch screen's top
 * right. Shows the version number, links to the built-in log viewer and to the
 * open-source GitHub repository, and an "about" dialog.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String GITHUB_URL = "https://github.com/jidanbings/lan-projects";

    // Pending update download, started after notification permission is granted.
    private String pendingUpdateUrl;
    private String pendingUpdateVersion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.settingsRoot), (v, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        TextView versionValue = findViewById(R.id.versionValue);
        try {
            versionValue.setText("v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception e) {
            versionValue.setText("v?");
        }

        findViewById(R.id.rowSpeed).setOnClickListener(v ->
                startActivity(new Intent(this, SpeedTestActivity.class)));

        findViewById(R.id.rowLogs).setOnClickListener(v ->
                startActivity(new Intent(this, LogViewerActivity.class)));

        findViewById(R.id.rowAbout).setOnClickListener(v -> showAbout());

        findViewById(R.id.rowPrivacy).setOnClickListener(v -> showPrivacy());

        findViewById(R.id.rowGithub).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "无法打开浏览器", android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.rowUpdate).setOnClickListener(v -> checkForUpdate());
    }

    private void showAbout() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle("关于 lan-projects")
                .setMessage("lan-projects v" + version + "\n\n"
                        + "局域网文件共享 · 端对端加密\n\n"
                        + "本机做服务器，或连接其他设备，在局域网内高速互传文件，"
                        + "不消耗互联网流量。\n\n"
                        + "开源项目：github.com/jidanbings/lan-projects")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showPrivacy() {
        new AlertDialog.Builder(this)
                .setTitle("隐私与 SDK")
                .setMessage("【数据是否上传云端】\n"
                        + "❌ 不会。所有文件传输都在局域网内完成（WebSocket 中继或 WebRTC 直连），"
                        + "不经过任何云端服务器，也不消耗互联网流量。App 内嵌的服务器只监听本机局域网地址，"
                        + "代码中没有任何向公网上传数据的请求。\n\n"
                        + "【使用的 SDK / 组件】\n"
                        + "· AndroidX AppCompat（界面）\n"
                        + "· ZXing（二维码扫码 / 生成）\n"
                        + "· 内置 Node.js 运行时（nodejs-mobile 18.20，仅用于局域网服务器）\n"
                        + "· npm 包：express、ws、ua-parser-js、unique-names-generator、express-rate-limit 等"
                        + "（均为服务器依赖，不包含任何统计 / 广告 / 云上报 SDK）\n\n"
                        + "传输数据均进行端对端加密，未配对者无法解密。")
                .setPositiveButton("知道了", null)
                .show();
    }

    /** Query GitHub for the latest release and prompt to update if newer. */
    private void checkForUpdate() {
        // Only OFFICIAL builds may check for updates. Debug builds skip the
        // startup signature gate, so without this guard a dev build could pull
        // the official APK (which then would not install over it anyway); a
        // re-signed release build is already blocked at LaunchActivity.
        if (!MainActivity.isOfficialBuild(this)) {
            android.widget.Toast.makeText(this, "非官方构建，不支持检查更新",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        TextView status = findViewById(R.id.updateStatus);
        status.setText("检查中…");
        new Thread(() -> {
            String latest = null, apkUrl = null, body = "";
            try {
                URL url = new URL("https://api.github.com/repos/jidanbings/lan-projects/releases/latest");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "lan-projects-android/update-check");
                if (conn.getResponseCode() == 200) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    try (InputStream is = conn.getInputStream()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    }
                    JSONObject obj = new JSONObject(baos.toString("UTF-8"));
                    String tag = obj.optString("tag_name", "");
                    if (!tag.isEmpty()) latest = tag.replaceFirst("^v", "");
                    body = obj.optString("body", "");
                    JSONArray assets = obj.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.optJSONObject(i);
                            if (a != null && a.optString("name", "").endsWith(".apk")) {
                                apkUrl = a.optString("browser_download_url", "");
                                if (!apkUrl.isEmpty()) break;
                            }
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                ServerLog.log(this, "检查更新失败: " + e);
            }

            final String fLatest = latest, fApk = apkUrl, fBody = body;
            runOnUiThread(() -> {
                if (fLatest == null) {
                    status.setText("");
                    android.widget.Toast.makeText(this, "检查更新失败：无法连接 GitHub，请稍后重试",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                String installed = currentVersion();
                if (compareVersions(installed, fLatest) >= 0) {
                    status.setText("");
                    android.widget.Toast.makeText(this, "已是最新版本 v" + installed,
                            android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    status.setText("v" + fLatest);
                    // Render the release notes (GitHub markdown) in a scrollable view.
                    String notes = "**当前版本：v" + installed + "**\n\n"
                            + (fBody == null || fBody.isEmpty() ? "" : fBody);
                    new AlertDialog.Builder(this)
                            .setTitle("发现新版本 v" + fLatest)
                            .setView(buildUpdateNotesView(notes))
                            .setPositiveButton("下载并安装", (d, w) -> downloadAndInstall(fApk, fLatest))
                            .setNegativeButton("以后再说", null)
                            .show();
                }
            });
        }).start();
    }

    private String currentVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    private int compareVersions(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Start the foreground download service (progress / pause / cancel). */
    private void downloadAndInstall(String apkUrl, String version) {
        if (apkUrl == null || apkUrl.isEmpty()) {
            android.widget.Toast.makeText(this, "未找到安装包下载地址", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        pendingUpdateUrl = apkUrl;
        pendingUpdateVersion = version;
        // Android 13+ needs POST_NOTIFICATIONS to show the progress notification.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
            return;
        }
        startDownloadService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 200) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startDownloadService();
            } else {
                android.widget.Toast.makeText(this, "需要通知权限才能显示下载进度", android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startDownloadService() {
        if (pendingUpdateUrl == null) return;
        Intent i = new Intent(this, UpdateDownloadService.class);
        i.putExtra(UpdateDownloadService.EXTRA_URL, pendingUpdateUrl);
        i.putExtra(UpdateDownloadService.EXTRA_VERSION, pendingUpdateVersion == null ? "" : pendingUpdateVersion);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        android.widget.Toast.makeText(this, "已开始下载，通知栏可查看进度 / 暂停 / 取消",
                android.widget.Toast.LENGTH_LONG).show();
    }

    /** Render the markdown release notes in a scrollable dialog view. */
    private View buildUpdateNotesView(String markdown) {
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        int pad = dp(20);
        tv.setPadding(pad, dp(16), pad, dp(16));
        tv.setTextSize(14);
        tv.setTextIsSelectable(true);
        Markwon.create(this).setMarkdown(tv, markdown);
        scroll.addView(tv);
        return scroll;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
