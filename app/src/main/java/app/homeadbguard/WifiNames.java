package app.homeadbguard;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;

/**
 * Display-name and rank helpers for the integer Wi-Fi constants exposed by
 * {@link android.net.wifi.WifiInfo}. Kept in one place so the UI and
 * {@link HomeMatcher}'s mismatch reasons stay consistent.
 */
final class WifiNames {
    private WifiNames() {
    }

    static String securityName(Integer type) {
        if (type == null) return "Unknown";
        switch (type) {
            case WifiInfo.SECURITY_TYPE_OPEN: return "Open";
            case WifiInfo.SECURITY_TYPE_WEP: return "WEP";
            case WifiInfo.SECURITY_TYPE_PSK: return "WPA2-Personal";
            case WifiInfo.SECURITY_TYPE_EAP: return "WPA2-Enterprise";
            case WifiInfo.SECURITY_TYPE_SAE: return "WPA3-Personal";
            case WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT: return "WPA3-Enterprise 192-bit";
            case WifiInfo.SECURITY_TYPE_OWE: return "OWE";
            case WifiInfo.SECURITY_TYPE_WAPI_PSK: return "WAPI-PSK";
            case WifiInfo.SECURITY_TYPE_WAPI_CERT: return "WAPI-Cert";
            case WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE: return "WPA3-Enterprise";
            case WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2: return "Passpoint R1/R2";
            case WifiInfo.SECURITY_TYPE_PASSPOINT_R3: return "Passpoint R3";
            case WifiInfo.SECURITY_TYPE_DPP: return "DPP";
            default: return "Type " + type;
        }
    }

    /**
     * Monotonic strength ranking for the {@link WifiInfo#SECURITY_TYPE_OPEN}
     * family. Used by the minimum-protection gate.
     */
    static int securityRank(Integer type) {
        if (type == null) return -1;
        switch (type) {
            case WifiInfo.SECURITY_TYPE_OPEN:
            case WifiInfo.SECURITY_TYPE_OWE:
                return 0;
            case WifiInfo.SECURITY_TYPE_WEP:
                return 1;
            case WifiInfo.SECURITY_TYPE_PSK:
            case WifiInfo.SECURITY_TYPE_EAP:
            case WifiInfo.SECURITY_TYPE_WAPI_PSK:
            case WifiInfo.SECURITY_TYPE_WAPI_CERT:
            case WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2:
                return 2;
            case WifiInfo.SECURITY_TYPE_SAE:
            case WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE:
            case WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT:
            case WifiInfo.SECURITY_TYPE_PASSPOINT_R3:
            case WifiInfo.SECURITY_TYPE_DPP:
                return 3;
            default:
                return -1;
        }
    }

    static String standardName(Integer std) {
        if (std == null) return "Unknown";
        switch (std) {
            case ScanResult.WIFI_STANDARD_LEGACY: return "Legacy";
            case ScanResult.WIFI_STANDARD_11N: return "Wi-Fi 4";
            case ScanResult.WIFI_STANDARD_11AC: return "Wi-Fi 5";
            case ScanResult.WIFI_STANDARD_11AX: return "Wi-Fi 6/6E";
            case ScanResult.WIFI_STANDARD_11AD: return "Wi-Fi 60 GHz";
            case ScanResult.WIFI_STANDARD_11BE: return "Wi-Fi 7";
            default: return "Standard " + std;
        }
    }
}
