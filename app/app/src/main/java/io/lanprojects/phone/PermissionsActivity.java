package io.lanprojects.phone;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 「已授权的权限」独立页面：逐项列出本 App 需要的可管理权限，每个权限带详细的
 * 隐私说明（用途 / 读取了什么 / 拒绝的影响），并支持实时开关——开启走系统授权
 * 弹窗，关闭跳转系统设置（返回后自动刷新）。常规权限（网络 / 前台服务 / 唤醒锁
 * 等）安装时授予、无法在此关闭，页面顶部说明。
 */
public class PermissionsActivity extends AppCompatActivity {

    // 权限类型：运行时权限（requestPermissions 授予）与两种特殊权限（跳系统设置页）。
    private static final int REQ_PERM_MANAGE = 300;
    private static final int PERM_RUNTIME = 0;
    private static final int PERM_OVERLAY = 1;
    private static final int PERM_UNKNOWN_SOURCES = 2;

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permissions);

        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(true);
        insetsController.setAppearanceLightNavigationBars(true);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.permissionsRoot), (v, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });

        findViewById(R.id.btnPermissionsBack).setOnClickListener(v -> finish());

        list = findViewById(R.id.permissionsList);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 首次打开与每次从系统权限 / 应用信息设置页返回都会走到这里：重建列表，
        // 让各开关始终反映当前真实授权状态。
        buildList();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_MANAGE) buildList();
    }

    private void buildList() {
        list.removeAllViews();
        addNote();
        addPermissionRow("相机", PERM_RUNTIME, Manifest.permission.CAMERA,
                "扫码连接时扫描二维码",
                "仅在点击「扫码连接」后调用相机，扫描对方设备的连接码 / 配对码 / 房间码。\n"
                        + "· 不拍摄、不录像、不保存画面，不访问相册；\n"
                        + "· 只有你主动打开扫码头时才调用，其余时间不启用；\n"
                        + "· 扫描结果只用于建立局域网连接，不离开本机、不上传。");
        if (Build.VERSION.SDK_INT >= 33) {
            addPermissionRow("通知", PERM_RUNTIME, Manifest.permission.POST_NOTIFICATIONS,
                    "显示服务器运行状态与更新下载进度",
                    "用于在通知栏显示：服务器运行状态（配合前台服务保持后台传输）、更新包下载进度。\n"
                            + "· 通知内容全部由本 App 本地生成，不含你的文件、不读取其他通知；\n"
                            + "· 拒绝后传输不受影响，只是看不到服务器状态与下载进度。");
            addPermissionRow("附近 WiFi 设备", PERM_RUNTIME, Manifest.permission.NEARBY_WIFI_DEVICES,
                    "首页显示当前 WiFi 网络名",
                    "用于首页「网络状态」识别当前连接的 WiFi，只显示网络名（SSID）方便分辨在哪个网络下传输。\n"
                            + "· 不定位、不扫描周围设备、不上传任何数据；\n"
                            + "· 网络名只用于屏幕显示、不写入存储；\n"
                            + "· 拒绝后仅是不显示网络名，文件传输完全不受影响。");
        } else {
            addPermissionRow("位置信息", PERM_RUNTIME, Manifest.permission.ACCESS_FINE_LOCATION,
                    "首页显示当前 WiFi 网络名（Android 9-12）",
                    "Android 13 以下读取 WiFi 网络名需要位置权限（系统限制，与本 App 无关）。\n"
                            + "· App 不进行任何定位 / 追踪，仅用于首页显示当前 WiFi 网络名；\n"
                            + "· 不存储、不上传位置或网络信息；\n"
                            + "· 拒绝后仅是不显示网络名，文件传输完全不受影响。");
        }
        addPermissionRow("显示在其他应用上层", PERM_OVERLAY, null,
                "选文件时保持屏幕常亮，防止热点断连",
                "选文件时，文件选择器盖住 App、自带屏幕常亮失效；若此刻息屏，热点无线电会掐断与对方的连接。"
                        + "App 在选文件期间挂一个 1×1 透明悬浮窗（携带系统「保持屏幕常亮」标志）防止息屏。\n"
                        + "· 完全透明、不可见、不拦截触控，不显示任何悬浮内容；\n"
                        + "· 不读取屏幕、不读取其他应用、不上传任何数据；\n"
                        + "· 只在选文件期间存在，选完立即移除，平时不常驻；\n"
                        + "· 授予该权限不代表会弹广告悬浮窗——本 App 无广告；\n"
                        + "· 拒绝后只是失去防断连优化，5 秒自动重连兜底，传输不受影响。");
        addPermissionRow("安装未知应用", PERM_UNKNOWN_SOURCES, null,
                "安装「检查更新」下载的官方 APK",
                "仅在「检查更新 → 下载并安装」时使用，用来安装从官方 GitHub Release 下载的安装包。\n"
                        + "· App 不会未经你同意安装任何应用，也不下载非官方来源；\n"
                        + "· 安装包仅来自 lan-projects 官方仓库、版本由「检查更新」校验；\n"
                        + "· 拒绝后仍可下载，只是需要到文件管理器里手动点击安装。");
    }

    private void addNote() {
        TextView note = new TextView(this);
        note.setText("网络 / 前台服务 / 唤醒锁 / 修改网络状态等为安装时授予的常规权限，无法在此单独关闭。\n"
                + "以下为可管理的权限：开启走系统授权弹窗，关闭跳转系统设置（返回后自动刷新状态）。");
        note.setTextSize(12);
        note.setTextColor(0xFF78909C);
        note.setLineSpacing(0f, 1.25f);
        note.setPadding(0, dp(6), 0, dp(12));
        list.addView(note);
        addDivider();
    }

    private void addPermissionRow(String name, int type, String permission,
                                  String usage, String privacyDetail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));

        // 第一行：权限名 + 用途在左，开关靠右。
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView nameTv = new TextView(this);
        nameTv.setText(name);
        nameTv.setTextSize(16);
        nameTv.setTextColor(0xFF212121);
        nameTv.setTypeface(nameTv.getTypeface(), android.graphics.Typeface.BOLD);
        textCol.addView(nameTv);
        TextView usageTv = new TextView(this);
        usageTv.setText(usage);
        usageTv.setTextSize(12);
        usageTv.setTextColor(0xFF607D8B);
        usageTv.setPadding(0, dp(2), 0, 0);
        textCol.addView(usageTv);
        header.addView(textCol);

        // 注意顺序：先 setChecked 再挂监听，避免重建列表时误触发开关逻辑。
        Switch sw = new Switch(this);
        sw.setChecked(isPermissionGranted(permission, type));
        sw.setOnCheckedChangeListener((b, isChecked) ->
                onPermissionToggled(name, permission, type, isChecked, sw));
        header.addView(sw);

        row.addView(header);

        // 第二行：详细的隐私说明（多行小字）。
        TextView detailTv = new TextView(this);
        detailTv.setText(privacyDetail);
        detailTv.setTextSize(12.5f);
        detailTv.setTextColor(0xFF546E7A);
        detailTv.setLineSpacing(0f, 1.25f);
        detailTv.setPadding(0, dp(8), dp(8), 0);   // 右侧留白，避免贴住下一行
        row.addView(detailTv);

        list.addView(row);
        addDivider();
    }

    private void addDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(0xFFD9DEE3);
        list.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
    }

    private boolean isPermissionGranted(String permission, int type) {
        switch (type) {
            case PERM_RUNTIME:
                return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
            case PERM_OVERLAY:
                return Settings.canDrawOverlays(this);
            case PERM_UNKNOWN_SOURCES:
                return getPackageManager().canRequestPackageInstalls();
        }
        return false;
    }

    private void onPermissionToggled(String name, String permission, int type,
                                     boolean isChecked, Switch sw) {
        switch (type) {
            case PERM_RUNTIME:
                if (isChecked) {
                    // 直接弹系统授权框；结果回来时 onRequestPermissionsResult 会重建列表
                    if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{permission}, REQ_PERM_MANAGE);
                    }
                } else {
                    // 运行时权限无法由 App 主动回收，只能到系统设置里关闭
                    sw.setChecked(true);
                    Toast.makeText(this, "请在系统设置中关闭「" + name + "」", Toast.LENGTH_LONG).show();
                    openAppDetailsSettings();
                }
                break;
            case PERM_OVERLAY:
                sw.setChecked(Settings.canDrawOverlays(this));
                Toast.makeText(this, "请在打开的页面中设置「" + name + "」", Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                }
                break;
            case PERM_UNKNOWN_SOURCES:
                sw.setChecked(getPackageManager().canRequestPackageInstalls());
                Toast.makeText(this, "请在打开的页面中设置「" + name + "」", Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                }
                break;
        }
    }

    /** 打开本 App 的系统权限管理页（用于关闭某个运行时权限）。 */
    private void openAppDetailsSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开应用权限设置", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
