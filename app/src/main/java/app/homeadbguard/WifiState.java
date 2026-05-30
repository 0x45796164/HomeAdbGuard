package app.homeadbguard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

final class WifiState {
    final String ssid;
    final String bssid;
    final String source;
    final boolean wifiTransportSeen;
    final boolean locationEnabled;
    final boolean hasFineLocation;
    final boolean hasNearbyWifiDevices;

    /** WifiInfo.SECURITY_TYPE_* (API 31), or null if unknown. */
    final Integer securityType;
    /** Frequency of the AP in MHz, or null if unknown. */
    final Integer frequencyMhz;
    /** WifiInfo.WIFI_STANDARD_* (API 30), or null if unknown. */
    final Integer wifiStandard;
    /** True when Multi-Link Operation is active (API 35+), null on older. */
    final Boolean mloActive;

    private WifiState(
            String ssid,
            String bssid,
            String source,
            boolean wifiTransportSeen,
            boolean locationEnabled,
            boolean hasFineLocation,
            boolean hasNearbyWifiDevices,
            Integer securityType,
            Integer frequencyMhz,
            Integer wifiStandard,
            Boolean mloActive
    ) {
        this.ssid = cleanSsid(ssid);
        this.bssid = Prefs.normalizeBssid(bssid);
        this.source = source == null ? "" : source;
        this.wifiTransportSeen = wifiTransportSeen;
        this.locationEnabled = locationEnabled;
        this.hasFineLocation = hasFineLocation;
        this.hasNearbyWifiDevices = hasNearbyWifiDevices;
        this.securityType = securityType;
        this.frequencyMhz = frequencyMhz;
        this.wifiStandard = wifiStandard;
        this.mloActive = mloActive;
    }

    private static WifiState bare(
            String ssid,
            String bssid,
            String source,
            boolean wifiTransportSeen,
            boolean locationEnabled,
            boolean hasFineLocation,
            boolean hasNearbyWifiDevices
    ) {
        return new WifiState(ssid, bssid, source, wifiTransportSeen,
                locationEnabled, hasFineLocation, hasNearbyWifiDevices,
                null, null, null, null);
    }

    boolean isUsable() {
        return !ssid.isEmpty()
                && !WifiManager.UNKNOWN_SSID.equals(ssid)
                && !bssid.isEmpty();
    }

    /**
     * True when the OS reported a Wi-Fi transport but the SSID/BSSID came back
     * empty — which on Android 13+ means the caller is not foreground and the
     * Wi-Fi identity is privacy-redacted. The fix is to defer to the FGS,
     * which reads from foreground context.
     */
    boolean isRedacted() {
        return wifiTransportSeen && !isUsable();
    }

    String band() {
        if (frequencyMhz == null) return null;
        int f = frequencyMhz;
        if (f < 2495) return "2.4 GHz";
        if (f < 5925) return "5 GHz";
        return "6 GHz";
    }

    Integer channel() {
        if (frequencyMhz == null) return null;
        int f = frequencyMhz;
        if (f >= 2412 && f <= 2484) {
            return f == 2484 ? 14 : (f - 2407) / 5;
        }
        if (f >= 5170 && f <= 5825) return (f - 5000) / 5;
        if (f >= 5955 && f <= 7115) return (f - 5950) / 5;
        return null;
    }

    static WifiState current(Context context) {
        boolean locEnabled = isLocationEnabled(context);
        boolean fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean nearby = context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                == PackageManager.PERMISSION_GRANTED;

        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        boolean sawWifi = false;

        WifiState fromAll = fromAllNetworks(cm, locEnabled, fine, nearby);
        if (fromAll.isUsable()) return fromAll;
        if (fromAll.wifiTransportSeen) sawWifi = true;

        WifiState fromActive = fromActiveNetwork(cm, locEnabled, fine, nearby);
        if (fromActive.isUsable()) return fromActive;
        if (fromActive.wifiTransportSeen) sawWifi = true;

        WifiState fromManager = fromWifiManager(context, locEnabled, fine, nearby);
        if (fromManager.isUsable()) return fromManager;
        if (fromManager.wifiTransportSeen) sawWifi = true;

        if (sawWifi) {
            return bare("", "", "redacted-or-unusable-wifi", true, locEnabled, fine, nearby);
        }
        return bare("", "", "none", false, locEnabled, fine, nearby);
    }

