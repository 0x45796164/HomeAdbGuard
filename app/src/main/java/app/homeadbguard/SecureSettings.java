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
        return setSafeState(context, atHome, false);
    }

    /**
     * Apply the safe state. When enabling, {@code forceAdbRebind} controls how
     * {@code adb_wifi_enabled} is written:
     *
     * <ul>
     *   <li>{@code false} (steady-state ticks): a plain write of {@code 1}. Cheap
     *       and non-disruptive — it leaves an already-listening adbd untouched, so
     *       an active wireless session is never interrupted.</li>
     *   <li>{@code true} (re-establishment events: service (re)start, Wi-Fi
     *       arrival, Doze exit, explicit "Apply now"): a {@code 0 -> 1} toggle that
     *       forces adbd to (re)bind its listening port. A plain rewrite of {@code 1}
     *       over an already-{@code 1} value fires no settings-change notification, so
     *       after a reboot or Doze tore the listener down — while the setting stayed
     *       {@code 1} — adbd would otherwise never re-bind and ADB stays silently
     *       off.</li>
     * </ul>
     */
    static ApplyResult setSafeState(Context context, boolean atHome, boolean forceAdbRebind) {
        ApplyResult denied = requirePermission(context);
        if (denied != null) return denied;

        boolean devOk;
        boolean adbOk;
        if (atHome) {
            devOk = putGlobalInt(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
            if (forceAdbRebind) {
                adbOk = forceAdbWifiRebind(context);
            } else {
                adbOk = putGlobalInt(context, ADB_WIFI_ENABLED, 1);
            }
        } else {
            adbOk = putGlobalInt(context, ADB_WIFI_ENABLED, 0);
            devOk = putGlobalInt(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
        }

        return record(context, new ApplyResult(true, devOk, adbOk,
                atHome ? (forceAdbRebind ? "requested enable (rebind)" : "requested enable") : "requested disable"));
    }

    /**
     * Force AOSP adbd to (re)bind its wireless listening port by toggling
     * {@code adb_wifi_enabled} {@code 0 -> 1} with a short delay. Returns an
     * optimistic {@code true}: the re-enable write is scheduled on the main
     * looper and we report the intent rather than blocking on its result.
     */
    static boolean forceAdbWifiRebind(Context context) {
        putGlobalInt(context, ADB_WIFI_ENABLED, 0);
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> putGlobalInt(context, ADB_WIFI_ENABLED, 1),
                ADB_TOGGLE_DELAY_MS
        );
        return true;
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
