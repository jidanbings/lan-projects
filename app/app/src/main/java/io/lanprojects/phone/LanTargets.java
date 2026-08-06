package io.lanprojects.phone;

import java.net.URI;
import java.util.Collection;

/**
 * Validation shared by every place that decides what a page may be:
 *
 *  - LaunchActivity's QR scan: a scanned code must resolve to a lan-projects
 *    server on the LAN (plain http, private-range IP / LAN hostname, root path,
 *    only the query params evaluateUrlParams handles, e.g. ?secret= / ?room_id=),
 *    so scanning a QR can never open a website.
 *  - MainActivity's WebView navigation: only LAN lan-projects pages load inside
 *    the app's WebView; anything else opens in the system browser.
 *  - LanProjectsBridge / SaveFileServer: only pages from a trusted LAN origin
 *    may write files, so a page that somehow loads in the WebView cannot drop
 *    files into Downloads.
 *
 * Everything fails closed: unknown scheme / host / parse error = not trusted.
 */
final class LanTargets {

    private LanTargets() {
    }

    /** Normalize a scanned QR value into a LAN connect URL, or null if invalid. */
    static String normalizeTarget(String input) {
        if (input == null) return null;
        String a = input.trim();
        if (a.isEmpty()) return null;

        String url;
        if (a.startsWith("http://") || a.startsWith("https://")) {
            url = a;
        } else {
            // Legacy bare host[:port] QR - build a full http URL.
            if (!a.matches("^[a-zA-Z0-9._\\-\\[\\]:]+$")) return null;
            if (a.endsWith("/")) a = a.substring(0, a.length() - 1);
            url = a.contains(":") ? "http://" + a + "/" : "http://" + a + ":3000/";
        }
        return isConnectUrl(url) ? url : null;
    }

    /** Accept only URLs that look like a lan-projects server on the LAN. */
    static boolean isConnectUrl(String url) {
        try {
            URI uri = new URI(url);
            // The lan-projects server is plain HTTP on the LAN; a scanned QR
            // must never load an https site.
            if (!"http".equalsIgnoreCase(uri.getScheme())) return false;
            // No userinfo (http://attacker@host is pure phishing), no fragment.
            if (uri.getUserInfo() != null || uri.getRawFragment() != null) return false;
            String host = uri.getHost();
            if (host == null || host.isEmpty() || !isLanHost(host)) return false;
            int port = uri.getPort();
            if (port != -1 && (port <= 0 || port > 65535)) return false;
            // The web UI is served at the root only.
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) return false;
            // Only the query params the web UI's evaluateUrlParams understands.
            // (main.js evaluateUrlParams: secret / room_id / base64text /
            // base64zip / share_target[+title+text+url] / file_handler / init.
            // "pair_key" was the pre-v1.1.0 6-digit pairing param and is gone.)
            String query = uri.getRawQuery();
            if (query != null && !query.isEmpty()) {
                for (String pair : query.split("&")) {
                    if (pair.isEmpty()) continue;
                    int eq = pair.indexOf('=');
                    String key = eq >= 0 ? pair.substring(0, eq) : pair;
                    if (!("secret".equals(key)
                            || "room_id".equals(key)
                            || "base64text".equals(key)
                            || "base64zip".equals(key)
                            || "share_target".equals(key)
                            || "title".equals(key)
                            || "text".equals(key)
                            || "url".equals(key)
                            || "file_handler".equals(key)
                            || "init".equals(key))) return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** True for private-range IPs (LAN) and single-label / *.local hostnames. */
    static boolean isLanHost(String host) {
        String h = host;
        if (h.startsWith("[")) h = h.substring(1, h.length() - 1);
        String lower = h.toLowerCase();

        if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            String[] parts = h.split("\\.");
            int[] o = new int[4];
            for (int i = 0; i < 4; i++) {
                o[i] = Integer.parseInt(parts[i]);
                if (o[i] > 255) return false;
            }
            // 10/8, 172.16/12, 192.168/16, 100.64/10 (CGNAT incl. Tailscale),
            // 169.254/16 (link-local), 127/8 (loopback).
            if (o[0] == 10) return true;
            if (o[0] == 172 && o[1] >= 16 && o[1] <= 31) return true;
            if (o[0] == 192 && o[1] == 168) return true;
            if (o[0] == 100 && o[1] >= 64 && o[1] <= 127) return true;
            if (o[0] == 169 && o[1] == 254) return true;
            return o[0] == 127;
        }
        // IPv6: loopback ::1, ULA fc00::/7, link-local fe80::/10.
        if (lower.equals("::1")) return true;
        if (lower.startsWith("fc") || lower.startsWith("fd")) return true;
        if (lower.startsWith("fe8") || lower.startsWith("fe9")
                || lower.startsWith("fea") || lower.startsWith("feb")) return true;
        // Hostname: a single label ("raspberrypi") or *.local (mDNS) - both
        // resolve on the LAN. Public DNS names are rejected.
        return h.matches("[a-zA-Z0-9]([a-zA-Z0-9-]{0,62})")
                || lower.endsWith(".local");
    }

    /** True if url/origin belongs to a lan-projects page we trust: plain http
     *  on a LAN host, or on one of {@code extraHosts} (the app's own server
     *  host, in case the phone is on an unusual network without a private IP). */
    static boolean isTrustedPageOrigin(String url, Collection<String> extraHosts) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            if (!"http".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return false;
            if (isLanHost(host)) return true;
            if (extraHosts != null) {
                for (String h : extraHosts) {
                    if (h != null && !h.isEmpty() && host.equalsIgnoreCase(h)) return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
