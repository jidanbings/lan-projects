package io.lanprojects.phone;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Snapshot of the phone's current connectivity for the launch screen:
 *
 *  - which network it is on: WiFi / mobile data / personal hotspot / none
 *  - the network name (WiFi SSID) and the relevant IPv4 for that network
 *  - whether the WiFi / mobile data / hotspot switches are currently on
 *
 * Toggling is best-effort: since Android 10 apps can no longer flip these
 * switches programmatically, the {@code toggle*} helpers try the direct path
 * (usable on Android 9) and fall back to opening the matching system panel /
 * settings screen where the user flips the switch themselves.
 */
public final class NetworkStatus {

    public enum Kind { WIFI, MOBILE, HOTSPOT, NONE }

    public final Kind kind;          // the network currently in use
    public final String name;        // WiFi SSID, or null when unavailable
    public final String ip;          // IPv4 for `kind`, or null
    public final boolean wifiOn;     // WiFi switch state
    public final boolean mobileOn;   // mobile-data link present (best effort)
    public final boolean hotspotOn;  // personal hotspot serving state

    private NetworkStatus(Kind kind, String name, String ip,
                          boolean wifiOn, boolean mobileOn, boolean hotspotOn) {
        this.kind = kind;
        this.name = name;
        this.ip = ip;
        this.wifiOn = wifiOn;
        this.mobileOn = mobileOn;
        this.hotspotOn = hotspotOn;
    }

