package app.homeadbguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class Prefs {
    static final String NAME = "home_adb_guard";
    static final String KEY_HOME_SSID = "home_ssid";
    static final String KEY_HOME_BSSIDS = "home_bssids";
    static final String KEY_MONITORING = "monitoring";
    static final String KEY_ALLOW_SSID_ONLY = "allow_ssid_only";
    static final String KEY_LAST_EVALUATION = "last_evaluation";
    static final String KEY_LAST_APPLY_RESULT = "last_apply_result";
    static final String KEY_DECISION_HISTORY = "decision_history";

    private static final int HISTORY_MAX = 10;
    private static final String HISTORY_SEP = "\n";

    private Prefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static String homeSsid(Context context) {
        return get(context).getString(KEY_HOME_SSID, "");
    }

    static Set<String> homeBssids(Context context) {
        String raw = get(context).getString(KEY_HOME_BSSIDS, "");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String item : raw.split("\\|")) {
            String normalized = normalizeBssid(item);
            if (!normalized.isEmpty()) out.add(normalized);
        }
        return out;
    }

    static boolean monitoring(Context context) {
        return get(context).getBoolean(KEY_MONITORING, false);
    }

    static boolean allowSsidOnly(Context context) {
        return get(context).getBoolean(KEY_ALLOW_SSID_ONLY, false);
    }

    static void setMonitoring(Context context, boolean enabled) {
        get(context).edit().putBoolean(KEY_MONITORING, enabled).apply();
    }

    static void setAllowSsidOnly(Context context, boolean enabled) {
        get(context).edit().putBoolean(KEY_ALLOW_SSID_ONLY, enabled).apply();
    }

    static void saveHome(Context context, WifiState wifi) {
        String bssid = normalizeBssid(wifi.bssid);
        get(context).edit()
                .putString(KEY_HOME_SSID, safe(wifi.ssid))
                .putString(KEY_HOME_BSSIDS, bssid)
                .apply();
    }

    static void addBssid(Context context, String bssid) {
        LinkedHashSet<String> set = new LinkedHashSet<>(homeBssids(context));
        String normalized = normalizeBssid(bssid);
        if (!normalized.isEmpty()) set.add(normalized);
        get(context).edit().putString(KEY_HOME_BSSIDS, join(set)).apply();
    }

    static void removeBssid(Context context, String bssid) {
        String target = normalizeBssid(bssid);
        if (target.isEmpty()) return;
        LinkedHashSet<String> set = new LinkedHashSet<>(homeBssids(context));
        if (!set.remove(target)) return;
        get(context).edit().putString(KEY_HOME_BSSIDS, join(set)).apply();
    }

    static void appendDecision(Context context, String entry) {
        if (entry == null) entry = "";
        entry = entry.replace('\n', ' ').replace('\r', ' ').trim();
        if (entry.isEmpty()) return;
        SharedPreferences p = get(context);
        String existing = p.getString(KEY_DECISION_HISTORY, "");
        List<String> kept = new ArrayList<>();
        kept.add(entry);
        if (!existing.isEmpty()) {
            for (String e : existing.split(HISTORY_SEP)) {
                if (kept.size() >= HISTORY_MAX) break;
                if (!e.isEmpty()) kept.add(e);
            }
        }
        p.edit().putString(KEY_DECISION_HISTORY, String.join(HISTORY_SEP, kept)).apply();
    }

    static List<String> decisionHistory(Context context) {
        String raw = get(context).getString(KEY_DECISION_HISTORY, "");
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String e : raw.split(HISTORY_SEP)) if (!e.isEmpty()) out.add(e);
        return out;
    }

    static void clearHome(Context context) {
        get(context).edit()
                .remove(KEY_HOME_SSID)
                .remove(KEY_HOME_BSSIDS)
                .putBoolean(KEY_ALLOW_SSID_ONLY, false)
                .apply();
    }

    static void setLastEvaluation(Context context, String text) {
        get(context).edit().putString(KEY_LAST_EVALUATION, safe(text)).apply();
    }

    static void setLastApplyResult(Context context, String text) {
        get(context).edit().putString(KEY_LAST_APPLY_RESULT, safe(text)).apply();
    }

    static String lastEvaluation(Context context) {
        return get(context).getString(KEY_LAST_EVALUATION, "-");
    }

    static String lastApplyResult(Context context) {
        return get(context).getString(KEY_LAST_APPLY_RESULT, "-");
    }

    private static String join(Set<String> items) {
        return String.join("|", items);
    }

    static String safe(String s) {
        return s == null ? "" : s;
    }

    static String normalizeBssid(String bssid) {
        if (bssid == null) return "";
        String normalized = bssid.trim().toLowerCase();
        if (normalized.equals("02:00:00:00:00:00")) return "";
        return normalized;
    }

    static String bssidDisplay(Set<String> bssids) {
        if (bssids == null || bssids.isEmpty()) return "-";
        return Arrays.toString(bssids.toArray(new String[0]));
    }
}