    private static WifiState fromAllNetworks(
            ConnectivityManager cm,
            boolean locEnabled,
            boolean fine,
            boolean nearby
    ) {
        if (cm == null) {
            return bare("", "", "ConnectivityManager unavailable", false, locEnabled, fine, nearby);
        }
        try {
            boolean sawWifi = false;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                sawWifi = true;
                WifiState state = fromCapabilities(caps, "ConnectivityManager#getAllNetworks", locEnabled, fine, nearby);
                if (state.isUsable()) return state;
            }
            return bare("", "", "ConnectivityManager#getAllNetworks", sawWifi, locEnabled, fine, nearby);
        } catch (SecurityException ignored) {
            return bare("", "", "ConnectivityManager#getAllNetworks SecurityException", false, locEnabled, fine, nearby);
        }
    }

    private static WifiState fromActiveNetwork(
            ConnectivityManager cm,
            boolean locEnabled,
            boolean fine,
            boolean nearby
    ) {
        if (cm == null) {
            return bare("", "", "ConnectivityManager unavailable", false, locEnabled, fine, nearby);
        }
        try {
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            return fromCapabilities(caps, "ConnectivityManager#getActiveNetwork", locEnabled, fine, nearby);
        } catch (SecurityException ignored) {
            return bare("", "", "ConnectivityManager#getActiveNetwork SecurityException", false, locEnabled, fine, nearby);
        }
    }

    static WifiState fromCapabilities(
            NetworkCapabilities caps,
            String source,
            boolean locationEnabled,
            boolean hasFineLocation,
            boolean hasNearbyWifiDevices
    ) {
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return bare("", "", source, false, locationEnabled, hasFineLocation, hasNearbyWifiDevices);
        }

        TransportInfo info = caps.getTransportInfo();
        if (info instanceof WifiInfo) {
            WifiInfo wifiInfo = (WifiInfo) info;
            Integer security = readSecurityType(wifiInfo);
            Integer freq = readFrequency(wifiInfo);
            Integer standard = readWifiStandard(wifiInfo);
            Boolean mlo = readMloActive(wifiInfo);
            return new WifiState(
                    wifiInfo.getSSID(),
                    wifiInfo.getBSSID(),
                    source,
                    true,
                    locationEnabled,
                    hasFineLocation,
                    hasNearbyWifiDevices,
                    security,
                    freq,
                    standard,
                    mlo
            );
        }

        return bare("", "", source, true, locationEnabled, hasFineLocation, hasNearbyWifiDevices);
    }

    private static WifiState fromWifiManager(
            Context context,
            boolean locationEnabled,
            boolean hasFineLocation,
            boolean hasNearbyWifiDevices
    ) {
        WifiManager wm = context.getSystemService(WifiManager.class);
        if (wm == null) {
            return bare("", "", "WifiManager unavailable", false, locationEnabled, hasFineLocation, hasNearbyWifiDevices);
        }

        try {
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) {
                return bare("", "", "WifiManager null", false, locationEnabled, hasFineLocation, hasNearbyWifiDevices);
            }
            boolean sawWifi = info.getNetworkId() != -1 || info.getSupplicantState() != null;
            Integer security = readSecurityType(info);
            Integer freq = readFrequency(info);
            Integer standard = readWifiStandard(info);
            Boolean mlo = readMloActive(info);
            return new WifiState(
                    info.getSSID(),
                    info.getBSSID(),
                    "WifiManager#getConnectionInfo",
                    sawWifi,
                    locationEnabled,
                    hasFineLocation,
                    hasNearbyWifiDevices,
                    security,
                    freq,
                    standard,
                    mlo
            );
        } catch (SecurityException ignored) {
            return bare("", "", "WifiManager SecurityException", true, locationEnabled, hasFineLocation, hasNearbyWifiDevices);
        }
    }

    private static Integer readSecurityType(WifiInfo info) {
        try {
            int t = info.getCurrentSecurityType();
            return t < 0 ? null : t;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Integer readFrequency(WifiInfo info) {
        try {
            int f = info.getFrequency();
            return f <= 0 ? null : f;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Integer readWifiStandard(WifiInfo info) {
        try {
            int s = info.getWifiStandard();
            return s == ScanResultStandardUnknown ? null : s;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** ScanResult.WIFI_STANDARD_UNKNOWN constant, inlined to avoid the import. */
    private static final int ScanResultStandardUnknown = 0;

    private static Boolean readMloActive(WifiInfo info) {
        if (Build.VERSION.SDK_INT < 35) return null;
        try {
            // "MLO active" = currently associated via more than one link. Both
            // getAssociatedMloLinks() and getApMloLinkId() are API 35+.
            return !info.getAssociatedMloLinks().isEmpty();
        } catch (RuntimeException ignored) {
            return null;
        } catch (NoSuchMethodError ignored) {
            return null;
        }
    }

    private static boolean isLocationEnabled(Context context) {
        LocationManager lm = context.getSystemService(LocationManager.class);
        if (lm == null) return false;
        try {
            return lm.isLocationEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String cleanSsid(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
