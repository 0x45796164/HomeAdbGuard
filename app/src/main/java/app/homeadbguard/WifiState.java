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

final class WifiState {
    final String ssid;
    final String bssid;
    final String source;
    final boolean wifiTransportSeen;
    final boolean locationEnabled;
    final boolean hasFineLocation;
    final boolean hasNearbyWifiDevices;

    private WifiState(
            String ssid,
            String bssid,
            String source,
            boolean wifiTransportSeen,
            boolean locationEnabled,
            boolean hasFineLocation,
            boolean hasNearbyWifiDevices
    ) {
        this.ssid = cleanSsid(ssid);
        this.bssid = Prefs.normalizeBssid(bssid);
        this.source = source == null ? "" : source;
        this.wifiTransportSeen = wifiTransportSeen;
        this.locationEnabled = locationEnabled;
        this.hasFineLocation = hasFineLocation;
        this.hasNearbyWifiDevices = hasNearbyWifiDevices;
    }

    boolean isUsable() {
        return !ssid.isEmpty()
                && !WifiManager.UNKNOWN_SSID.equals(ssid)
                && !bssid.isEmpty();
    }

    static WifiState current(Context context) {
        boolean locEnabled = isLocationEnabled(context);
        boolean fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean nearby = context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                == PackageManager.PERMISSION_GRANTED;

        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm != null) {
            boolean sawWifi = false;
            try {
                for (Network network : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        sawWifi = true;
                        WifiState state = fromCapabilities(caps, "ConnectivityManager#getAllNetworks", locEnabled, fine, nearby);
                        if (state.isUsable()) return state;
                    }
                }
            } catch (SecurityException ignored) {
            }

            try {
                Network active = cm.getActiveNetwork();
                NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
                WifiState state = fromCapabilities(caps, "ConnectivityManager#getActiveNetwork", locEnabled, fine, nearby);
                if (state.isUsable()) return state;
                if (state.wifiTransportSeen) sawWifi = true;
            } catch (SecurityException ignored) {
            }

            WifiState fallback = fromWifiManager(context, locEnabled, fine, nearby);
            if (fallback.isUsable()) return fallback;
            if (fallback.wifiTransportSeen || sawWifi) {
                return new WifiState(fallback.ssid, fallback.bssid, "redacted-or-unusable-wifi", true, locEnabled, fine, nearby);
            }
        }

        WifiState fallback = fromWifiManager(context, locEnabled, fine, nearby);
        if (fallback.isUsable()) return fallback;
        return new WifiState("", "", "none", false, locEnabled, fine, nearby);
    }

    static WifiState fromCapabilities(
            NetworkCapabilities caps,
            String source,
            boolean locationEnabled,
            boolean hasFineLocation,
            boolean hasNearbyWifiDevices
    ) {
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return new WifiState("", "", source, false, locationEnabled, hasFineLocation, hasNearbyWifiDevices);
        }

        TransportInfo info = caps.getTransportInfo();
        if (info instanceof WifiInfo) {
            WifiInfo wifiInfo = (WifiInfo) info;
            return new WifiState(
                    wifiInfo.getSSID(),
                    wifiInfo.getBSSID(),
                    source,
                    true,
                    locationEnabled,
                    hasFineLocation,
                    hasNearbyWifiDevices
            );
        }

        return new WifiState("", "", source, true, locationEnabled, hasFineLocation, hasNearbyWifiDevices);
    }

    private static WifiState fromWifiManager(
            Context context,
            boolean locationEnabled,
            boolean hasFineLocation,
            boolean hasNearbyWifiDevices
    ) {
        WifiManager wm = context.getSystemService(WifiManager.class);
        if (wm == null) {
            return new WifiState("", "", "WifiManager unavailable", false, locationEnabled, hasFineLocation, hasNearbyWifiDevices);
        }

        try {
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) {
                return new WifiState("", "", "WifiManager null", false, locationEnabled, hasFineLocation, hasNearbyWifiDevices);
            }
            boolean sawWifi = info.getNetworkId() != -1 || info.getSupplicantState() != null;
            return new WifiState(info.getSSID(), info.getBSSID(), "WifiManager#getConnectionInfo", sawWifi, locationEnabled, hasFineLocation, hasNearbyWifiDevices);
        } catch (SecurityException ignored) {
            return new WifiState("", "", "WifiManager SecurityException", true, locationEnabled, hasFineLocation, hasNearbyWifiDevices);
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
