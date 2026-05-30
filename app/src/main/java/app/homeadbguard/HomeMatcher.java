package app.homeadbguard;

import android.content.Context;

import java.util.Set;

final class HomeMatcher {
    private HomeMatcher() {
    }

    static MatchResult evaluate(Context context, WifiState wifi) {
        if (!wifi.isUsable()) {
            return new MatchResult(false, "Wi-Fi details are unavailable; failing closed");
        }

        // Minimum-protection gate runs first. When set, this is a hard refusal
        // regardless of SSID/BSSID match — the user has declared that ADB
        // should not be on networks below this strength even if the SSID
        // matches.
        int minSec = Prefs.minSecurityType(context);
        if (minSec != Prefs.SEC_UNSET) {
            int currentRank = WifiNames.securityRank(wifi.securityType);
            int minRank = WifiNames.securityRank(minSec);
            if (currentRank < minRank) {
                return new MatchResult(false,
                        "Below minimum protection (need " + WifiNames.securityName(minSec)
                                + ", got " + WifiNames.securityName(wifi.securityType) + ")");
            }
        }

        String savedSsid = Prefs.homeSsid(context);
        Set<String> savedBssids = Prefs.homeBssids(context);
        boolean allowSsidOnly = Prefs.allowSsidOnly(context);

        if (savedSsid.isEmpty()) {
            return new MatchResult(false, "No home Wi-Fi saved; failing closed");
        }
        if (!savedSsid.equals(wifi.ssid)) {
            return new MatchResult(false, "SSID does not match saved home Wi-Fi");
        }

        boolean bssidMatch = savedBssids.contains(Prefs.normalizeBssid(wifi.bssid));
        if (!bssidMatch && !allowSsidOnly) {
            return new MatchResult(false, "SSID matches but BSSID is not trusted; failing closed");
        }

        // Strict fingerprint runs after SSID/BSSID pass. Only the captured
        // expected fields participate; unset fields (Prefs sentinel -1) are
        // skipped, as are fields the current Wi-Fi doesn't expose (null).
        if (Prefs.strictFingerprint(context)) {
            MatchResult strict = checkStrictFingerprint(context, wifi);
            if (strict != null) return strict;
        }

        if (bssidMatch) {
            return new MatchResult(true, "SSID and BSSID match saved home Wi-Fi");
        }
        return new MatchResult(true,
                "SSID matches; BSSID did not match but SSID-only fallback is enabled");
    }

    private static MatchResult checkStrictFingerprint(Context context, WifiState wifi) {
        int expectedSec = Prefs.expectedSecurityType(context);
        if (expectedSec != Prefs.SEC_UNSET
                && wifi.securityType != null
                && expectedSec != wifi.securityType) {
            return new MatchResult(false,
                    "Security type mismatch (expected " + WifiNames.securityName(expectedSec)
                            + ", got " + WifiNames.securityName(wifi.securityType) + ")");
        }

        int expectedFreq = Prefs.expectedFrequencyMhz(context);
        if (expectedFreq != Prefs.FREQ_UNSET
                && wifi.frequencyMhz != null
                && expectedFreq != wifi.frequencyMhz) {
            return new MatchResult(false,
                    "Frequency mismatch (expected " + expectedFreq + " MHz, got "
                            + wifi.frequencyMhz + " MHz)");
        }

        int expectedStd = Prefs.expectedWifiStandard(context);
        if (expectedStd != Prefs.STD_UNSET
                && wifi.wifiStandard != null
                && expectedStd != wifi.wifiStandard) {
            return new MatchResult(false,
                    "Wi-Fi standard mismatch (expected " + WifiNames.standardName(expectedStd)
                            + ", got " + WifiNames.standardName(wifi.wifiStandard) + ")");
        }

        int expectedMlo = Prefs.expectedMloActive(context);
        if (expectedMlo != Prefs.MLO_UNSET && wifi.mloActive != null) {
            boolean expectedMloOn = expectedMlo == 1;
            if (expectedMloOn != wifi.mloActive) {
                return new MatchResult(false,
                        "MLO mismatch (expected " + (expectedMloOn ? "on" : "off")
                                + ", got " + (wifi.mloActive ? "on" : "off") + ")");
            }
        }

        return null;
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