    /** Snapshot of the current state. Cheap; safe to call on the UI thread. */
    public static NetworkStatus detect(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean hotspotOn = isHotspotOn(context);
        boolean wifiOn = isWifiEnabled(context);
        boolean mobileOn = isMobileDataConnected(cm);

        Kind kind = Kind.NONE;
        String name = null;
        String ip = null;

        Network active = cm == null ? null : cm.getActiveNetwork();
        if (active != null) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            LinkProperties lp = cm.getLinkProperties(active);
            String activeIp = firstIpv4(lp);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                kind = Kind.MOBILE;
                ip = activeIp;
            } else if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                kind = Kind.WIFI;
                ip = activeIp;
                name = getWifiSsid(context);
            }
        }

        if (hotspotOn) {
            // Hotspot wins over the active network: the WiFi radio is in AP
            // mode, so there is no active WiFi transport, yet the hotspot is
            // serving clients from its own AP interface IP.
            kind = Kind.HOTSPOT;
            ip = getHotspotIp(context);
        }

        if (kind == Kind.NONE) {
            // Ethernet / USB tethering / nothing: fall back to the app's usual
            // LAN address resolution so the status card still shows an address.
            ip = MainActivity.getLanIpAddress();
        }

        return new NetworkStatus(kind, name, ip, wifiOn, mobileOn, hotspotOn);
    }

    // ------------------------------------------------------------------
    // Detection helpers
    // ------------------------------------------------------------------

    /**
     * True when the personal hotspot is serving. API 31+ has a public
     * TetheringManager; older versions fall back to the (greylisted)
     * WifiManager.isWifiApEnabled() reflection, which still works on the
     * vast majority of devices for read-only state.
     */
    private static boolean isHotspotOn(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
                // TetheringManager.getTetheringState(TETHERING_WIFI=0) returns
                // TETHERING_ON=1 when the WiFi hotspot is serving.
                Object tm = ConnectivityManager.class.getMethod("getTetheringManager").invoke(cm);
                Object state = tm.getClass().getMethod("getTetheringState", int.class)
                        .invoke(tm, 0);
                if (state instanceof Integer) return (Integer) state == 1;
            } catch (Throwable ignored) {
                // Fall through to the reflection path.
            }
        }
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            return Boolean.TRUE.equals(
                    WifiManager.class.getMethod("isWifiApEnabled").invoke(wm));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isWifiEnabled(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            // isWifiEnabled() is deprecated on API 29+; getWifiState() is its
            // non-deprecated equivalent (same semantics).
            return wm != null && wm.getWifiState() == WifiManager.WIFI_STATE_ENABLED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True when mobile data is actually on. A cellular network object exists
     * even when data is switched off (it still carries voice / SMS), so only
     * count it when it also has the INTERNET capability - which is present
     * only when the data switch is on.
     */
    @SuppressWarnings("deprecation") // getAllNetworks(): only one-shot enumerator;
    //                                  the replacement is an async NetworkCallback.
    private static boolean isMobileDataConnected(ConnectivityManager cm) {
        if (cm == null) return false;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null
                        && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * WiFi SSID (network name), or null when unavailable / no permission.
     *
     * @SuppressWarnings deprecation: getConnectionInfo() is deprecated since
     * API 31, but it is still the only PUBLIC way to read the connected SSID
     * (the documented replacement NetworkCapabilities#getSSID is a system API
     * not exposed in the public SDK), so the call is kept.
     */
    @SuppressWarnings("deprecation")
    private static String getWifiSsid(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) return null;
        } else {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return null;
        }
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            String ssid = wm.getConnectionInfo().getSSID();
            if (ssid == null) return null;
            ssid = ssid.trim();
            if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            if (ssid.isEmpty() || "<unknown ssid>".equals(ssid)) return null;
            return ssid;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * IP of the hotspot AP interface (e.g. 192.168.43.1). When the hotspot is
     * on, the WiFi radio is in AP mode and the generic getLanIpAddress() may
     * pick a cellular interface instead, so scan the interfaces for a private
     * IPv4 that is not cellular / VPN.
     */
    private static String getHotspotIp(Context context) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase();
                if (n.startsWith("rmnet") || n.startsWith("wwan") || n.startsWith("ccmni")
                        || n.startsWith("tun") || n.startsWith("ppp")) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (isPrivateIpv4(ip)) return ip;
                }
            }
        } catch (Exception ignored) {
        }
        return MainActivity.getLanIpAddress();
    }

    private static boolean isPrivateIpv4(String ip) {
        if (ip == null) return false;
        try {
            String[] p = ip.split("\\.");
            if (p.length != 4) return false;
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            return a == 10
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 100 && b >= 64 && b <= 127); // CGNAT
        } catch (Exception e) {
            return false;
        }
    }

    private static String firstIpv4(LinkProperties lp) {
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                return a.getHostAddress();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Toggles. On stock Android 10+ apps can no longer flip these switches
    // programmatically (the methods silently no-op or throw), but several
    // OEM ROMs still honour them - so try the DIRECT path first, and only
    // fall back to the matching system panel when the direct call fails.
    // ------------------------------------------------------------------

    public static void toggleWifi(Context context, boolean enable) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            // setWifiEnabled() is deprecated on API 29+ (apps can no longer flip
            // WiFi directly there), but it still works on Android 9 and some OEM
            // ROMs - call it via reflection so the direct path keeps working
            // without a compile warning. Only fall back to the system panel when
            // it threw or returned false.
            Object res = WifiManager.class.getMethod("setWifiEnabled", boolean.class)
                    .invoke(wm, enable);
            if (Boolean.TRUE.equals(res)) return; // toggled directly
        } catch (Exception ignored) {
        }
        Toast.makeText(context, "系统不允许 App 直接切换，已打开系统面板", Toast.LENGTH_SHORT).show();
        openWifiPanel(context);
    }

    public static void toggleMobileData(Context context, boolean enable) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            Method m = ConnectivityManager.class.getMethod("setMobileDataEnabled", boolean.class);
            m.invoke(cm, enable);
            return; // reflection succeeded
        } catch (Exception ignored) {
        }
        Toast.makeText(context, "系统不允许 App 直接切换，已打开系统面板", Toast.LENGTH_SHORT).show();
        openInternetPanel(context);
    }

    public static void toggleHotspot(Context context, boolean enable) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            // setWifiApEnabled is a hidden API whose WifiConfiguration parameter
            // type is deprecated; resolve it by name so no deprecated type is
            // referenced in this source.
            Method m = WifiManager.class.getMethod("setWifiApEnabled",
                    Class.forName("android.net.wifi.WifiConfiguration"), boolean.class);
            m.invoke(wm, null, enable);
            return; // reflection succeeded
        } catch (Exception ignored) {
        }
        Toast.makeText(context, "系统不允许 App 直接切换，已打开系统面板", Toast.LENGTH_SHORT).show();
        openTetheringSettings(context);
    }

    public static void openWifiPanel(Context context) {
        try {
            Intent i = Build.VERSION.SDK_INT >= 29
                    ? new Intent(Settings.Panel.ACTION_WIFI)
                    : new Intent(Settings.ACTION_WIFI_SETTINGS);
            context.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    public static void openInternetPanel(Context context) {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 29) {
                i = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
            } else {
                i = new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS);
            }
            context.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    public static void openTetheringSettings(Context context) {
        // 热点设置页没有公开常量，用系统 action 字面量；个别 ROM 不支持时
        // 退回「网络和互联网」总设置页。
        try {
            context.startActivity(new Intent("android.settings.WIFI_TETHERING"));
        } catch (Exception e) {
            try {
                context.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }
}
