package io.lanprojects.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Remembers the servers the user connected to in "connect to other device"
 * mode, so the launch screen can offer them for quick re-connect. Stored as
 * a JSON array string to preserve most-recent-first ordering.
 *
 * Entries are deduplicated by a NORMALIZED key (host:port, scheme and trailing
 * slash stripped) so that "192.168.1.8", "192.168.1.8:3000" and
 * "http://192.168.1.8:3000/" all collapse to one entry. Without this, repeated
 * connect/back cycles accumulated a different row each time the address was
 * typed with a different shape.
 */
public class DeviceHistory {

    private static final String PREFS = "device_history";
    private static final String KEY = "recent";
    private static final int MAX = 8;

    private DeviceHistory() {
    }

    /** Canonical "host:port" key used for dedup (empty string for invalid). */
    private static String keyOf(String url) {
        if (url == null) return "";
        String a = stripSecret(url).trim().replaceAll("^https?://", "").toLowerCase();
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        if (a.isEmpty()) return "";
        if (!a.contains(":")) a = a + ":3000";
        return a;
    }

    /**
     * Drop a URL's query string and fragment.
     *
     * SECURITY: the QR/scan flow can carry the pairing secret in the query
     * string (?secret=&lt;26-char base32&gt;). That secret is the long-lived master
     * key for the device pair, so persisting it to disk would let anyone able to
     * read the app's private storage (root, adb backup, a forensic image, some
     * vendors' cloud backups) derive the room id and session key offline and
     * impersonate this device — no brute force needed. The history list only
     * needs host:port to offer a quick reconnect, so the query is removed before
     * anything is written to disk.
     */
    static String stripSecret(String url) {
        if (url == null) return null;
        int q = url.indexOf('?');
        int h = url.indexOf('#');
        int cut = -1;
        if (q >= 0) cut = q;
        if (h >= 0 && (cut < 0 || h < cut)) cut = h;
        return cut < 0 ? url : url.substring(0, cut);
    }

    public static List<String> getAll(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = sp.getString(KEY, "[]");
        List<String> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i));
        } catch (JSONException ignored) {
        }
        // Defensive: collapse any duplicates (e.g. leftover from earlier bugs)
        // by normalized key, keeping the first occurrence.
        // Also sanitize on READ: entries written by older builds may still embed
        // the pairing secret in their query string, so strip it here and rewrite
        // the store if anything changed. This purges secrets already on disk.
        Set<String> seen = new HashSet<>();
        List<String> deduped = new ArrayList<>();
        boolean changed = false;
        for (String s : list) {
            String clean = stripSecret(s);
            if (!clean.equals(s)) changed = true;
            String k = keyOf(clean);
            if (k.isEmpty() || seen.add(k)) deduped.add(clean);
        }
        if (changed) save(context, deduped);
        return deduped;
    }

    /** Add a server URL at the front (deduplicated by normalized key), capped at MAX. */
    public static void add(Context context, String url) {
        // SECURITY: never persist a pairing secret that may ride in the query
        // string (see stripSecret). Store only the host:port form.
        String safe = stripSecret(url);
        if (safe == null) return;
        String key = keyOf(safe);
        if (key.isEmpty()) return;
        List<String> list = new ArrayList<>();
        list.add(safe);
        for (String s : getAll(context)) {
            if (!keyOf(s).equals(key)) list.add(s);
        }
        while (list.size() > MAX) list.remove(list.size() - 1);
        save(context, list);
    }

    public static void remove(Context context, String url) {
        String key = keyOf(url);
        List<String> list = new ArrayList<>();
        for (String s : getAll(context)) {
            if (!keyOf(s).equals(key)) list.add(s);
        }
        save(context, list);
    }

    public static void clear(Context context) {
        save(context, new ArrayList<>());
    }

    private static void save(Context context, List<String> list) {
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }
}
