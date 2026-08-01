package io.lanprojects.phone;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Measures THIS phone's real outbound transfer speed: how fast it can push
 * encrypted data through a WebSocket (the exact pipeline file transfers use).
 * On the user's phones the LAN itself does ~300 MB/s, but a phone as server is
 * limited by its JS data processing (~20 MB/s on a good phone, ~5 MB/s on a
 * weak one). The test auto-starts the local server, connects a WebSocket to
 * it, and streams a large encrypted payload while timing it - so the result
 * matches the phone's true transfer ceiling.
 */
public class SpeedTestActivity extends AppCompatActivity {

    private static final int SERVER_PORT = 3000;

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_speed_test);

        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.speedRoot), (v, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        webView = findViewById(R.id.webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);

        // Ensure the local server is running so the test has a WebSocket sink.
        // The test page retries the connection until it is up.
        if (!isServerUp()) {
            try {
                Intent svc = new Intent(this, NodeService.class);
                svc.setAction(NodeService.ACTION_START);
                startForegroundService(svc);
            } catch (Exception ignored) {
            }
        }

        String html = """
                <!DOCTYPE html><html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                body{background:#ECEFF1;color:#212121;font-family:sans-serif;padding:24px;text-align:center;margin:0;}
                h1{font-size:18px;margin:8px 0;} p{color:#546E7A;font-size:14px;line-height:1.7;}
                .btn{background:#3367D6;color:#fff;border:none;padding:14px 32px;border-radius:8px;font-size:16px;margin:18px 0;}
                .btn:disabled{background:#B0BEC5;color:#FFFFFF;}
                #result{margin-top:10px;} .big{font-size:34px;font-weight:bold;color:#1565C0;margin:6px 0;}
                .hint{font-size:12px;color:#78909C;}
                </style></head><body>
                <h1>本机对外传输速度测试</h1>
                <p>把加密数据真实地通过本地 WebSocket 推送出去计时，
                <br>测出你这台手机作为服务器能向外传输的最大速度。</p>
                <p id="status" class="hint">正在连接本地服务器…</p>
                <button class="btn" id="btn" disabled>开始测试</button>
                <div id="result"></div>
                <script>
                var KEY = new Uint8Array(32);
                for (var i = 0; i < 32; i++) KEY[i] = (i * 37) % 256;
                function encrypt(view) {
                  var iv = crypto.getRandomValues(new Uint8Array(8));
                  var out = new Uint8Array(view.length + 8);
                  out.set(iv);
                  var k = KEY;
                  for (var i = 0; i < view.length; i++) out[8 + i] = view[i] ^ k[i % 32];
                  return out.buffer;
                }
                var CHUNK = 1048576;          // 1 MB per frame (same as real transfers)
                var TOTAL = 64 * 1048576;     // 64 MB total - long enough for a stable ~2s measurement
                var ws = null;
                function connect() {
                  ws = new WebSocket('ws://127.0.0.1:3000/server');
                  ws.binaryType = 'arraybuffer';
                  ws.onopen = function() {
                    document.getElementById('status').textContent = '已连接本地服务器';
                    document.getElementById('btn').disabled = false;
                  };
                  ws.onclose = function() {
                    document.getElementById('status').textContent = '服务器未就绪，重试中…';
                    document.getElementById('btn').disabled = true;
                    setTimeout(connect, 500);
                  };
                  ws.onerror = function() { ws.close(); };
                  ws.onmessage = function(e) {
                    if (typeof e.data === 'string') {
                      try { var m = JSON.parse(e.data); if (m.type === 'ping') ws.send(JSON.stringify({type:'pong'})); } catch(_) {}
                    }
                  };
                }
                connect();
                document.getElementById('btn').onclick = function() {
                  var btn = document.getElementById('btn');
                  var res = document.getElementById('result');
                  btn.disabled = true;
                  res.innerHTML = '<p>正在生成并推送 64 MB 测试数据…</p>';
                  setTimeout(function() {
                    try {
                      var data = new Uint8Array(TOTAL);
                      var seed = 12345;
                      for (var i = 0; i < TOTAL; i++) {
                        seed = (seed * 1103515245 + 12345) & 0x7fffffff;
                        data[i] = seed & 255;
                      }
                      // Read through FileReader exactly like a real transfer does
                      // (this is where the phone's processing time actually goes),
                      // then encrypt and push over the WebSocket.
                      var blob = new Blob([data]);
                      var offset = 0;
                      var start = performance.now();
                      function pushChunk() {
                        var reader = new FileReader();
                        reader.onload = function() {
                          try {
                            var chunk = new Uint8Array(reader.result);
                            var enc = encrypt(chunk);
                            ws.send(enc);
                            offset += CHUNK;
                            if (offset < TOTAL) {
                              pushChunk();
                            } else {
                              var elapsed = (performance.now() - start) / 1000;
                              var mbps = (TOTAL / 1048576) / elapsed;
                              res.innerHTML =
                                '<div class="big">' + mbps.toFixed(1) + ' MB/s</div>' +
                                '<p>本机对外传输速度</p>' +
                                '<p>推送 ' + (TOTAL / 1048576) + ' MB 用时 ' + elapsed.toFixed(1) + ' 秒</p>' +
                                '<p class="hint">和真实传输相同的读取 + 加密 + WebSocket 二进制帧，<br>' +
                                '这就是你这台手机作为服务器向外传输的速度上限。</p>';
                              btn.disabled = false;
                            }
                          } catch (e) { res.innerHTML = '<p>测试失败: ' + e + '</p>'; btn.disabled = false; }
                        };
                        reader.onerror = function() { res.innerHTML = '<p>读取失败</p>'; btn.disabled = false; };
                        reader.readAsArrayBuffer(blob.slice(offset, offset + CHUNK));
                      }
                      pushChunk();
                    } catch (e) { res.innerHTML = '<p>测试失败: ' + e + '</p>'; btn.disabled = false; }
                  }, 30);
                };
                </script>
                </body></html>
                """;

        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private boolean isServerUp() {
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", SERVER_PORT), 300);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
