package app.homeadbguard;

import android.content.Context;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Reads back the actual ADB-related secure settings (and, best-effort, the live
 * wireless-debugging TLS port) so the guard can verify and recover state instead
 * of trusting its own writes.
 *
 * The primary signal is the {@code adb_wifi_enabled} read-back, which works on
 * every device. The TLS port ({@code service.adb.tls.port}) is read when the OS
 * exposes it — on hardened/SELinux-locked builds it is denied, in which case the
 * verdict is simply {@link Confidence#ON} (setting on) rather than the stronger
 * {@link Confidence#VERIFIED_ON}.
 */
final class AdbState {

    enum Confidence {
        /** Setting on and a live TLS port is readable — confirmed listening. */
        VERIFIED_ON,
        /** Setting reads on; the TLS port is not readable on this device. */
        ON,
        /** Setting reads off. */
        OFF,
        /** Setting unreadable. */
        UNKNOWN
    }

    static final String KEY_DEV = "development_settings_enabled";
    static final String KEY_ADB_WIFI = "adb_wifi_enabled";
    static final String KEY_ADB_USB = "adb_enabled";
    private static final String PROP_TLS_PORT = "service.adb.tls.port";

    private AdbState() {
    }

    /**
     * Pure fusion of the read-back signals.
     * @param adbWifiSetting 1 on, 0 off, -1 unreadable.
     * @param tlsPort        live port (&gt;0), or -1 if unset/unreadable.
     */
    static Confidence fuse(int adbWifiSetting, int tlsPort) {
        if (adbWifiSetting == 0) return Confidence.OFF;
        if (adbWifiSetting != 1) return Confidence.UNKNOWN;
        return tlsPort > 0 ? Confidence.VERIFIED_ON : Confidence.ON;
    }

    /** Immutable result of one {@link #detect(Context)} run. */
    static final class Snapshot {
        final int devSetting;       // 1/0/-1
        final int adbWifiSetting;   // 1/0/-1
        final int adbUsbSetting;    // 1/0/-1
        final int tlsPort;          // >0 live, -1 unknown
        final Confidence confidence;

        Snapshot(int devSetting, int adbWifiSetting, int adbUsbSetting,
                 int tlsPort, Confidence confidence) {
            this.devSetting = devSetting;
            this.adbWifiSetting = adbWifiSetting;
            this.adbUsbSetting = adbUsbSetting;
            this.tlsPort = tlsPort;
            this.confidence = confidence;
        }

        String summary() {
            return confidence.name()
                    + " · dev=" + s(devSetting)
                    + " adb_wifi=" + s(adbWifiSetting)
                    + " adb_usb=" + s(adbUsbSetting)
                    + " port=" + (tlsPort > 0 ? String.valueOf(tlsPort) : "—");
        }

        private static String s(int v) {
            return v < 0 ? "?" : String.valueOf(v);
        }
    }

    static Snapshot detect(Context context) {
        int dev = readSettingInt(context, KEY_DEV);
        int adbWifi = readSettingInt(context, KEY_ADB_WIFI);
        int adbUsb = readSettingInt(context, KEY_ADB_USB);
        int port = readTlsPort();
        return new Snapshot(dev, adbWifi, adbUsb, port, fuse(adbWifi, port));
    }

    /** @return 1/0 for a present value, 0 if absent, -1 if reading threw. */
    private static int readSettingInt(Context context, String key) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), key);
        } catch (Settings.SettingNotFoundException notFound) {
            return 0;
        } catch (RuntimeException denied) {
            return -1;
        }
    }

    /** @return the live TLS port (>0), or -1 if unset/unreadable/unparseable. */
    static int readTlsPort() {
        String raw = systemProperty(PROP_TLS_PORT);
        if (raw == null || raw.isEmpty()) return -1;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String systemProperty(String key) {
        String viaReflect = systemPropertyReflect(key);
        if (viaReflect != null) return viaReflect;
        return systemPropertyExec(key);
    }

    private static String systemPropertyReflect(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, key);
            return v == null ? null : v.toString();
        } catch (Throwable hiddenApiBlockedOrMissing) {
            return null;
        }
    }

    private static String systemPropertyExec(String key) {
        try {
            Process p = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                p.waitFor();
                return line == null ? "" : line.trim();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
