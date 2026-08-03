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
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings screen, opened from the 设置 button on the launch screen's top
 * right. Shows the version number, links to the built-in log viewer and to the
 * open-source GitHub repository, and an "about" dialog.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String GITHUB_URL = "https://github.com/jidanbings/lan-projects";

    // 更新检查的多个数据源：先直连 GitHub API（海外/可翻墙用户最优，能拿到完整
    // 更新说明），失败则退回国内可达的加速代理（转发 /releases/latest 页面，从中
    // 解析最新 tag 版本号）。加速代理是第三方公共服务，域名可能变动，故用列表依次尝试。
    private static final String[] UPDATE_CHECK_SOURCES = {
            "https://api.github.com/repos/jidanbings/lan-projects/releases/latest",
            "https://ghfast.top/https://github.com/jidanbings/lan-projects/releases/latest",
            "https://gh.ddlc.top/https://github.com/jidanbings/lan-projects/releases/latest",
            "https://gh-proxy.com/https://github.com/jidanbings/lan-projects/releases/latest",
            "https://ghproxy.net/https://github.com/jidanbings/lan-projects/releases/latest",
    };

    // APK 下载的加速代理前缀（配合 GitHub 直连，构建多条下载地址依次尝试）。
    private static final String[] DOWNLOAD_PROXIES = {
            "https://ghfast.top/",
            "https://gh.ddlc.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
    };

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
        insetsController.setAppearanceLightStatusBars(true);
        insetsController.setAppearanceLightNavigationBars(true);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.settingsRoot), (v, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // Version shown at the bottom of the settings page.
        TextView versionFooter = findViewById(R.id.versionFooter);
        try {
            versionFooter.setText("lan-projects v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception e) {
            versionFooter.setText("lan-projects v?");
        }

        // GitHub icon below the version -> open the repository.
        findViewById(R.id.githubFooter).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "无法打开浏览器", android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.rowSpeed).setOnClickListener(v ->
                startActivity(new Intent(this, SpeedTestActivity.class)));

        findViewById(R.id.rowLogs).setOnClickListener(v ->
                startActivity(new Intent(this, LogViewerActivity.class)));

        findViewById(R.id.rowAbout).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));

        findViewById(R.id.rowPrivacy).setOnClickListener(v ->
                startActivity(new Intent(this, PrivacyActivity.class)));

        findViewById(R.id.rowUpdate).setOnClickListener(v -> checkForUpdate());
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
            // 依次尝试多个更新源（GitHub 直连 → 加速代理页面），取第一个能拿到版本的。
            UpdateCheckResult result = null;
            for (String src : UPDATE_CHECK_SOURCES) {
                result = checkFromUrl(src);
                if (result != null) break;
            }
            final UpdateCheckResult fResult = result;
            runOnUiThread(() -> {
                if (fResult == null) {
                    status.setText("");
                    android.widget.Toast.makeText(this, "检查更新失败：无法连接 GitHub，请稍后重试",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                String installed = currentVersion();
                if (compareVersions(installed, fResult.latest) >= 0) {
                    status.setText("");
                    android.widget.Toast.makeText(this, "已是最新版本 v" + installed,
                            android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    status.setText("v" + fResult.latest);
                    // Render the release notes (GitHub markdown) in a scrollable view.
                    String notes = "**当前版本：v" + installed + "**\n\n"
                            + (fResult.body == null || fResult.body.isEmpty() ? "" : fResult.body);
                    new AlertDialog.Builder(this)
                            .setTitle("发现新版本 v" + fResult.latest)
                            .setView(buildUpdateNotesView(notes))
                            .setPositiveButton("下载并安装", (d, w) -> downloadAndInstall(fResult.apkUrl, fResult.latest))
                            .setNegativeButton("以后再说", null)
                            .show();
                }
            });
        }).start();
    }

    /** 单个更新源的结果：最新版本号、APK 下载地址、更新说明（可能为空）。 */
    private static class UpdateCheckResult {
        final String latest;
        final String apkUrl;
        final String body;

        UpdateCheckResult(String latest, String apkUrl, String body) {
            this.latest = latest;
            this.apkUrl = apkUrl;
            this.body = body;
        }
    }

    /** 从某个更新源拉取最新版本信息；源不可用或解析失败时返回 null。 */
    private UpdateCheckResult checkFromUrl(String source) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(source).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "lan-projects-android/update-check");
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return null;
            }
            String finalUrl = conn.getURL().toString();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (InputStream is = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                    if (baos.size() > 512 * 1024) break;   // 页面源只需解析出版本号，不用读完
                }
            }
            conn.disconnect();
            String content = baos.toString("UTF-8");

            String tag = null, body = "";
            if (content.trim().startsWith("{")) {          // GitHub API 的 JSON 响应
                try {
                    JSONObject obj = new JSONObject(content);
                    String t = obj.optString("tag_name", "");
                    if (!t.isEmpty()) tag = t;
                    body = obj.optString("body", "");
                } catch (Exception ignored) { }
            }
            if (tag == null || tag.isEmpty()) {            // 加速代理页面：从重定向地址 / HTML 里找 tag
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("releases/tag/v?(\\d+\\.\\d+\\.\\d+)")
                        .matcher(finalUrl + " " + content);
                if (m.find()) tag = "v" + m.group(1);
            }
            if (tag == null || tag.isEmpty()) return null;
            String version = tag.replaceFirst("^v", "");
            // 本项目的 APK 命名与 tag 固定对应，可确定性地构造直链：
            //  https://github.com/jidanbings/lan-projects/releases/download/v{ver}/lan-projects-v{ver}-release.apk
            String base = "https://github.com/jidanbings/lan-projects/releases/download/v" + version
                    + "/lan-projects-v" + version + "-release.apk";
            return new UpdateCheckResult(version, base, body);
        } catch (Exception e) {
            ServerLog.log(this, "检查更新源失败 " + source + ": " + e);
            return null;
        }
    }

    /** 由直链构造加速下载地址列表（加速代理在前，GitHub 直连兜底）。 */
    private List<String> downloadUrls(String baseUrl) {
        List<String> list = new ArrayList<>();
        for (String proxy : DOWNLOAD_PROXIES) list.add(proxy + baseUrl);
        list.add(baseUrl);
        return list;
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
        i.putStringArrayListExtra(UpdateDownloadService.EXTRA_URLS,
                new ArrayList<>(downloadUrls(pendingUpdateUrl)));
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
        // Same rich markdown (tables, strikethrough, clickable links) as the
        // About / Privacy pages so GitHub release notes render correctly.
        MarkdownRenderer.create(this).setMarkdown(tv, markdown);
        scroll.addView(tv);
        // Cap the dialog height so a long changelog doesn't push the buttons off
        // screen; short notes still wrap to their natural height.
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        scroll.addOnLayoutChangeListener((v, l, t, r, b, oL, oT, oR, oB) -> {
            if (b - t > maxHeight) {
                v.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));
            }
        });
        return scroll;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
