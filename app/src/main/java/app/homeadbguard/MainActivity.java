package app.homeadbguard;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 1001;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        status = new TextView(this);
        status.setTextSize(15f);
        status.setPadding(24, 24, 24, 24);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(24, 24, 24, 24);
        layout.addView(status);

        layout.addView(button("Request runtime permissions", () -> {
            requestRuntimePermissions();
            refreshStatus();
        }));

        layout.addView(button("Open this app's Android settings", () -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }));

        layout.addView(button("Save current Wi-Fi as home", () -> {
            WifiState wifi = WifiState.current(this);
            if (!wifi.isUsable()) {
                Prefs.setLastEvaluation(this, "Cannot save home Wi-Fi because Wi-Fi details are unavailable: " + MonitorService.wifiSummary(wifi));
                refreshStatus();
                return;
            }
            Prefs.saveHome(this, wifi);
            Prefs.setLastEvaluation(this, "Saved home Wi-Fi SSID and first trusted BSSID");
            refreshStatus();
        }));

        layout.addView(button("Add current AP BSSID to trusted home list", () -> {
            WifiState wifi = WifiState.current(this);
            if (!wifi.isUsable()) {
                Prefs.setLastEvaluation(this, "Cannot add BSSID because Wi-Fi details are unavailable: " + MonitorService.wifiSummary(wifi));
                refreshStatus();
                return;
            }
            String savedSsid = Prefs.homeSsid(this);
            if (!savedSsid.isEmpty() && !savedSsid.equals(wifi.ssid)) {
                Prefs.setLastEvaluation(this, "Current SSID does not match saved home SSID; BSSID not added");
                refreshStatus();
                return;
            }
            if (savedSsid.isEmpty()) Prefs.saveHome(this, wifi);
            else Prefs.addBssid(this, wifi.bssid);
            Prefs.setLastEvaluation(this, "Added current AP BSSID to trusted home list");
            refreshStatus();
        }));

        layout.addView(button("Toggle SSID-only fallback", () -> {
            boolean next = !Prefs.allowSsidOnly(this);
            Prefs.setAllowSsidOnly(this, next);
            Prefs.setLastEvaluation(this, "SSID-only fallback is now " + (next ? "enabled" : "disabled"));
            refreshStatus();
        }));

        layout.addView(button("Start monitoring", () -> {
            Prefs.setMonitoring(this, true);
            MonitorService.requestStart(this);
            refreshStatus();
        }));

        layout.addView(button("Apply current state now", () -> {
            MonitorService.applyCurrentState(this);
            refreshStatus();
        }));

        layout.addView(button("Disable ADB Wi-Fi + Developer options now", () -> {
            SecureSettings.disableNow(this);
            refreshStatus();
        }));

        layout.addView(button("Stop monitoring and disable now", () -> {
            Prefs.setMonitoring(this, false);
            SecureSettings.disableNow(this);
            stopService(new Intent(this, MonitorService.class));
            Prefs.setLastEvaluation(this, "Monitoring stopped manually");
            refreshStatus();
        }));

        layout.addView(button("Clear saved home Wi-Fi", () -> {
            Prefs.clearHome(this);
            SecureSettings.disableNow(this);
            Prefs.setLastEvaluation(this, "Saved home Wi-Fi cleared and ADB disabled");
            refreshStatus();
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private Button button(String text, Runnable runnable) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> runnable.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 10, 0, 10);
        b.setLayoutParams(lp);
        return b;
    }

    private void requestRuntimePermissions() {
        List<String> wanted = new ArrayList<>();
        addIfMissing(wanted, Manifest.permission.ACCESS_FINE_LOCATION);
        addIfMissing(wanted, Manifest.permission.NEARBY_WIFI_DEVICES);
        addIfMissing(wanted, Manifest.permission.POST_NOTIFICATIONS);
        if (!wanted.isEmpty()) {
            requestPermissions(wanted.toArray(new String[0]), PERMISSION_REQUEST);
        }
    }

    private void addIfMissing(List<String> wanted, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(permission);
        }
    }

    private void refreshStatus() {
        WifiState wifi = WifiState.current(this);
        Set<String> bssids = Prefs.homeBssids(this);
        HomeMatcher.MatchResult match = HomeMatcher.evaluate(this, wifi);

        String text =
                "Package: " + getPackageName() + "\n\n" +
                "Runtime permissions:\n" +
                "  Fine location: " + granted(Manifest.permission.ACCESS_FINE_LOCATION) + "\n" +
                "  Nearby Wi-Fi devices: " + granted(Manifest.permission.NEARBY_WIFI_DEVICES) + "\n" +
                "  Notifications: " + granted(Manifest.permission.POST_NOTIFICATIONS) + "\n" +
                "  WRITE_SECURE_SETTINGS: " + granted(Manifest.permission.WRITE_SECURE_SETTINGS) + "\n\n" +
                "Current Wi-Fi:\n" +
                "  SSID: " + valueOrDash(wifi.ssid) + "\n" +
                "  BSSID: " + valueOrDash(wifi.bssid) + "\n" +
                "  source: " + valueOrDash(wifi.source) + "\n" +
                "  usable: " + wifi.isUsable() + "\n" +
                "  Wi-Fi transport seen: " + wifi.wifiTransportSeen + "\n" +
                "  Location enabled: " + wifi.locationEnabled + "\n\n" +
                "Saved home Wi-Fi:\n" +
                "  SSID: " + valueOrDash(Prefs.homeSsid(this)) + "\n" +
                "  Trusted BSSIDs: " + Prefs.bssidDisplay(bssids) + "\n" +
                "  SSID-only fallback: " + Prefs.allowSsidOnly(this) + "\n\n" +
                "Decision now:\n" +
                "  atHome: " + match.atHome + "\n" +
                "  reason: " + match.reason + "\n\n" +
                "Monitoring enabled: " + Prefs.monitoring(this) + "\n\n" +
                "Last evaluation:\n" + Prefs.lastEvaluation(this) + "\n\n" +
                "Last settings write:\n" + Prefs.lastApplyResult(this) + "\n\n" +
                "Use ADB to verify actual Android global settings; Android does not reliably expose Development options state back to third-party apps.";
        status.setText(text);
    }

    private String granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED ? "yes" : "no";
    }

    private String valueOrDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }
}
