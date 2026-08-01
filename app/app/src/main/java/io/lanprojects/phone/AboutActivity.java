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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;

/**
 * "关于本软件" page: renders the project's README (fetched live from GitHub,
 * markdown) so it always shows the latest intro without bundling a copy.
 */
public class AboutActivity extends AppCompatActivity {

    private static final String README_URL =
            "https://raw.githubusercontent.com/jidanbings/lan-projects/main/README.md";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.aboutRoot), (v, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });

        findViewById(R.id.btnAboutBack).setOnClickListener(v -> finish());

        loadReadme();
    }

    private void loadReadme() {
        TextView tv = findViewById(R.id.aboutContent);
        tv.setText("加载中…");
        new Thread(() -> {
            String md = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(README_URL).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "lan-projects-android/about");
                if (conn.getResponseCode() == 200) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (InputStream is = conn.getInputStream()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    }
                    md = baos.toString("UTF-8");
                }
                conn.disconnect();
            } catch (Exception e) {
                ServerLog.log(this, "加载关于文档失败: " + e);
            }
            final String content = md;
            runOnUiThread(() -> {
                if (content == null || content.isEmpty()) {
                    tv.setText("无法加载介绍文档，请检查网络后重试");
                } else {
                    markwon().setMarkdown(tv, content);
                }
            });
        }, "about-load").start();
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
}
