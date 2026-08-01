package io.lanprojects.phone;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Settings screen, opened from the 设置 button on the launch screen's top
 * right. Shows the version number, links to the built-in log viewer and to the
 * open-source GitHub repository, and an "about" dialog.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String GITHUB_URL = "https://github.com/jidanbings/lan-projects";

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
                    String notes = (fBody == null || fBody.isEmpty()) ? "" : ("\n\n" + fBody);
                    new AlertDialog.Builder(this)
                            .setTitle("发现新版本 v" + fLatest)
                            .setMessage("当前 v" + installed + notes)
                            .setPositiveButton("下载并安装", (d, w) -> downloadAndInstall(fApk))
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

    /** Download the release APK to app-specific Downloads, then install. */
    private void downloadAndInstall(String apkUrl) {
        if (apkUrl == null || apkUrl.isEmpty()) {
            android.widget.Toast.makeText(this, "未找到安装包下载地址", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.Toast.makeText(this, "开始下载安装包，请稍候…", android.widget.Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "");
                if (!dir.exists() && !dir.mkdirs()) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this, "下载失败：无法创建目录",
                            android.widget.Toast.LENGTH_SHORT).show());
                    return;
                }
                File apk = new File(dir, "lan-projects-update.apk");
                HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "lan-projects-android/update");
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                conn.disconnect();
                runOnUiThread(() -> installApk(apk));
            } catch (Exception e) {
                ServerLog.log(this, "下载更新失败: " + e);
                runOnUiThread(() -> android.widget.Toast.makeText(this, "下载失败，请检查网络后重试",
                        android.widget.Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** Install a downloaded APK (requests "unknown sources" permission first). */
    private void installApk(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要安装权限")
                    .setMessage("请允许安装未知来源应用，然后重新点击「下载并安装」")
                    .setPositiveButton("去设置", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "无法打开安装器: " + e, android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
