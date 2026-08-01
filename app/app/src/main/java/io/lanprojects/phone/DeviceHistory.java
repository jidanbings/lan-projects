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
        String a = url.trim().replaceAll("^https?://", "").toLowerCase();
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        if (a.isEmpty()) return "";
        if (!a.contains(":")) a = a + ":3000";
        return a;
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
        Set<String> seen = new HashSet<>();
        List<String> deduped = new ArrayList<>();
        for (String s : list) {
            String k = keyOf(s);
            if (k.isEmpty() || seen.add(k)) deduped.add(s);
        }
        return deduped;
    }

    /** Add a server URL at the front (deduplicated by normalized key), capped at MAX. */
    public static void add(Context context, String url) {
        String key = keyOf(url);
        if (key.isEmpty()) return;
        List<String> list = new ArrayList<>();
        list.add(url);
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
