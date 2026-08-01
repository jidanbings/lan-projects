package io.lanprojects.phone;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

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
}
