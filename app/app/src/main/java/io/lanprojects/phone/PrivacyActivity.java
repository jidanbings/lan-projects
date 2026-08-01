package io.lanprojects.phone;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;

/**
 * "隐私与 SDK" page: renders a bundled, detailed privacy policy plus an
 * inventory of every SDK / open-source component the app ships (permission
 * purposes, what data stays on-device, licenses). Unlike the About page this
 * content is static and offline so it always renders even without network.
 */
public class PrivacyActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy);

        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.privacyRoot), (v, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });

        findViewById(R.id.btnPrivacyBack).setOnClickListener(v -> finish());

        TextView tv = findViewById(R.id.privacyContent);
        markwon().setMarkdown(tv, PRIVACY_MARKDOWN);
    }

    /** Markwon configured with tables + strikethrough; links open in browser. */
    private Markwon markwon() {
        return Markwon.builder(this)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(this))
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureConfiguration(MarkwonConfiguration.Builder builder) {
                        builder.linkResolver((view, link) -> {
                            if (link != null
                                    && (link.startsWith("http://") || link.startsWith("https://"))) {
                                try {
                                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                                } catch (Exception ignored) {
                                }
                            }
                        });
                    }
                })
                .build();
    }

    private static final String PRIVACY_MARKDOWN = "" +
            "# 隐私政策\n" +
            "\n" +
            "## 一、数据不会离开你的局域网\n" +
            "\n" +
            "lan-projects 的**所有文件传输都在局域网内完成**：一台设备作为服务器，其他设备通过局域网 IP 连接，文件在设备之间直接传输（或经内置服务器中继）。App **不会**把任何文件、日志或个人数据上传到云端，不收集统计 / 广告 / 用户行为数据，也不消耗互联网流量。\n" +
            "\n" +
            "- 内置服务器只监听局域网地址（10.x / 172.16-31.x / 192.168.x），**不监听公网接口**；\n" +
            "- 传输内容使用 **ChaCha20 端到端加密**，只有已配对（扫码或 6 位配对码）的设备持有密钥，未配对的设备即使截获数据也无法解密；\n" +
            "- 配对密钥、房间密钥只保存在设备本地（WebView 存储），不会同步到任何账号或云端。\n" +
            "\n" +
            "## 二、本机存储了哪些数据\n" +
            "\n" +
            "| 数据 | 存储位置 | 用途 | 是否上传 |\n" +
            "| --- | --- | --- | --- |\n" +
            "| 服务器运行日志 | 应用私有目录 server.log | 排障（「设置 → 查看日志」可查看 / 清空） | 否 |\n" +
            "| 最近连接记录 | 本机设置 | 首页快速重新连接 | 否 |\n" +
            "| 配对 / 房间密钥 | WebView 本地存储 | 端到端加密 | 否 |\n" +
            "| 接收的文件 | 系统「下载」目录 | 文件传输 | 否 |\n" +
            "\n" +
            "- 日志只保留**最近 100 条**，更早的自动删除；\n" +
            "- 卸载 App 即清除以上全部本地数据。\n" +
            "\n" +
            "## 三、权限用途说明\n" +
            "\n" +
            "| 权限 | 用途 |\n" +
            "| --- | --- |\n" +
            "| 网络 / 网络状态 / WiFi 状态 | 建立局域网连接、获取局域网 IP、检测网络 |\n" +
            "| 前台服务（含 dataSync） | 在后台运行文件服务器、后台下载更新包 |\n" +
            "| 通知 | 显示服务器运行状态、更新下载进度（暂停 / 取消） |\n" +
            "| 相机 | 扫描「扫码连接」二维码（不保存拍摄内容） |\n" +
            "| 安装未知应用 | 安装「检查更新」下载的官方 APK |\n" +
            "\n" +
            "## 四、何时会访问公网\n" +
            "\n" +
            "仅在以下两种**你主动触发**的情况下访问互联网（均不包含个人数据）：\n" +
            "\n" +
            "1. 「检查更新」→ 查询 GitHub Releases API；\n" +
            "2. 「关于本软件」→ 加载 GitHub README 文档。\n" +
            "\n" +
            "除此之外，App 运行时不依赖任何公网服务。\n" +
            "\n" +
            "---\n" +
            "\n" +
            "# SDK 与开源组件\n" +
            "\n" +
            "## 平台与运行时\n" +
            "\n" +
            "| 组件 | 版本 | 许可证 | 用途 |\n" +
            "| --- | --- | --- | --- |\n" +
            "| Android SDK | compileSdk 35 / targetSdk 35 / minSdk 28 | Android | 系统平台（Android 9+） |\n" +
            "| Android System WebView | 系统自带 | Chromium | 渲染界面并运行 Web 前端 |\n" +
            "| nodejs-mobile | Node.js 18.x（arm64） | MIT | 内置运行时，仅作本地局域网服务器 |\n" +
            "\n" +
            "## Android 依赖\n" +
            "\n" +
            "| 组件 | 版本 | 许可证 | 用途 |\n" +
            "| --- | --- | --- | --- |\n" +
            "| AndroidX AppCompat | 1.7.0 | Apache-2.0 | 界面与兼容层 |\n" +
            "| ZXing（zxing-android-embedded） | 4.3.0 | Apache-2.0 | 二维码扫描 / 生成 |\n" +
            "| Markwon | 4.6.2 | Apache-2.0 | Markdown 渲染（更新说明、关于、本页） |\n" +
            "\n" +
            "## 内置 Node.js 服务器依赖（npm）\n" +
            "\n" +
            "| 组件 | 版本 | 许可证 | 用途 |\n" +
            "| --- | --- | --- | --- |\n" +
            "| express | 4.18.2 | MIT | HTTP 静态服务 |\n" +
            "| ws | 8.16.0 | MIT | WebSocket 中继传输 |\n" +
            "| ua-parser-js | 1.0.37 | MIT | 设备名识别 |\n" +
            "| unique-names-generator | 4.3.0 | MIT | 生成随机设备昵称 |\n" +
            "| express-rate-limit | 7.1.5 | MIT | 服务器防滥用限流 |\n" +
            "\n" +
            "## 前端脚本库\n" +
            "\n" +
            "| 组件 | 许可证 | 用途 |\n" +
            "| --- | --- | --- |\n" +
            "| noble-ciphers（ChaCha20） | MIT | 端到端加密 |\n" +
            "| zip.js | BSD-3-Clause | 多文件打包 zip 下载 |\n" +
            "| qrcode-svg | MIT | 首页二维码生成 |\n" +
            "| NoSleep | MIT | 传输时保持屏幕常亮 |\n" +
            "| heic2any | MIT | HEIC 图片预览转换 |\n" +
            "\n" +
            "以上所有组件均**不包含**统计、广告、云上报或第三方跟踪代码。\n" +
            "\n" +
            "> 本项目为开源软件，完整源码与许可证文本见 GitHub：https://github.com/jidanbings/lan-projects";
}
