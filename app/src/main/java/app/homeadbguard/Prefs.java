package app.homeadbguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    static final String KEY_SNOOZE_UNTIL = "snooze_until_millis";
    static final String KEY_PAUSE_UNTIL = "pause_until_millis";

    /** {@link #pauseUntil} sentinel: paused with no timer, until the user resumes. */
    static final long PAUSE_INDEFINITE = -1L;
    static final String KEY_NETWORK_WATCH_ARMED = "network_watch_armed";
    static final String KEY_LAST_DECISION_PRESENT = "last_decision_present";
    static final String KEY_LAST_DECISION_AT_HOME = "last_decision_at_home";
    static final String KEY_LAST_DECISION_REASON = "last_decision_reason";
    static final String KEY_LAST_ADB_CONFIDENCE = "last_adb_confidence";
    static final String KEY_LAST_ADB_SUMMARY = "last_adb_summary";
    static final String KEY_LAST_MODE = "last_mode";
    static final String KEY_ADB_PORT = "adb_wireless_port";
    static final String KEY_PORT_DISCOVERY = "port_discovery_enabled";

    static final String KEY_STRICT_FINGERPRINT = "strict_fingerprint";
    static final String KEY_EXPECTED_SECURITY_TYPE = "expected_security_type";
    static final String KEY_EXPECTED_FREQUENCY_MHZ = "expected_frequency_mhz";
    static final String KEY_EXPECTED_WIFI_STANDARD = "expected_wifi_standard";
    static final String KEY_EXPECTED_MLO_ACTIVE = "expected_mlo_active";
    static final String KEY_MIN_SECURITY_TYPE = "min_security_type";

    /** Sentinel "no value captured / no enforcement" for the int-encoded fingerprint slots. */
    static final int SEC_UNSET = -1;
    static final int FREQ_UNSET = -1;
    static final int STD_UNSET = -1;
    static final int MLO_UNSET = -1;

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
        SharedPreferences.Editor edit = get(context).edit().putBoolean(KEY_MONITORING, enabled);
        if (!enabled) {
            edit.remove(KEY_LAST_DECISION_PRESENT)
                    .remove(KEY_LAST_DECISION_AT_HOME)
                    .remove(KEY_LAST_DECISION_REASON);
        }
        edit.apply();
    }

    static boolean lastDecisionPresent(Context context) {
        return get(context).getBoolean(KEY_LAST_DECISION_PRESENT, false);
    }

    static boolean lastDecisionAtHome(Context context) {
        return get(context).getBoolean(KEY_LAST_DECISION_AT_HOME, false);
    }

    static String lastDecisionReason(Context context) {
        return get(context).getString(KEY_LAST_DECISION_REASON, "");
    }

    static void setLastDecision(Context context, boolean atHome, String reason) {
        get(context).edit()
                .putBoolean(KEY_LAST_DECISION_PRESENT, true)
                .putBoolean(KEY_LAST_DECISION_AT_HOME, atHome)
                .putString(KEY_LAST_DECISION_REASON, safe(reason))
                .apply();
    }

    static boolean strictFingerprint(Context context) {
        return get(context).getBoolean(KEY_STRICT_FINGERPRINT, false);
    }

    static void setStrictFingerprint(Context context, boolean enabled) {
        get(context).edit().putBoolean(KEY_STRICT_FINGERPRINT, enabled).apply();
    }

    static int expectedSecurityType(Context context) {
        return get(context).getInt(KEY_EXPECTED_SECURITY_TYPE, SEC_UNSET);
    }

    static int expectedFrequencyMhz(Context context) {
        return get(context).getInt(KEY_EXPECTED_FREQUENCY_MHZ, FREQ_UNSET);
    }

    static int expectedWifiStandard(Context context) {
        return get(context).getInt(KEY_EXPECTED_WIFI_STANDARD, STD_UNSET);
    }

    static int expectedMloActive(Context context) {
        return get(context).getInt(KEY_EXPECTED_MLO_ACTIVE, MLO_UNSET);
    }

    static boolean hasCapturedFingerprint(Context context) {
        SharedPreferences p = get(context);
        return p.getInt(KEY_EXPECTED_SECURITY_TYPE, SEC_UNSET) != SEC_UNSET
                || p.getInt(KEY_EXPECTED_FREQUENCY_MHZ, FREQ_UNSET) != FREQ_UNSET
                || p.getInt(KEY_EXPECTED_WIFI_STANDARD, STD_UNSET) != STD_UNSET
                || p.getInt(KEY_EXPECTED_MLO_ACTIVE, MLO_UNSET) != MLO_UNSET;
    }

    static void captureFingerprint(Context context, WifiState wifi) {
        SharedPreferences.Editor edit = get(context).edit();
        edit.putInt(KEY_EXPECTED_SECURITY_TYPE,
                wifi.securityType == null ? SEC_UNSET : wifi.securityType);
        edit.putInt(KEY_EXPECTED_FREQUENCY_MHZ,
                wifi.frequencyMhz == null ? FREQ_UNSET : wifi.frequencyMhz);
        edit.putInt(KEY_EXPECTED_WIFI_STANDARD,
                wifi.wifiStandard == null ? STD_UNSET : wifi.wifiStandard);
        edit.putInt(KEY_EXPECTED_MLO_ACTIVE,
                wifi.mloActive == null ? MLO_UNSET : (wifi.mloActive ? 1 : 0));
        edit.apply();
    }

    static void clearFingerprint(Context context) {
        get(context).edit()
                .remove(KEY_EXPECTED_SECURITY_TYPE)
                .remove(KEY_EXPECTED_FREQUENCY_MHZ)
                .remove(KEY_EXPECTED_WIFI_STANDARD)
                .remove(KEY_EXPECTED_MLO_ACTIVE)
                .apply();
    }

    static int minSecurityType(Context context) {
        return get(context).getInt(KEY_MIN_SECURITY_TYPE, SEC_UNSET);
    }

    static void setMinSecurityType(Context context, int type) {
        get(context).edit().putInt(KEY_MIN_SECURITY_TYPE, type).apply();
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

    static long snoozeUntil(Context context) {
        return get(context).getLong(KEY_SNOOZE_UNTIL, 0L);
    }

    static void setSnoozeUntil(Context context, long millis) {
        SharedPreferences.Editor edit = get(context).edit().putLong(KEY_SNOOZE_UNTIL, millis);
        if (millis != 0L) edit.putLong(KEY_PAUSE_UNTIL, 0L);
        edit.apply();
    }

    static boolean isSnoozeActive(Context context) {
        return System.currentTimeMillis() < snoozeUntil(context);
    }

    static long snoozeRemainingMs(Context context) {
        long until = snoozeUntil(context);
        long now = System.currentTimeMillis();
        return until > now ? (until - now) : 0L;
    }

    // ---- Pause (temporary OFF while home). Mutually exclusive with snooze. ----

    static long pauseUntil(Context context) {
        return get(context).getLong(KEY_PAUSE_UNTIL, 0L);
    }

    /** @param millis a future timestamp, {@link #PAUSE_INDEFINITE}, or 0 to clear. */
    static void setPauseUntil(Context context, long millis) {
        SharedPreferences.Editor edit = get(context).edit().putLong(KEY_PAUSE_UNTIL, millis);
        if (millis != 0L) edit.putLong(KEY_SNOOZE_UNTIL, 0L); // opposites cannot coexist
        edit.apply();
    }

    static boolean isPauseActive(Context context) {
        long until = pauseUntil(context);
        return until == PAUSE_INDEFINITE || (until > 0L && System.currentTimeMillis() < until);
    }

    /** @return remaining ms for a timed pause, {@link #PAUSE_INDEFINITE} if indefinite, else 0. */
    static long pauseRemainingMs(Context context) {
        long until = pauseUntil(context);
        if (until == PAUSE_INDEFINITE) return PAUSE_INDEFINITE;
        long now = System.currentTimeMillis();
        return until > now ? (until - now) : 0L;
    }

    static boolean networkWatchArmed(Context context) {
        return get(context).getBoolean(KEY_NETWORK_WATCH_ARMED, false);
    }

    static void setNetworkWatchArmed(Context context, boolean armed) {
        get(context).edit().putBoolean(KEY_NETWORK_WATCH_ARMED, armed).apply();
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
                .remove(KEY_STRICT_FINGERPRINT)
                .remove(KEY_EXPECTED_SECURITY_TYPE)
                .remove(KEY_EXPECTED_FREQUENCY_MHZ)
                .remove(KEY_EXPECTED_WIFI_STANDARD)
                .remove(KEY_EXPECTED_MLO_ACTIVE)
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

    static void setLastAdbState(Context context, String confidence, String summary) {
        get(context).edit()
                .putString(KEY_LAST_ADB_CONFIDENCE, safe(confidence))
                .putString(KEY_LAST_ADB_SUMMARY, safe(summary))
                .apply();
    }

    static String lastAdbConfidence(Context context) {
        return get(context).getString(KEY_LAST_ADB_CONFIDENCE, "");
    }

    static String lastAdbSummary(Context context) {
        return get(context).getString(KEY_LAST_ADB_SUMMARY, "-");
    }

    static void setLastMode(Context context, String mode) {
        get(context).edit().putString(KEY_LAST_MODE, safe(mode)).apply();
    }

    static String lastMode(Context context) {
        return get(context).getString(KEY_LAST_MODE, "");
    }

    /** Opt-in: best-effort mDNS discovery of the wireless port. Off by default. */
    static boolean portDiscoveryEnabled(Context context) {
        return get(context).getBoolean(KEY_PORT_DISCOVERY, false);
    }

    static void setPortDiscoveryEnabled(Context context, boolean enabled) {
        get(context).edit().putBoolean(KEY_PORT_DISCOVERY, enabled).apply();
    }

    /** Last discovered wireless-debugging connect port, or 0 if unknown. */
    static int adbPort(Context context) {
        return get(context).getInt(KEY_ADB_PORT, 0);
    }

    /** Stores the port; no-op (no listener churn) when the value is unchanged. */
    static void setAdbPort(Context context, int port) {
        if (adbPort(context) == port) return;
        get(context).edit().putInt(KEY_ADB_PORT, port).apply();
    }

    private static String join(Set<String> items) {
        return String.join("|", items);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    static String normalizeBssid(String bssid) {
        if (bssid == null) return "";
        String normalized = bssid.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("02:00:00:00:00:00")) return "";
        return normalized;
    }
}
