package io.lanprojects.phone;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JS bridge the bundled web UI uses to save received files on the phone.
 *
 * The web app downloads files as {@code blob:} URLs, which a plain WebView
 * cannot persist. We inject a small JS routine that fetches the blob, streams
 * it to this bridge in base64 chunks, and we write it to the Downloads folder
 * (MediaStore on API 29+, app-external directory below).
 */
public class LanProjectsBridge {

    private static final String TAG = "LanProjectsBridge";

    private final Context context;
    private final WebView webView;
    private final Collection<String> ownHosts;
    private OutputStream currentStream;
    private Uri currentUri;
    private File currentFile;
    private String currentFilename;
    private long currentExpectedSize = -1;
    private long currentBytesWritten = 0;

    // The web UI sets this right before triggering a download (a.download is
    // NOT visible to the DownloadListener), so the saved file gets its real
    // name instead of a URL-derived garbage one like http___192.168.0.26_...
    private String pendingFileName;

    /**
     * @param webView   the WebView this bridge is injected into, used to check
     *                  the current page origin before allowing file writes.
     * @param ownHosts  the app's own server host(s) - pages served by this
     *                  phone are always trusted even on unusual networks.
     */
    public LanProjectsBridge(Context context, WebView webView, Collection<String> ownHosts) {
        this.context = context.getApplicationContext();
        this.webView = webView;
        this.ownHosts = ownHosts;
    }

    /** Called by the web UI immediately before clicking a download anchor. */
    @JavascriptInterface
    public void setPendingFileName(String name) {
        if (!isTrustedPage()) return;
        pendingFileName = name;
    }

    /** Read and clear the name the web UI announced for the next download. */
    public String takePendingFileName() {
        String n = pendingFileName;
        pendingFileName = null;
        return n;
    }

    @JavascriptInterface
    public void startBlobSave(String filename, String mime, long totalSize) {
        if (!isTrustedPage()) return;
        try {
            currentFilename = sanitize(filename);
            currentExpectedSize = totalSize;
            currentBytesWritten = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, currentFilename);
                cv.put(MediaStore.Downloads.MIME_TYPE,
                        mime != null ? mime : "application/octet-stream");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                currentUri = context.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                currentStream = context.getContentResolver().openOutputStream(currentUri);
            } else {
                File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "");
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "Could not create downloads dir");
                }
                currentFile = new File(dir, currentFilename);
                currentStream = new FileOutputStream(currentFile);
            }
        } catch (Exception e) {
            Log.e(TAG, "startBlobSave failed", e);
            closeQuietly();
        }
    }

    @JavascriptInterface
    public void saveBlobChunk(String base64) {
        try {
            if (currentStream == null) return;
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            currentStream.write(data);
            currentBytesWritten += data.length;
        } catch (Exception e) {
            Log.e(TAG, "saveBlobChunk failed", e);
        }
    }

    @JavascriptInterface
    public void finishBlobSave() {
        long written = currentBytesWritten;
        closeQuietly();
        // Verify the file really got every byte. The web UI tells us the blob
        // size up front; if what we wrote differs, the file is corrupt and we
        // delete it instead of leaving a broken install package behind.
        if (currentExpectedSize >= 0 && written != currentExpectedSize) {
            deleteCurrent();
            ServerLog.log(context, "✗ 下载校验失败: " + currentFilename
                    + " 写入 " + written + " / 预期 " + currentExpectedSize);
            toast("✗ 文件不完整，已删除: " + currentFilename
                    + "\n(" + written + " / " + currentExpectedSize + " 字节)");
            return;
        }
        ServerLog.log(context, "下载完成: " + currentFilename + " (" + written + " 字节)");
        String location = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? "已保存到 下载 (Downloads)"
                : (currentFile != null ? "已保存到 " + currentFile.getAbsolutePath() : "已保存");
        toast("✓ " + currentFilename + "\n" + location);
    }

    /** Called by the web UI after the native save server finished the file. */
    @JavascriptInterface
    public void saveDone(String filename) {
        String clean = sanitize(filename);
        ServerLog.log(context, "保存完成: " + clean);
        String location = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? "已保存到 下载 (Downloads)"
                : "已保存到本地";
        toast("✓ " + clean + "\n" + location);
    }

    @JavascriptInterface
    public void failBlobSave(String msg) {
        closeQuietly();
        deleteCurrent();
        ServerLog.log(context, "✗ 保存失败: " + currentFilename + " " + msg);
        toast("✗ 保存失败: " + (msg == null ? "" : msg));
    }

    /**
     * Only pages served by a lan-projects server on the LAN (or by this app's
     * own server) may use the file-writing bridge. A malicious page that
     * somehow loads in the WebView must not be able to drop files into the
     * Downloads folder, so refuse the call unless the current page origin is
     * trusted. Runs on the UI thread (WebView is not thread-safe).
     */
    private boolean isTrustedPage() {
        if (webView == null) return false;
        final boolean[] ok = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        webView.post(() -> {
            try {
                ok[0] = LanTargets.isTrustedPageOrigin(webView.getUrl(), ownHosts);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return false;
        }
        return ok[0];
    }

    /** Remove the half-written file/MediaStore entry. */
    private void deleteCurrent() {
        try {
            if (currentUri != null) {
                context.getContentResolver().delete(currentUri, null, null);
            }
            if (currentFile != null && currentFile.exists()) {
                currentFile.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private void closeQuietly() {
        try {
            if (currentStream != null) currentStream.close();
        } catch (Exception ignored) {
        }
        currentStream = null;
        currentUri = null;
        currentFile = null;
    }

    private String sanitize(String name) {
        if (name == null || name.isEmpty()) return "lan-projects-" + System.currentTimeMillis();
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void toast(final String msg) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
    }
}
