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
import android.widget.ImageView;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import app.homeadbguard.databinding.ActivityMainBinding;
import app.homeadbguard.databinding.ItemSetupStepBinding;

public final class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST = 1001;

    static final String ACTION_ENABLE_NOW = "app.homeadbguard.action.ENABLE_NOW";
    static final String ACTION_DISABLE_NOW = "app.homeadbguard.action.DISABLE_NOW";
    static final String ACTION_RECHECK_NOW = "app.homeadbguard.action.RECHECK_NOW";

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        wireSetupSteps();
        wireHomeCard();
        wirePairingCard();
        wireMonitorCard();
        wireDiagnostics();
        wireCollapsibleCards();

        binding.aboutCard.versionText.setText(getString(R.string.version_label, BuildConfig.VERSION_NAME));

        handleShortcutIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShortcutIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void handleShortcutIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        switch (intent.getAction()) {
            case ACTION_ENABLE_NOW: {
                SecureSettings.ApplyResult r = SecureSettings.enableNowIfAtHome(this);
                snack(getString(r.adbWifiWriteOk ? R.string.enable_succeeded : R.string.enable_refused));
                break;
            }
            case ACTION_DISABLE_NOW: {
                SecureSettings.disableNow(this);
                snack(getString(R.string.shortcut_disable_short));
                break;
            }
            case ACTION_RECHECK_NOW: {
                MonitorService.applyCurrentState(this);
                snack(getString(R.string.shortcut_recheck_short));
                break;
            }
            default:
                return;
        }
        intent.setAction(null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    // ---------- Setup steps ----------

    private void wireSetupSteps() {
        bindSetupStep(binding.setupStepPermissions,
                R.string.setup_step_permissions,
                R.string.setup_step_permissions_desc,
                R.string.setup_action_permissions,
                v -> requestRuntimePermissions());

        bindSetupStep(binding.setupStepSecure,
                R.string.setup_step_secure_settings,
                R.string.setup_step_secure_settings_desc,
                R.string.setup_action_open_settings,
                v -> openAppDetails());
        binding.setupStepSecure.stepCommandCard.setVisibility(View.VISIBLE);
        binding.setupStepSecure.stepCommandText.setText(R.string.setup_command_secure_settings);
        binding.setupStepSecure.stepCommandCopy.setOnClickListener(v ->
                copyToClipboard("adb command", getString(R.string.setup_command_secure_settings)));

        bindSetupStep(binding.setupStepHome,
                R.string.setup_step_save_home,
                R.string.setup_step_save_home_desc,
                R.string.setup_action_save_home,
                v -> saveCurrentAsHome());
    }

    private void bindSetupStep(ItemSetupStepBinding step, @StringRes int title, @StringRes int desc,
                               @StringRes int action, View.OnClickListener onAction) {
        step.stepTitle.setText(title);
        step.stepDesc.setText(desc);
        step.stepAction.setText(action);
        step.stepAction.setOnClickListener(onAction);
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

    // ---------- Pairing card ----------

    private void wirePairingCard() {
        binding.pairingCopyIp.setOnClickListener(v -> withLocalIp(ip -> copyToClipboard("local IP", ip)));
        binding.pairingCopyCommand.setOnClickListener(v -> withLocalIp(ip ->
                copyToClipboard("adb connect command", getString(R.string.pairing_command_template, ip))));
    }

    private void withLocalIp(Consumer<String> onIp) {
        String ip = LocalIp.firstUsableIpv4();
        if (ip == null) {
            snack(getString(R.string.pairing_no_ip));
            return;
        }
        onIp.accept(ip);
    }

    private void renderPairingCard() {
        String ip = LocalIp.firstUsableIpv4();
        if (ip == null) {
            binding.pairingIp.setText(R.string.pairing_no_ip);
            binding.pairingCommand.setText("—");
        } else {
            binding.pairingIp.setText(ip);
            binding.pairingCommand.setText(getString(R.string.pairing_command_template, ip));
        }
    }

    // ---------- Monitor card ----------

    private void wireMonitorCard() {
        binding.monitorSwitch.setOnCheckedChangeListener((CompoundButton btn, boolean checked) -> {
            if (!btn.isPressed()) return;
            Prefs.setMonitoring(this, checked);
            if (checked) {
                MonitorService.requestStart(this);
                snack("Monitoring started");
                refresh();
            } else {
                stopMonitoring("Monitoring stopped");
            }
        });

        binding.monitorEnable.setOnClickListener(v -> {
            SecureSettings.ApplyResult r = SecureSettings.enableNowIfAtHome(this);
            snack(getString(r.adbWifiWriteOk ? R.string.enable_succeeded : R.string.enable_refused));
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

        binding.monitorStop.setOnClickListener(v -> stopMonitoring("Stopped"));

        binding.snooze15.setOnClickListener(v -> armSnooze(15));
        binding.snooze30.setOnClickListener(v -> armSnooze(30));
        binding.snooze60.setOnClickListener(v -> armSnooze(60));
        binding.snoozeCancel.setOnClickListener(v -> {
            SnoozeArmer.cancel(this);
            snack(getString(R.string.snooze_cancel));
            refresh();
        });
    }

    private void stopMonitoring(String snackMsg) {
        Prefs.setMonitoring(this, false);
        SecureSettings.disableNow(this);
        stopService(new Intent(this, MonitorService.class));
        NetworkWatch.disarm(this);
        Prefs.setLastEvaluation(this, "Monitoring stopped manually");
        snack(snackMsg);
        refresh();
    }

    private void armSnooze(int minutes) {
        SnoozeArmer.Result r = SnoozeArmer.arm(this, minutes);
        if (r == SnoozeArmer.Result.ARMED) {
            snack(getString(R.string.snooze_armed, minutes));
        } else {
            snack(getString(R.string.snooze_refused));
        }
        refresh();
    }

    // ---------- Diagnostics ----------

    private void wireDiagnostics() {
        binding.diagCard.diagPackage.setText(getPackageName());
        binding.diagCard.diagVerifyHint.setOnLongClickListener(v -> {
            copyToClipboard("verify commands", getString(R.string.diag_verify_hint));
            return true;
        });
    }

    // ---------- Collapsible cards ----------

    private void wireCollapsibleCards() {
        wireCollapsible(binding.setupHeader, binding.setupContent, binding.setupChevron);
        wireCollapsible(binding.currentNetworkHeader, binding.currentNetworkContent, binding.currentNetworkChevron);
        wireCollapsible(binding.homeHeader, binding.homeContent, binding.homeChevron);
        wireCollapsible(binding.pairingHeader, binding.pairingContent, binding.pairingChevron);
        wireCollapsible(binding.monitorHeader, binding.monitorContent, binding.monitorChevron);
        wireCollapsible(binding.diagCard.diagHeader, binding.diagCard.diagContent, binding.diagCard.diagChevron);
        wireCollapsible(binding.aboutCard.aboutHeader, binding.aboutCard.aboutContent, binding.aboutCard.aboutChevron);
    }

    private void wireCollapsible(View header, View content, ImageView chevron) {
        header.setOnClickListener(v -> {
            boolean willCollapse = content.getVisibility() == View.VISIBLE;
            content.setVisibility(willCollapse ? View.GONE : View.VISIBLE);
            chevron.setImageResource(willCollapse
                    ? R.drawable.ic_expand_more
                    : R.drawable.ic_expand_less);
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
        renderPairingCard();
        renderMonitor(monitoring);
        renderDiagnostics(wifi);
    }

    private void renderStatusHero(boolean configured, boolean monitoring, HomeMatcher.MatchResult match) {
        @StringRes int titleRes;
        @StringRes int subtitleRes;
        @DrawableRes int icon;
        @ColorInt int container;
        @ColorInt int onContainer;

        if (!configured || !monitoring) {
            titleRes = configured ? R.string.status_idle_title : R.string.status_setup_title;
            subtitleRes = configured ? R.string.status_idle_subtitle : R.string.status_setup_subtitle;
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
        markStep(binding.setupStepPermissions, permsOk);
        markStep(binding.setupStepSecure, secureOk);
        markStep(binding.setupStepHome, homeSaved);
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
                empty.setText(R.string.none_placeholder);
                empty.setClickable(false);
                binding.homeBssidChips.addView(empty);
            } else {
                for (String b : bssids) {
                    Chip chip = new Chip(this);
                    chip.setText(b);
                    chip.setClickable(false);
                    chip.setCloseIconVisible(true);
                    chip.setOnCloseIconClickListener(v -> {
                        if (Prefs.homeBssids(this).size() <= 1) {
                            snack(getString(R.string.bssid_remove_only));
                            return;
                        }
                        Prefs.removeBssid(this, b);
                        Prefs.setLastEvaluation(this, "Removed " + b + " from trusted list");
                        MonitorService.applyCurrentState(this);
                        snack(getString(R.string.bssid_remove_done, b));
                        refresh();
                    });
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
        boolean snoozing = Prefs.isSnoozeActive(this);
        if (snoozing) {
            long remainingMin = (Prefs.snoozeRemainingMs(this) + 59_999L) / 60_000L;
            binding.snoozeSubtitle.setText(getString(
                    R.string.snooze_active_with_remaining,
                    getString(R.string.snooze_active_label),
                    getString(R.string.snooze_remaining, remainingMin)));
        } else {
            binding.snoozeSubtitle.setText(R.string.snooze_subtitle);
        }
        binding.snoozeCancel.setVisibility(snoozing ? View.VISIBLE : View.GONE);
    }

    private void renderDiagnostics(WifiState wifi) {
        binding.diagCard.diagPermLoc.setText(grantText(Manifest.permission.ACCESS_FINE_LOCATION));
        binding.diagCard.diagPermNearby.setText(grantText(Manifest.permission.NEARBY_WIFI_DEVICES));
        binding.diagCard.diagPermNotif.setText(grantText(Manifest.permission.POST_NOTIFICATIONS));
        binding.diagCard.diagPermSecure.setText(grantText(Manifest.permission.WRITE_SECURE_SETTINGS));
        binding.diagCard.diagLocationToggle.setText(getString(wifi.locationEnabled ? R.string.enabled : R.string.disabled));
        binding.diagCard.diagWifiSource.setText(valueOrDash(wifi.source));
        binding.diagCard.diagLastEval.setText(valueOrDash(Prefs.lastEvaluation(this)));
        binding.diagCard.diagLastApply.setText(valueOrDash(Prefs.lastApplyResult(this)));

        List<String> history = Prefs.decisionHistory(this);
        binding.diagCard.diagHistory.setText(history.isEmpty()
                ? getString(R.string.diag_history_empty)
                : String.join("\n", history));
    }

    // ---------- Helpers ----------

    private boolean hasAllRuntimePermissions() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private String grantText(String permission) {
        return getString(checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                ? R.string.granted
                : R.string.not_granted);
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
