package app.homeadbguard;

import android.content.Context;

import java.util.Set;

final class HomeMatcher {
    private HomeMatcher() {
    }

    static MatchResult evaluate(Context context, WifiState wifi) {
        String savedSsid = Prefs.homeSsid(context);
        Set<String> savedBssids = Prefs.homeBssids(context);
        boolean allowSsidOnly = Prefs.allowSsidOnly(context);

        if (!wifi.isUsable()) {
            return new MatchResult(false, "Wi-Fi details are unavailable; failing closed");
        }
        if (savedSsid.isEmpty()) {
            return new MatchResult(false, "No home Wi-Fi saved; failing closed");
        }
        if (!savedSsid.equals(wifi.ssid)) {
            return new MatchResult(false, "SSID does not match saved home Wi-Fi");
        }
        if (savedBssids.contains(Prefs.normalizeBssid(wifi.bssid))) {
            return new MatchResult(true, "SSID and BSSID match saved home Wi-Fi");
        }
        if (allowSsidOnly) {
            return new MatchResult(true, "SSID matches; BSSID did not match but SSID-only fallback is enabled");
        }
        return new MatchResult(false, "SSID matches but BSSID is not trusted; failing closed");
    }

    static final class MatchResult {
        final boolean atHome;
        final String reason;

        MatchResult(boolean atHome, String reason) {
            this.atHome = atHome;
            this.reason = reason == null ? "" : reason;
        }
    }
}
