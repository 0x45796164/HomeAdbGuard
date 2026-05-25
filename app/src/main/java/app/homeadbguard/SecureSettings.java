package app.homeadbguard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

final class SecureSettings {
    static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";
    private static final long ADB_TOGGLE_DELAY_MS = 250L;

    private SecureSettings() {
    }

    static boolean hasWriteSecureSettings(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static ApplyResult setSafeState(Context context, boolean atHome) {
        ApplyResult denied = requirePermission(context);
        if (denied != null) return denied;

        boolean devOk;
        boolean adbOk;
        if (atHome) {
            devOk = putGlobalInt(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
            adbOk = putGlobalInt(context, ADB_WIFI_ENABLED, 1);
        } else {
            adbOk = putGlobalInt(context, ADB_WIFI_ENABLED, 0);
            devOk = putGlobalInt(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
        }

        return record(context, new ApplyResult(true, devOk, adbOk, atHome ? "requested enable" : "requested disable"));
    }

    /**
     * Force-enable Developer options and ADB over Wi-Fi when the device is on a trusted network.
     * Refuses (and disables) when not at home, so the button never weakens the protection.
     *
     * Toggles ADB_WIFI_ENABLED 0 → 1 with a short delay to nudge AOSP adbd to re-bind the
     * listening port. A plain rewrite of `1` over an already-`1` value is sometimes ignored.
     */
    static ApplyResult enableNowIfAtHome(Context context) {
        ApplyResult denied = requirePermission(context);
        if (denied != null) return denied;

        WifiState wifi = WifiState.current(context);
        HomeMatcher.MatchResult match = HomeMatcher.evaluate(context, wifi);
        if (!match.atHome) {
            return record(context, new ApplyResult(true, false, false, "refused: " + match.reason));
        }

        boolean devOk = putGlobalInt(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
        putGlobalInt(context, ADB_WIFI_ENABLED, 0);
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> putGlobalInt(context, ADB_WIFI_ENABLED, 1),
                ADB_TOGGLE_DELAY_MS
        );

        return record(context, new ApplyResult(true, devOk, true, "force enable (toggled)"));
    }

    static ApplyResult disableNow(Context context) {
        ApplyResult denied = requirePermission(context);
        if (denied != null) return denied;

        boolean adbOk = putGlobalInt(context, ADB_WIFI_ENABLED, 0);
        boolean devOk = putGlobalInt(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
        return record(context, new ApplyResult(true, devOk, adbOk, "manual disable"));
    }

    private static ApplyResult requirePermission(Context context) {
        if (hasWriteSecureSettings(context)) return null;
        return record(context, new ApplyResult(false, false, false, "WRITE_SECURE_SETTINGS is not granted"));
    }

    private static ApplyResult record(Context context, ApplyResult result) {
        Prefs.setLastApplyResult(context, result.toString());
        return result;
    }

    private static boolean putGlobalInt(Context context, String key, int value) {
        try {
            return Settings.Global.putInt(context.getContentResolver(), key, value);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static final class ApplyResult {
        final boolean permissionGranted;
        final boolean developerOptionsWriteOk;
        final boolean adbWifiWriteOk;
        final String note;

        ApplyResult(boolean permissionGranted, boolean developerOptionsWriteOk, boolean adbWifiWriteOk, String note) {
            this.permissionGranted = permissionGranted;
            this.developerOptionsWriteOk = developerOptionsWriteOk;
            this.adbWifiWriteOk = adbWifiWriteOk;
            this.note = note == null ? "" : note;
        }

        @Override
        public String toString() {
            return "permissionGranted=" + permissionGranted
                    + ", developerOptionsWriteOk=" + developerOptionsWriteOk
                    + ", adbWifiWriteOk=" + adbWifiWriteOk
                    + ", note=" + note;
        }
    }
}
