package io.lanprojects.phone;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Collection;

/**
 * Tiny local HTTP server (main process) that receives a received-file blob as a
 * raw binary POST body and writes it straight to the Downloads folder.
 *
 * WHY: the old path base64-encoded the blob and pushed it through the
 * addJavascriptInterface bridge in ~256 KB chunks - for a 1 GB file that is
 * 1.33 GB of base64 crossing a slow JNI bridge (~4000 calls) and took ~20 s on
 * a weak phone. A desktop browser saves the same file natively in 2-3 s. This
 * server makes the app's path native too: fetch() streams the blob as a binary
 * HTTP body (no base64, no bridge), this server streams it to disk, and the
 * file lands in Downloads almost as fast as the disk allows.
 *
 * com.sun.net.httpserver is not available on Android, so a minimal HTTP/1.1
 * handler is implemented on a plain ServerSocket.
 */
public class SaveFileServer {

    private static final int PORT = 3900;

    private final Context context;
    private final Collection<String> ownHosts;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    /**
     * @param ownHosts the app's own server host(s); requests whose Origin is
     *                 this phone's own server are accepted even on unusual
     *                 networks without a private-range IP.
     */
    public SaveFileServer(Context context, Collection<String> ownHosts) {
        this.context = context.getApplicationContext();
        this.ownHosts = ownHosts;
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
        } catch (Exception e) {
            ServerLog.log(context, "保存服务器启动失败: " + e);
            return;
        }
        acceptThread = new Thread(() -> {
            while (true) {
                try {
                    Socket s = serverSocket.accept();
                    handle(s);
                } catch (Exception ignored) {
                    break;
                }
            }
        }, "save-file-server");
        acceptThread.start();
        ServerLog.log(context, "本地保存服务器已启动 (127.0.0.1:" + PORT + ")");
    }

    public void stop() {
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        serverSocket = null;
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             InputStream in = s.getInputStream();
             OutputStream out = s.getOutputStream()) {
            s.setSoTimeout(15 * 60 * 1000); // generous: a 1 GB body takes a while on weak phones

            // ---- read request line + headers until \r\n\r\n ----
            StringBuilder head = new StringBuilder();
            int b;
            while (head.length() < 32 * 1024 && (b = in.read()) != -1) {
                head.append((char) b);
                if (head.length() >= 4
                        && head.charAt(head.length() - 4) == '\r'
                        && head.charAt(head.length() - 3) == '\n'
                        && head.charAt(head.length() - 2) == '\r'
                        && head.charAt(head.length() - 1) == '\n') {
                    break;
                }
            }
            String header = head.toString();
            // Only LAN lan-projects pages may save files here. A page that
            // somehow loads in the WebView must not be able to drop files into
            // Downloads, so reject requests whose Origin is not a trusted LAN
            // origin (browser requests always send Origin; requests without one
            // are allowed for backwards compatibility).
            String origin = headerValue(header, "Origin:");
            if (origin != null && !LanTargets.isTrustedPageOrigin(origin, ownHosts)) {
                respond(out, 403, "Forbidden");
                return;
            }
            // The web UI's fetch() is cross-origin (page on :3000 -> POST to
            // :3900). A Blob body has a non-safelisted Content-Type, so the
            // browser sends an OPTIONS preflight first; answer it with CORS
            // headers or the POST is never sent and the download fails.
            if (header.startsWith("OPTIONS")) {
                respond(out, 200, "OK");
                return;
            }
            if (!header.startsWith("POST")) {
                respond(out, 405, "Method Not Allowed");
                return;
            }

            // request line: POST /save?name=...&mime=...&size=... HTTP/1.1
            String requestLine = header.substring(0, header.indexOf("\r\n"));
            String query = "";
            int qStart = requestLine.indexOf('?');
            int sp = requestLine.indexOf(' ', qStart);
            if (qStart >= 0) {
                query = requestLine.substring(qStart + 1, sp > 0 ? sp : requestLine.length());
            }
            String name = param(query, "name", "lan-projects-file");
            String mime = param(query, "mime", "application/octet-stream");
            long expected = Long.parseLong(param(query, "size", "-1"));

            // Content-Length
            long contentLength = -1;
            int clIdx = header.toLowerCase().indexOf("content-length:");
            if (clIdx >= 0) {
                int lineEnd = header.indexOf("\r\n", clIdx);
                contentLength = Long.parseLong(
                        header.substring(clIdx + "content-length:".length(), lineEnd).trim());
            }
            if (contentLength < 0) {
                respond(out, 411, "Length Required");
                return;
            }

            // ---- stream the body to a temp file (never buffer the whole file) ----
            // First clean any orphaned saving-* temp files: an interrupted save
            // can leave a file as big as the transfer sitting in getCacheDir(),
            // inflating the app's reported size. The current temp is deleted in
            // the finally below on every path (success, failure, exception).
            cleanStaleTempFiles();
            File tmp = null;
            long written = 0;
            try {
                tmp = new File(context.getCacheDir(), "saving-" + System.currentTimeMillis());
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while (written < contentLength && (n = in.read(buf, 0,
                            (int) Math.min(buf.length, contentLength - written))) != -1) {
                        fos.write(buf, 0, n);
                        written += n;
                    }
                }

                if (written != contentLength || (expected >= 0 && written != expected)) {
                    ServerLog.log(context, "✗ 保存失败(字节不符): " + name + " " + written + "/" + expected);
                    respond(out, 500, "Size mismatch");
                    return;
                }

                // ---- move to Downloads ----
                boolean ok = moveToDownloads(tmp, name, mime);
                if (ok) {
                    ServerLog.log(context, "下载完成: " + name + " (" + written + " 字节)");
                    respond(out, 200, "OK");
                } else {
                    ServerLog.log(context, "✗ 保存到下载目录失败: " + name);
                    respond(out, 500, "Save failed");
                }
            } catch (Exception e) {
                ServerLog.log(context, "保存服务器错误: " + e);
            } finally {
                // Remove the temp file on every path (success / failure /
                // exception) so an interrupted save never leaves a big file in
                // the cache dir.
                if (tmp != null && tmp.exists()) tmp.delete();
            }
        } catch (Exception e) {
            ServerLog.log(context, "保存服务器错误: " + e);
        }
    }

    /** Delete any leftover saving-* temp files (interrupted previous saves). */
    private void cleanStaleTempFiles() {
        File[] files = context.getCacheDir().listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith("saving-")) {
                if (f.delete()) {
                    ServerLog.log(context, "清理残留临时文件: " + f.getName()
                            + " (" + (f.length() / 1024 / 1024) + "MB)");
                }
            }
        }
    }

    private boolean moveToDownloads(File src, String filename, String mime) {
        try {
            String cleanName = filename.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, cleanName);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = context.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                try (OutputStream os = context.getContentResolver().openOutputStream(uri);
                     FileInputStream fis = new FileInputStream(src)) {
                    if (os == null) return false;
                    copy(fis, os);
                }
            } else {
                File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "");
                if (!dir.exists() && !dir.mkdirs()) return false;
                File dest = new File(dir, cleanName);
                try (FileOutputStream os = new FileOutputStream(dest);
                     FileInputStream fis = new FileInputStream(src)) {
                    copy(fis, os);
                }
            }
            return true;
        } catch (Exception e) {
            ServerLog.log(context, "moveToDownloads 失败: " + e);
            return false;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    /** Read a header line's value from the raw request head, or null. */
    private static String headerValue(String header, String key) {
        int idx = header.toLowerCase().indexOf(key.toLowerCase());
        if (idx < 0) return null;
        int lineEnd = header.indexOf("\r\n", idx);
        if (lineEnd < 0) return null;
        String value = header.substring(idx + key.length(), lineEnd).trim();
        return value.isEmpty() ? null : value;
    }

    private static String param(String query, String key, String def) {
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                try {
                    return URLDecoder.decode(part.substring(eq + 1), "UTF-8");
                } catch (Exception ignored) {
                    return def;
                }
            }
        }
        return def;
    }

    private static void respond(OutputStream out, int code, String text) throws Exception {
        String body = code + " " + text;
        out.write(("HTTP/1.1 " + code + " " + text + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Connection: close\r\n"
                + "\r\n" + body).getBytes("UTF-8"));
        out.flush();
    }
}
