package io.lanprojects.phone;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared GitHub text-content fetcher for the About (README) and 更新日志
 * (updatelog.md) pages. GitHub raw is slow / unreachable for many users in
 * China, so these pages use the SAME accelerated mirrors as 检查更新: try each
 * source in order and take the first that returns readable content. The mirror
 * domain list lives in SettingsActivity.DOWNLOAD_PROXIES (per project
 * convention) so a dead mirror only needs one edit.
 */
public final class GithubContent {

    /** Repo this app's docs live in, and the branch they are on. */
    private static final String OWNER_REPO = "jidanbings/lan-projects";
    private static final String BRANCH = "main";

    private GithubContent() {
    }

    /**
     * Candidate URLs for one doc file (e.g. "README.md", "updatelog.md"):
     * accelerated mirrors first (fast and reachable in China), GitHub direct
     * as the final fallback — the same acceleration strategy as 检查更新.
     */
    public static List<String> candidates(String path) {
        List<String> urls = new ArrayList<>();
        for (String proxy : SettingsActivity.DOWNLOAD_PROXIES) {
            // github.com/…/raw 会 302 到 raw.githubusercontent.com，代理服务端跟随
            urls.add(proxy + "https://github.com/" + OWNER_REPO + "/raw/" + BRANCH + "/" + path);
        }
        urls.add("https://raw.githubusercontent.com/" + OWNER_REPO + "/" + BRANCH + "/" + path);
        return urls;
    }

    /** 依次尝试候选地址，返回第一个成功读到的内容；全部失败返回 null。 */
    public static String fetch(Context context, List<String> urls) {
        for (String url : urls) {
            String body = fetchOne(context, url);
            if (body != null && !body.isEmpty() && !looksLikeHtmlPage(body)) return body;
        }
        return null;
    }

    private static String fetchOne(Context context, String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "lan-projects-android");
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return null;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream is = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                    if (baos.size() > 2 * 1024 * 1024) break;  // 防御异常巨大的文件
                }
            }
            conn.disconnect();
            return baos.toString("UTF-8");
        } catch (Exception e) {
            ServerLog.log(context, "GitHub 内容拉取失败 " + url + ": " + e);
            return null;
        }
    }

    /** 代理偶尔会返回错误页 / 登录页而不是文件本身，这类 HTML 跳过、换下一个源。 */
    private static boolean looksLikeHtmlPage(String content) {
        String t = content.trim();
        return t.startsWith("<!DOCTYPE") || t.startsWith("<html") || t.startsWith("<HTML");
    }
}
