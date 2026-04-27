package app.homeadbguard;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.CompoundButton;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import app.homeadbguard.databinding.ActivityMainBinding;
import app.homeadbguard.databinding.ItemSetupStepBinding;

public final class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST = 1001;

    private ActivityMainBinding binding;
    private ItemSetupStepBinding stepPermissions;
    private ItemSetupStepBinding stepSecure;
    private ItemSetupStepBinding stepHome;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        stepPermissions = ItemSetupStepBinding.bind(binding.setupStepPermissions.getRoot());
        stepSecure = ItemSetupStepBinding.bind(binding.setupStepSecure.getRoot());
        stepHome = ItemSetupStepBinding.bind(binding.setupStepHome.getRoot());

        wireSetupSteps();
        wireHomeCard();
        wireMonitorCard();
        wireDiagnostics();

        binding.versionText.setText(getString(R.string.version_label, BuildConfig.VERSION_NAME));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    // ---------- Setup steps ----------

    private void wireSetupSteps() {
        stepPermissions.stepTitle.setText(R.string.setup_step_permissions);
        stepPermissions.stepDesc.setText(R.string.setup_step_permissions_desc);
        stepPermissions.stepAction.setText(R.string.setup_action_permissions);
        stepPermissions.stepAction.setOnClickListener(v -> requestRuntimePermissions());

        stepSecure.stepTitle.setText(R.string.setup_step_secure_settings);
        stepSecure.stepDesc.setText(R.string.setup_step_secure_settings_desc);
        stepSecure.stepCommandCard.setVisibility(View.VISIBLE);
        stepSecure.stepCommandText.setText(R.string.setup_command_secure_settings);
        stepSecure.stepCommandCopy.setOnClickListener(v ->
                copyToClipboard("adb command", getString(R.string.setup_command_secure_settings)));
        stepSecure.stepAction.setText(R.string.setup_action_open_settings);
        stepSecure.stepAction.setOnClickListener(v -> openAppDetails());

        stepHome.stepTitle.setText(R.string.setup_step_save_home);
        stepHome.stepDesc.setText(R.string.setup_step_save_home_desc);
        stepHome.stepAction.setText(R.string.setup_action_save_home);
        stepHome.stepAction.setOnClickListener(v -> saveCurrentAsHome());
    }

    private void requestRuntimePermissions() {
        List<String> wanted = new ArrayList<>();
        addIfMissing(wanted, Manifest.permission.ACCESS_FINE_LOCATION);
        addIfMissing(wanted, Manifest.permission.NEARBY_WIFI_DEVICES);
        addIfMissing(wanted, Manifest.permission.POST_NOTIFICATIONS);
        if (wanted.isEmpty()) {
            snack(getString(R.string.granted));
            return;
        }
        requestPermissions(wanted.toArray(new String[0]), PERMISSION_REQUEST);
    }

    private void addIfMissing(List<String> wanted, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(permission);
        }
    }

    private void openAppDetails() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private void saveCurrentAsHome() {
        WifiState wifi = WifiState.current(this);
        if (!wifi.isUsable()) {
            snack("Wi-Fi details unavailable: " + MonitorService.wifiSummary(wifi));
            refresh();
            return;
        }
        Prefs.saveHome(this, wifi);
        Prefs.setLastEvaluation(this, "Saved home Wi-Fi SSID and first trusted BSSID");
        snack("Saved " + wifi.ssid);
        refresh();
    }

    // ---------- Home card ----------

    private void wireHomeCard() {
        binding.homeAddBssid.setOnClickListener(v -> addCurrentBssid());
        binding.homeClear.setOnClickListener(v -> clearHome());
        binding.homeSsidOnlySwitch.setOnCheckedChangeListener((CompoundButton btn, boolean checked) -> {
            if (!btn.isPressed()) return;
            Prefs.setAllowSsidOnly(this, checked);
            Prefs.setLastEvaluation(this, "SSID-only fallback is now " + (checked ? "enabled" : "disabled"));
            MonitorService.applyCurrentState(this);
            refresh();
        });
    }

    private void addCurrentBssid() {
        WifiState wifi = WifiState.current(this);
        if (!wifi.isUsable()) {
            snack("Wi-Fi details unavailable");
            refresh();
            return;
        }
        String savedSsid = Prefs.homeSsid(this);
        if (savedSsid.isEmpty()) {
            Prefs.saveHome(this, wifi);
            snack("Saved " + wifi.ssid);
        } else if (!savedSsid.equals(wifi.ssid)) {
            snack("Current SSID does not match saved home (" + savedSsid + ")");
            return;
        } else {
            Prefs.addBssid(this, wifi.bssid);
            snack("Added " + wifi.bssid);
        }
        refresh();
    }

    private void clearHome() {
        Prefs.clearHome(this);
        SecureSettings.disableNow(this);
        Prefs.setLastEvaluation(this, "Saved home Wi-Fi cleared and ADB disabled");
        snack("Home Wi-Fi cleared");
        refresh();
    }

    // ---------- Monitor card ----------

    private void wireMonitorCard() {
        binding.monitorSwitch.setOnCheckedChangeListener((CompoundButton btn, boolean checked) -> {
            if (!btn.isPressed()) return;
            Prefs.setMonitoring(this, checked);
            if (checked) {
                MonitorService.requestStart(this);
                snack("Monitoring started");
            } else {
                SecureSettings.disableNow(this);
                stopService(new Intent(this, MonitorService.class));
                Prefs.setLastEvaluation(this, "Monitoring stopped manually");
                snack("Monitoring stopped");
            }
            refresh();
        });

        binding.monitorApply.setOnClickListener(v -> {
            MonitorService.applyCurrentState(this);
            refresh();
        });

        binding.monitorDisable.setOnClickListener(v -> {
            SecureSettings.disableNow(this);
            snack("Disable requested");
            refresh();
        });

        binding.monitorStop.setOnClickListener(v -> {
            Prefs.setMonitoring(this, false);
            SecureSettings.disableNow(this);
            stopService(new Intent(this, MonitorService.class));
            Prefs.setLastEvaluation(this, "Monitoring stopped manually");
            snack("Stopped");
            refresh();
        });
    }

    // ---------- Diagnostics ----------

    private void wireDiagnostics() {
        binding.diagHeader.setOnClickListener(v -> {
            boolean expanded = binding.diagContent.getVisibility() == View.VISIBLE;
            binding.diagContent.setVisibility(expanded ? View.GONE : View.VISIBLE);
            binding.diagChevron.setImageResource(expanded
                    ? R.drawable.ic_radio_unchecked
                    : R.drawable.ic_check_circle);
        });
        binding.diagPackage.setText(getPackageName());
        binding.diagVerifyHint.setOnLongClickListener(v -> {
            copyToClipboard("verify commands", getString(R.string.diag_verify_hint));
            return true;
        });
    }

    // ---------- Refresh ----------

    private void refresh() {
        WifiState wifi = WifiState.current(this);
        HomeMatcher.MatchResult match = HomeMatcher.evaluate(this, wifi);

        boolean permsOk = hasAllRuntimePermissions();
        boolean secureOk = SecureSettings.hasWriteSecureSettings(this);
        boolean homeSaved = !Prefs.homeSsid(this).isEmpty();
        boolean monitoring = Prefs.monitoring(this);
        boolean fullyConfigured = permsOk && secureOk && homeSaved;

        renderStatusHero(fullyConfigured, monitoring, match);
        renderSetup(permsOk, secureOk, homeSaved, fullyConfigured);
        renderCurrentNetwork(wifi, match);
        renderHome();
        renderMonitor(monitoring);
        renderDiagnostics(wifi);
    }

    private void renderStatusHero(boolean configured, boolean monitoring, HomeMatcher.MatchResult match) {
        @ColorInt int container;
        @ColorInt int onContainer;
        @DrawableRes int icon;
        int titleRes;
        int subtitleRes;

        if (!configured) {
            titleRes = R.string.status_setup_title;
            subtitleRes = R.string.status_setup_subtitle;
            icon = R.drawable.ic_warning;
            container = ContextCompat.getColor(this, R.color.status_setup_container);
            onContainer = ContextCompat.getColor(this, R.color.status_setup);
        } else if (!monitoring) {
            titleRes = R.string.status_idle_title;
            subtitleRes = R.string.status_idle_subtitle;
            icon = R.drawable.ic_warning;
            container = ContextCompat.getColor(this, R.color.status_setup_container);
            onContainer = ContextCompat.getColor(this, R.color.status_setup);
        } else if (match.atHome) {
            titleRes = R.string.status_protected_title;
            subtitleRes = R.string.status_protected_subtitle;
            icon = R.drawable.ic_shield_check;
            container = ContextCompat.getColor(this, R.color.status_protected_container);
            onContainer = ContextCompat.getColor(this, R.color.status_protected);
        } else {
            titleRes = R.string.status_blocked_title;
            subtitleRes = R.string.status_blocked_subtitle;
            icon = R.drawable.ic_shield_off;
            container = ContextCompat.getColor(this, R.color.status_blocked_container);
            onContainer = ContextCompat.getColor(this, R.color.status_blocked);
        }

        binding.statusCard.setCardBackgroundColor(container);
        binding.statusTitle.setTextColor(onContainer);
        binding.statusSubtitle.setTextColor(onContainer);
        binding.statusIcon.setImageResource(icon);
        binding.statusIcon.setImageTintList(android.content.res.ColorStateList.valueOf(onContainer));
        binding.statusTitle.setText(titleRes);
        binding.statusSubtitle.setText(subtitleRes);
    }

    private void renderSetup(boolean permsOk, boolean secureOk, boolean homeSaved, boolean fullyConfigured) {
        binding.setupCard.setVisibility(fullyConfigured ? View.GONE : View.VISIBLE);
        markStep(stepPermissions, permsOk);
        markStep(stepSecure, secureOk);
        markStep(stepHome, homeSaved);
    }

    private void markStep(ItemSetupStepBinding step, boolean done) {
        step.stepIcon.setImageResource(done ? R.drawable.ic_check_circle : R.drawable.ic_radio_unchecked);
        step.stepAction.setEnabled(!done);
        step.stepAction.setAlpha(done ? 0.5f : 1f);
    }

    private void renderCurrentNetwork(WifiState wifi, HomeMatcher.MatchResult match) {
        binding.currentSsid.setText(valueOrDash(wifi.ssid));
        binding.currentBssid.setText(valueOrDash(wifi.bssid));
        binding.currentDecision.setText(match.reason);
    }

    private void renderHome() {
        Set<String> bssids = Prefs.homeBssids(this);
        String ssid = Prefs.homeSsid(this);

        if (ssid.isEmpty()) {
            binding.homeEmpty.setVisibility(View.VISIBLE);
            binding.homeFilledGroup.setVisibility(View.GONE);
        } else {
            binding.homeEmpty.setVisibility(View.GONE);
            binding.homeFilledGroup.setVisibility(View.VISIBLE);
            binding.homeSsid.setText(ssid);
            binding.homeBssidChips.removeAllViews();
            if (bssids.isEmpty()) {
                Chip empty = new Chip(this);
                empty.setText("(none)");
                empty.setClickable(false);
                binding.homeBssidChips.addView(empty);
            } else {
                for (String b : bssids) {
                    Chip chip = new Chip(this);
                    chip.setText(b);
                    chip.setClickable(false);
                    binding.homeBssidChips.addView(chip);
                }
            }
        }

        boolean ssidOnly = Prefs.allowSsidOnly(this);
        if (binding.homeSsidOnlySwitch.isChecked() != ssidOnly) {
            binding.homeSsidOnlySwitch.setChecked(ssidOnly);
        }
    }

    private void renderMonitor(boolean monitoring) {
        if (binding.monitorSwitch.isChecked() != monitoring) {
            binding.monitorSwitch.setChecked(monitoring);
        }
    }

    private void renderDiagnostics(WifiState wifi) {
        binding.diagPermLoc.setText(grantText(Manifest.permission.ACCESS_FINE_LOCATION));
        binding.diagPermNearby.setText(grantText(Manifest.permission.NEARBY_WIFI_DEVICES));
        binding.diagPermNotif.setText(grantText(Manifest.permission.POST_NOTIFICATIONS));
        binding.diagPermSecure.setText(grantText(Manifest.permission.WRITE_SECURE_SETTINGS));
        binding.diagLocationToggle.setText(wifi.locationEnabled
                ? getString(R.string.enabled)
                : getString(R.string.disabled));
        binding.diagWifiSource.setText(valueOrDash(wifi.source));
        binding.diagLastEval.setText(valueOrDash(Prefs.lastEvaluation(this)));
        binding.diagLastApply.setText(valueOrDash(Prefs.lastApplyResult(this)));
    }

    // ---------- Helpers ----------

    private boolean hasAllRuntimePermissions() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private String grantText(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                ? getString(R.string.granted)
                : getString(R.string.not_granted);
    }

    private String valueOrDash(String s) {
        return s == null || s.isEmpty() ? getString(R.string.value_unknown) : s;
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        snack(getString(R.string.copied_to_clipboard));
    }

    private void snack(String text) {
        Snackbar.make(binding.getRoot(), text, Snackbar.LENGTH_SHORT).show();
    }
}
