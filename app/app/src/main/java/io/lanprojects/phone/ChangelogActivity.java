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
 * "更新日志" page: renders the project's changelog (updatelog.md, fetched live
 * from GitHub, markdown) so it always shows the latest entries. Uses the same
 * GitHub acceleration mirrors as 检查更新 so users in China can view it without
 * needing to reach GitHub directly.
 */
public class ChangelogActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_changelog);

        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(true);
        insetsController.setAppearanceLightNavigationBars(true);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.changelogRoot), (v, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });

        findViewById(R.id.btnChangelogBack).setOnClickListener(v -> finish());

        loadChangelog();
    }

    private void loadChangelog() {
        TextView tv = findViewById(R.id.changelogContent);
        tv.setText("加载中…");
        new Thread(() -> {
            // 加速代理在前、GitHub 直连兜底，与「检查更新」同一组国内加速源。
            String md = GithubContent.fetch(this, GithubContent.candidates("updatelog.md"));
            final String content = md;
            runOnUiThread(() -> {
                if (content == null || content.isEmpty()) {
                    tv.setText("无法加载更新日志，请检查网络后重试");
                } else {
                    MarkdownRenderer.render(this, tv, content);
                }
            });
        }, "changelog-load").start();
    }
}
