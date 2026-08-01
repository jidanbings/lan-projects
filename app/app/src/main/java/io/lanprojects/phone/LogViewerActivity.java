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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Shows the in-app diagnostic log (files/server.log) plus crash.log, so the
 * user can read what the server did without adb. Reached from the 查看日志
 * button in the top-right corner of the launch screen.
 */
public class LogViewerActivity extends AppCompatActivity {

    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.logViewerRoot), (v, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });

        logText = findViewById(R.id.logText);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            ServerLog.clear(this);
            refresh();
        });

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        String serverLog = ServerLog.read(this);
        String crashLog = readCrashLog();
        String all;
        if (crashLog != null && !crashLog.isEmpty()) {
            all = "========== server.log ==========\n" + serverLog
                    + "\n\n========== crash.log ==========\n" + crashLog;
        } else {
            all = "========== server.log ==========\n" + serverLog;
        }
        logText.setText(all);
    }

    private String readCrashLog() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return null;
            File f = new File(dir, "crash.log");
            if (!f.exists()) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "读取 crash.log 失败: " + e;
        }
    }
}
