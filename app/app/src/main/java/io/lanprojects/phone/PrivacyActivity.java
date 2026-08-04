package io.lanprojects.phone;

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
        insetsController.setAppearanceLightStatusBars(true);
        insetsController.setAppearanceLightNavigationBars(true);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.privacyRoot), (v, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });

        findViewById(R.id.btnPrivacyBack).setOnClickListener(v -> finish());

        TextView tv = findViewById(R.id.privacyContent);
        MarkdownRenderer.render(this, tv, PRIVACY_MARKDOWN);
    }

    private static final String PRIVACY_MARKDOWN = "" +
            "# 隐私政策\n" +
            "\n" +
            "## 一、数据不会离开你的局域网\n" +
            "\n" +
            "lan-projects 的**所有文件传输都在局域网内完成**：一台设备作为服务器，其他设备通过局域网 IP 连接，文件在设备之间直接传输（或经内置服务器中继）。App **不会**把任何文件、日志或个人数据上传到云端，不收集统计 / 广告 / 用户行为数据，日常文件传输不消耗互联网流量。\n" +
            "\n" +
            "- 内置服务器只监听局域网地址（10.x / 172.16-31.x / 192.168.x），**不监听公网接口**；\n" +
            "- 传输内容使用 **ChaCha20 端到端加密**：每个文件都会生成一个随机的 12 字节随机数（nonce），配合双方共有的密钥加密。密钥只通过**扫码**或 **6 位配对码**建立，只有已配对（或同房间）的设备持有，未配对的设备即使截获数据也无法解密；\n" +
            "- 配对密钥、房间密钥只保存在设备本地（WebView 存储），不会同步到任何账号或云端，App 卸载后一并清除；\n" +
            "- 接收文件时内容在内存中完成解密、按你的选择落盘——App 不会在本地留存明文副本之外的任何额外拷贝。\n" +
            "\n" +
            "## 二、我们不收集、不共享任何数据\n" +
            "\n" +
            "- **无账号、无注册、无设备标识**：App 不创建用户体系，不采集设备指纹、不追踪用户行为；\n" +
            "- **无任何第三方统计 / 广告 / 崩溃上报 SDK**：本页下方列出的全部内置组件均为功能组件，均不含埋点或跟踪代码；\n" +
            "- **不读取敏感信息**：不读取通讯录、短信、通话记录、相册（除非你主动选择要发送的相片）；不进行任何形式的定位（位置权限仅用于 Android 9-12 下读取 WiFi 网络名，见第四节）；\n" +
            "- **不与任何第三方共享数据**：传输文件只在你连接到的设备之间流转，App 开发者不通过任何渠道（包括服务器）接触你的文件或日志。\n" +
            "\n" +
            "## 三、本机存储了哪些数据\n" +
            "\n" +
            "| 数据 | 存储位置 | 用途 | 是否上传 |\n" +
            "| --- | --- | --- | --- |\n" +
            "| 服务器运行日志 server.log | 应用私有目录 | 排障（「设置 → 查看日志」可查看 / 清空） | 否 |\n" +
            "| 崩溃日志 crash.log | 应用外部私有目录 | 崩溃 / 异常排障（「设置 → 查看日志」可查看） | 否 |\n" +
            "| 最近连接记录 | 本机设置（SharedPreferences） | 首页快速重新连接 | 否 |\n" +
            "| 配对 / 房间密钥、房间信息 | WebView 本地存储 | 端到端加密、房间识别 | 否 |\n" +
            "| 服务器运行所需文件（内置 Web 代码、进程 PID 等） | 应用私有目录 | 运行本地服务器 | 否 |\n" +
            "| 更新安装包 lan-projects-update.apk | 应用私有下载目录 | 安装官方更新（新版本安装完成后自动删除） | 否 |\n" +
            "| 接收的文件 | 系统「下载」目录 | 文件传输 | 否 |\n" +
            "\n" +
            "- 日志只保留**最近 100 条**，更早的自动删除；crash.log 仅在你主动打开「查看日志」时被读取，其余时间不读取不上传；\n" +
            "- 首页「网络状态」识别的当前网络类型 / WiFi 网络名 / IP **只在内存中用于显示，不写入任何存储**；读取 WiFi 名需要系统权限（Android 13+ 附近 WiFi 设备、Android 9-12 位置），拒绝授权只是不显示名字，不影响传输；\n" +
            "- **卸载 App 即清除以上全部本地数据**（系统「下载」目录中已保存的文件由你自行管理）。\n" +
            "\n" +
            "## 四、权限用途说明\n" +
            "\n" +
            "| 权限 | 用途 |\n" +
            "| --- | --- |\n" +
            "| 网络 / 网络状态 / WiFi 状态 | 建立局域网连接、获取局域网 IP、检测网络是否可用 |\n" +
            "| 修改 WiFi 与网络状态 | 首页「网络开关」开启 / 关闭 WiFi、移动数据、个人热点（Android 10+ 多数系统禁止 App 直接切换，此时只打开对应系统面板，由你手动操作；Android 9 上可尽力直接切换） |\n" +
            "| 附近 WiFi 设备（Android 13+）/ 位置（Android 9-12） | 首页显示当前连接的 WiFi 网络名（仅用于识别网络，**不定位、不上传**；拒绝后只是不显示名字，不影响传输） |\n" +
            "| 前台服务（含 dataSync） | 在后台运行文件服务器、后台下载更新包 |\n" +
            "| 通知 | 显示服务器运行状态、更新下载进度（暂停 / 取消） |\n" +
            "| 相机 | 扫描「扫码连接」二维码（不保存拍摄内容） |\n" +
            "| 显示在其他应用上层（悬浮窗） | 选文件时保持屏幕常亮，防止息屏后热点掐断对方连接（详见下方说明） |\n" +
            "| 安装未知应用 | 安装「检查更新」下载的官方 APK |\n" +
            "\n" +
            "**关于「显示在其他应用上层」（悬浮窗权限）的详细说明**：\n" +
            "\n" +
            "- **为什么需要它**：点「选择发送文件」后，系统文件选择器会盖住本 App，App 自己的界面进入暂停态、自带的屏幕常亮设置随即失效。如果此时息屏，WiFi / 热点无线电可能进入省电，掐断两台手机之间的连接（表现就是对方显示未连接）。为了根治这个问题，App 在**选文件的这段时间里**挂一个 **1×1 像素的透明悬浮窗**，携带系统的「保持屏幕常亮」标志，让屏幕在选文件期间不熄灭——屏幕不熄，热点就不省电，连接就不会断；\n" +
            "\n" +
            "- **使用时机**：只在「选择发送文件」的界面存在期间使用（通常几秒到几分钟），选完或取消的瞬间立即移除；平时**不常驻、不显示任何悬浮内容**；\n" +
            "\n" +
            "- **它不读取、不收集任何东西**：这个权限只允许 App 在其他应用之上显示窗口。App 用它在屏幕角落里放了一个**完全透明、不可见、不拦截任何触控**的空白像素，不展示内容、不记录屏幕、不读取其他应用、不上传任何数据。它唯一的用途就是让屏幕保持常亮；\n" +
            "\n" +
            "- **如何开启 / 关闭**：首次点「选择发送文件」时 App 会弹一次说明，引导你到系统设置开启（只需一次）；之后随时可在 **「设置 → 已授权的权限」** 中查看并开关；\n" +
            "\n" +
            "- **拒绝授权的影响**：只是失去「选文件时不熄屏」的防断连优化。前端仍有 5 秒自动重连兜底，文件传输本身完全不受影响；\n" +
            "\n" +
            "- **特别注意**：授予该权限**不代表** App 会弹出任何广告悬浮窗——本 App 无广告、无任何常驻悬浮内容，请放心。\n" +
            "\n" +
            "## 五、何时会访问公网\n" +
            "\n" +
            "仅在以下**你主动触发**的情况下访问互联网（请求中**不含任何个人数据**）：\n" +
            "\n" +
            "1. 「检查更新」→ 查询 GitHub 最新版本信息。国内直连 GitHub 可能不通或很慢，App 会**自动依次尝试多个第三方加速代理**（ghfast.top / gh.ddlc.top / gh-proxy.com / ghproxy.net，均为社区公开的转发服务）获取同一份版本信息，请求会经由这些第三方服务器中转；\n" +
            "2. 「下载更新包」→ 从 GitHub Releases 下载官方 APK，**优先经上述加速代理下载、GitHub 直连兜底**——文件经过第三方服务器中转，且可能被代理服务缓存；\n" +
            "3. 「关于本软件」→ 加载 GitHub 上的 README 文档；\n" +
            "4. 「更新日志」→ 加载 GitHub 上的 updatelog.md 文档——与「关于本软件」同样优先经上述加速代理拉取、GitHub 直连兜底。\n" +
            "\n" +
            "除此之外，App 运行时不依赖任何公网服务。\n" +
            "\n" +
            "## 六、数据安全与你的权利\n" +
            "\n" +
            "- **传输加密**：局域网内的文件传输使用 ChaCha20 加密，密钥仅存于已配对设备本地，传输信道不经过任何我们维护的服务器；\n" +
            "- **可随时查看与删除**：服务器日志可在「设置 → 查看日志」查看并一键清空；更新安装包会在新版本安装完成后自动删除；\n" +
            "- **卸载即删**：卸载 App 会删除应用私有目录下的日志、连接记录、密钥与 WebView 存储的全部数据；\n" +
            "- **未成年人保护**：本工具是纯本地的文件共享工具，不含任何面向未成年人的内容或信息收集；\n" +
            "- **政策更新**：如本政策或内置组件清单发生变更，会在本页及「更新日志」中同步说明，并在 App 新版本中生效。\n" +
            "\n" +
            "---\n" +
            "\n" +
            "# SDK 与开源组件\n" +
            "\n" +
            "以下为本 App 内置的**全部**第三方组件。许可证列已附对应许可证全文链接；组件名链接到各项目主页 / 源码仓库。所有组件均**不包含**统计、广告、云上报或第三方跟踪代码。\n" +
            "\n" +
            "## 平台与运行时\n" +
            "\n" +
            "| 组件 | 版本 | 许可证 | 用途 |\n" +
            "| --- | --- | --- | --- |\n" +
            "| Android SDK | compileSdk 35 / targetSdk 35 / minSdk 28 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | 系统平台（Android 9+） |\n" +
            "| Android System WebView | 系统自带 | [Chromium](https://chromium.googlesource.com/chromium/src/+/main/LICENSE) | 渲染界面并运行 Web 前端 |\n" +
            "| [nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile) | Node.js 18.20.4（arm64） | [MIT](https://opensource.org/license/mit) | 内置运行时，仅作本地局域网服务器 |\n" +
            "\n" +
            "## Android 依赖\n" +
            "\n" +
            "| 组件 | 版本 | 许可证 | 用途 |\n" +
            "| --- | --- | --- | --- |\n" +
            "| [AndroidX AppCompat](https://github.com/androidx/androidx) | 1.7.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | 界面与兼容层 |\n" +
            "| [ZXing（zxing-android-embedded）](https://github.com/journeyapps/zxing-android-embedded) | 4.3.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | 二维码扫描 / 生成 |\n" +
            "| [Markwon](https://github.com/noties/Markwon) | 4.6.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | Markdown 渲染（更新说明、关于、本页） |\n" +
            "\n" +
            "## 内置 Node.js 服务器依赖（npm）\n" +
            "\n" +
            "| 组件 | 版本 | 许可证 | 用途 |\n" +
            "| --- | --- | --- | --- |\n" +
            "| [express](https://github.com/expressjs/express) | 4.22.2 | [MIT](https://opensource.org/license/mit) | HTTP 静态服务 |\n" +
            "| [ws](https://github.com/websockets/ws) | 8.21.1 | [MIT](https://opensource.org/license/mit) | WebSocket 中继传输 |\n" +
            "| [ua-parser-js](https://github.com/faisalman/ua-parser-js) | 1.0.41 | [MIT](https://opensource.org/license/mit) | 设备名识别 |\n" +
            "| [unique-names-generator](https://github.com/andreasonny83/unique-names-generator) | 4.7.1 | [MIT](https://opensource.org/license/mit) | 生成随机设备昵称 |\n" +
            "| [express-rate-limit](https://github.com/express-rate-limit/express-rate-limit) | 7.5.1 | [MIT](https://opensource.org/license/mit) | 服务器防滥用限流 |\n" +
            "\n" +
            "## 前端脚本库\n" +
            "\n" +
            "| 组件 | 版本 | 许可证 | 用途 |\n" +
            "| --- | --- | --- | --- |\n" +
            "| [noble-ciphers](https://github.com/paulmillr/noble-ciphers)（ChaCha20） | — | [MIT](https://opensource.org/license/mit) | 端到端加密 |\n" +
            "| [zip.js](https://github.com/gildas-lormeau/zip.js) | — | [BSD-3-Clause](https://opensource.org/license/bsd-3-clause) | 多文件打包 zip 下载 |\n" +
            "| [qrcode-svg](https://github.com/papnkukn/qrcode-svg) | 1.1.0 | [MIT](https://opensource.org/license/mit) | 首页二维码生成 |\n" +
            "| [NoSleep](https://github.com/richtr/NoSleep.js) | 0.12.0 | [MIT](https://opensource.org/license/mit) | 传输时保持屏幕常亮 |\n" +
            "| [heic2any](https://github.com/alexcorvi/heic2any) | — | [MIT](https://opensource.org/license/mit) | HEIC 图片预览转换 |\n" +
            "\n" +
            "以上组件的完整许可证文本均随各自开源项目分发，点击上面许可证链接即可查看全文；App 打包时未对任何组件做修改、未加入任何统计或跟踪代码。\n" +
            "\n" +
            "> **关于更新加速代理**：「检查更新 / 下载更新包 / 关于本软件 / 更新日志」经由第三方加速代理（ghfast.top / gh.ddlc.top / gh-proxy.com / ghproxy.net）转发 GitHub。它们是社区公开服务、**非本 App 内置组件**，域名可能变更；App 会自动依次尝试并在源失败时切换，无需手动配置。\n" +
            "\n" +
            "> 本项目为开源软件，完整源码与许可证文本见 [GitHub](https://github.com/jidanbings/lan-projects)。";
}
