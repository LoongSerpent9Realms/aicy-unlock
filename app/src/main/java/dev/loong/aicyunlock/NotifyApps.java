package dev.loong.aicyunlock;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The app allow-list behind "播报应用".
 *
 * <p>Stored as a small JSON document in the module preferences and served to
 * Aicy's process through {@link ConfigProvider}. An empty list keeps the old
 * behaviour: every app is announced. Once the user picks at least one app,
 * notifications from everything else are dropped before they reach the speech
 * queue, which is what keeps promotional pushes out.
 */
final class NotifyApps {

    private NotifyApps() {
    }

    static List<String> load(Context context) {
        if (context == null) return new ArrayList<>();
        try {
            return parse(context.getSharedPreferences(ConfigProvider.PREFS,
                    Context.MODE_PRIVATE)
                    .getString(ConfigProvider.KEY_NOTIFY_APPS, ""));
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    static void save(Context context, List<String> packages) {
        if (context == null) return;
        // Small document, and the selection must survive an immediate process
        // death (the app is often killed right after a module update).
        context.getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
                .edit().putString(ConfigProvider.KEY_NOTIFY_APPS, serialize(packages))
                .commit();
    }

    static String serialize(List<String> packages) {
        JSONArray apps = new JSONArray();
        for (String pkg : normalized(packages)) apps.put(pkg);
        JSONObject root = new JSONObject();
        try {
            root.put("v", 1);
            root.put("apps", apps);
        } catch (Throwable ignored) {
            // org.json only throws on NaN/Infinity, which never occur here
        }
        return root.toString();
    }

    /** @return never null; skips malformed rows instead of dropping the list */
    static List<String> parse(String json) {
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        try {
            String raw = json.trim();
            JSONArray apps = raw.startsWith("[")
                    ? new JSONArray(raw)
                    : new JSONObject(raw).optJSONArray("apps");
            if (apps == null) return new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (int i = 0; i < apps.length(); i++) {
                String pkg = apps.optString(i, "").trim();
                if (!pkg.isEmpty()) values.add(pkg);
            }
            return normalized(values);
        } catch (Throwable ignored) {
            // corrupt document: behave as "no allow-list" rather than crash
            return new ArrayList<>();
        }
    }

    /** Deduplicates and sorts, so an unchanged selection keeps a stable payload. */
    private static List<String> normalized(List<String> packages) {
        Set<String> unique = new LinkedHashSet<>();
        if (packages != null) {
            for (String pkg : packages) {
                if (pkg == null) continue;
                String value = pkg.trim();
                if (!value.isEmpty()) unique.add(value);
            }
        }
        List<String> out = new ArrayList<>(unique);
        Collections.sort(out);
        return out;
    }
}
