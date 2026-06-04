package app.homeadbguard;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    private ActivityMainBinding binding;
    private boolean initialCollapseApplied;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private ConnectivityManager.NetworkCallback uiNetCallback;
    private final Runnable refreshIfActive = () -> {
        if (binding != null && !isFinishing() && !isDestroyed()) refresh();
    };

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerLiveRefreshListeners();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterLiveRefreshListeners();
    }

    private void registerLiveRefreshListeners() {
        if (prefsListener == null) {
            prefsListener = (sp, key) -> postRefresh();
            Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsListener);
        }
        if (uiNetCallback == null) {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) {
                uiNetCallback = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(@NonNull Network n) { postRefresh(); }
                    @Override public void onLost(@NonNull Network n) { postRefresh(); }
                    @Override public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) { postRefresh(); }
                    @Override public void onLinkPropertiesChanged(@NonNull Network n, @NonNull LinkProperties lp) { postRefresh(); }
                };
                try {
                    cm.registerDefaultNetworkCallback(uiNetCallback);
                } catch (RuntimeException e) {
                    uiNetCallback = null;
                }
            }
        }
    }

    private void unregisterLiveRefreshListeners() {
        if (prefsListener != null) {
            try {
                Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
            } catch (RuntimeException ignored) {
            }
            prefsListener = null;
        }
        if (uiNetCallback != null) {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) {
                try {
                    cm.unregisterNetworkCallback(uiNetCallback);
                } catch (RuntimeException ignored) {
                }
            }
            uiNetCallback = null;
        }
    }

    private void postRefresh() {
        mainHandler.removeCallbacks(refreshIfActive);
        mainHandler.postDelayed(refreshIfActive, 80L);
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
        // Background location ("Allow all the time") is optional and left to the
        // user — see the setup hint / docs. Without it the guard still works; it
        // just won't auto-recover after a reboot until the app is opened.
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

        binding.fingerprintStrictSwitch.setOnCheckedChangeListener((CompoundButton btn, boolean checked) -> {
            if (!btn.isPressed()) return;
            Prefs.setStrictFingerprint(this, checked);
            Prefs.setLastEvaluation(this, "Strict fingerprinting is now " + (checked ? "enabled" : "disabled"));
            MonitorService.applyCurrentState(this);
            refresh();
        });

        binding.fingerprintCapture.setOnClickListener(v -> captureFingerprint());

        binding.minProtectionChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            int sec;
            if (id == R.id.min_protection_wpa2) {
                sec = android.net.wifi.WifiInfo.SECURITY_TYPE_PSK;
            } else if (id == R.id.min_protection_wpa3) {
                sec = android.net.wifi.WifiInfo.SECURITY_TYPE_SAE;
            } else {
                sec = Prefs.SEC_UNSET;
            }
            if (sec == Prefs.minSecurityType(this)) return;
            Prefs.setMinSecurityType(this, sec);
            Prefs.setLastEvaluation(this, "Minimum protection level set to "
                    + (sec == Prefs.SEC_UNSET ? "off" : WifiNames.securityName(sec)));
            MonitorService.applyCurrentState(this);
            refresh();
        });
    }

    private void captureFingerprint() {
        WifiState wifi = WifiState.current(this);
        if (!wifi.isUsable()) {
            snack(getString(R.string.fingerprint_capture_refused));
            return;
        }
        String savedSsid = Prefs.homeSsid(this);
        if (savedSsid.isEmpty() || !savedSsid.equals(wifi.ssid)) {
            snack(getString(R.string.fingerprint_capture_refused));
            return;
        }
        Prefs.captureFingerprint(this, wifi);
        Prefs.setLastEvaluation(this, "Captured fingerprint: " + fingerprintSummary(wifi));
        snack(getString(R.string.fingerprint_captured));
        MonitorService.applyCurrentState(this);
        refresh();
    }

    private static String fingerprintSummary(WifiState wifi) {
        StringBuilder sb = new StringBuilder();
        if (wifi.securityType != null) sb.append(WifiNames.securityName(wifi.securityType));
        if (wifi.frequencyMhz != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(wifi.frequencyMhz).append(" MHz");
            Integer ch = wifi.channel();
            if (ch != null) sb.append(" / Ch ").append(ch);
        }
        if (wifi.wifiStandard != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(WifiNames.standardName(wifi.wifiStandard));
        }
        if (wifi.mloActive != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("MLO ").append(wifi.mloActive ? "on" : "off");
        }
        return sb.length() == 0 ? "" : sb.toString();
    }

    private static String fingerprintSummaryFromPrefs(android.content.Context ctx) {
        int sec = Prefs.expectedSecurityType(ctx);
        int freq = Prefs.expectedFrequencyMhz(ctx);
        int std = Prefs.expectedWifiStandard(ctx);
        int mlo = Prefs.expectedMloActive(ctx);
        if (sec == Prefs.SEC_UNSET && freq == Prefs.FREQ_UNSET
                && std == Prefs.STD_UNSET && mlo == Prefs.MLO_UNSET) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (sec != Prefs.SEC_UNSET) sb.append(WifiNames.securityName(sec));
        if (freq != Prefs.FREQ_UNSET) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(freq).append(" MHz");
        }
        if (std != Prefs.STD_UNSET) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(WifiNames.standardName(std));
        }
        if (mlo != Prefs.MLO_UNSET) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("MLO ").append(mlo == 1 ? "on" : "off");
        }
        return sb.toString();
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
        String ip = LocalIp.firstUsableIpv4(this);
        if (ip == null) {
            snack(getString(R.string.pairing_no_ip));
            return;
        }
        onIp.accept(ip);
    }

    private void renderPairingCard() {
        String ip = LocalIp.firstUsableIpv4(this);
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
                snack(getString(R.string.guard_armed));
                refresh();
            } else {
                stopMonitoring(getString(R.string.guard_off_snack));
            }
        });

        binding.pause15.setOnClickListener(v -> pauseFor(15));
        binding.pause30.setOnClickListener(v -> pauseFor(30));
        binding.pause60.setOnClickListener(v -> pauseFor(60));
        binding.pauseInf.setOnClickListener(v -> pauseFor(0)); // 0 ⇒ until resume
        binding.pauseResume.setOnClickListener(v -> {
            Prefs.setPauseUntil(this, 0L);
            Prefs.setLastEvaluation(this, "Resumed from app");
            MonitorService.applyCurrentState(this, true);
            snack(getString(R.string.pause_resumed));
            refresh();
        });

        binding.monitorStop.setOnClickListener(v -> stopMonitoring(getString(R.string.guard_off_snack)));

        binding.snooze15.setOnClickListener(v -> armSnooze(15));
        binding.snooze30.setOnClickListener(v -> armSnooze(30));
        binding.snooze60.setOnClickListener(v -> armSnooze(60));
        binding.snoozeCancel.setOnClickListener(v -> {
            SnoozeArmer.cancel(this);
            snack(getString(R.string.snooze_cancel));
            refresh();
        });
    }

    private void pauseFor(int minutes) {
        if (!Prefs.monitoring(this)) {
            snack(getString(R.string.pause_needs_guard));
            return;
        }
        long until = minutes <= 0
                ? Prefs.PAUSE_INDEFINITE
                : System.currentTimeMillis() + (long) minutes * 60_000L;
        Prefs.setPauseUntil(this, until);
        Prefs.setLastEvaluation(this, minutes <= 0
                ? "Paused until resume" : "Paused for " + minutes + " min");
        MonitorService.applyCurrentState(this);
        snack(minutes <= 0
                ? getString(R.string.pause_active_indefinite)
                : getString(R.string.pause_armed, minutes));
        refresh();
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
        header.setOnClickListener(v -> setCollapsed(content, chevron, content.getVisibility() == View.VISIBLE));
    }

    private void setCollapsed(View content, ImageView chevron, boolean collapsed) {
        content.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        chevron.setImageResource(collapsed ? R.drawable.ic_expand_more : R.drawable.ic_expand_less);
    }

    /**
     * Once per activity launch, collapse the cards by default after the user has
     * finished initial setup — a configured user just wants the at-a-glance status
     * hero, not a wall of expanded controls. Before setup completes the cards stay
     * expanded so the steps are visible. Applied a single time so it never fights a
     * manual expand/collapse the user makes during the session. (The setup card is
     * hidden entirely once configured, so it is intentionally not included here.)
     */
    private void applyInitialCollapseState(boolean fullyConfigured) {
        if (initialCollapseApplied) return;
        initialCollapseApplied = true;
        if (!fullyConfigured) return;
        setCollapsed(binding.currentNetworkContent, binding.currentNetworkChevron, true);
        setCollapsed(binding.homeContent, binding.homeChevron, true);
        setCollapsed(binding.pairingContent, binding.pairingChevron, true);
        setCollapsed(binding.monitorContent, binding.monitorChevron, true);
        setCollapsed(binding.diagCard.diagContent, binding.diagCard.diagChevron, true);
        setCollapsed(binding.aboutCard.aboutContent, binding.aboutCard.aboutChevron, true);
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

        applyInitialCollapseState(fullyConfigured);
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

        GuardState.Mode mode = GuardState.resolve(this, match).mode;
        if (!configured || !monitoring) {
            titleRes = configured ? R.string.status_idle_title : R.string.status_setup_title;
            subtitleRes = configured ? R.string.status_idle_subtitle : R.string.status_setup_subtitle;
            icon = R.drawable.ic_warning;
            container = ContextCompat.getColor(this, R.color.status_setup_container);
            onContainer = ContextCompat.getColor(this, R.color.status_setup);
        } else if (mode == GuardState.Mode.ON || mode == GuardState.Mode.SNOOZED) {
            titleRes = mode == GuardState.Mode.SNOOZED ? R.string.status_snoozed_title : R.string.status_protected_title;
            subtitleRes = mode == GuardState.Mode.SNOOZED ? R.string.status_snoozed_subtitle : R.string.status_protected_subtitle;
            icon = R.drawable.ic_shield_check;
            container = ContextCompat.getColor(this, R.color.status_protected_container);
            onContainer = ContextCompat.getColor(this, R.color.status_protected);
        } else if (mode == GuardState.Mode.PAUSED) {
            titleRes = R.string.status_paused_title;
            subtitleRes = R.string.status_paused_subtitle;
            icon = R.drawable.ic_warning;
            container = ContextCompat.getColor(this, R.color.status_setup_container);
            onContainer = ContextCompat.getColor(this, R.color.status_setup);
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

        boolean strict = Prefs.strictFingerprint(this);
        if (binding.fingerprintStrictSwitch.isChecked() != strict) {
            binding.fingerprintStrictSwitch.setChecked(strict);
        }

        String summary = fingerprintSummaryFromPrefs(this);
        binding.fingerprintSummary.setText(summary.isEmpty()
                ? getString(R.string.fingerprint_summary_unset) : summary);

        int minSec = Prefs.minSecurityType(this);
        int targetChipId;
        if (minSec == android.net.wifi.WifiInfo.SECURITY_TYPE_PSK) {
            targetChipId = R.id.min_protection_wpa2;
        } else if (minSec == android.net.wifi.WifiInfo.SECURITY_TYPE_SAE) {
            targetChipId = R.id.min_protection_wpa3;
        } else {
            targetChipId = R.id.min_protection_off;
        }
        if (binding.minProtectionChips.getCheckedChipId() != targetChipId) {
            binding.minProtectionChips.check(targetChipId);
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

        // Pause: when active, swap the duration chips for a Resume action.
        boolean paused = Prefs.isPauseActive(this);
        binding.pauseButtons.setVisibility(paused ? View.GONE : View.VISIBLE);
        binding.pauseInf.setVisibility(paused ? View.GONE : View.VISIBLE);
        binding.pauseResume.setVisibility(paused ? View.VISIBLE : View.GONE);
        if (paused) {
            long remMs = Prefs.pauseRemainingMs(this);
            binding.pauseSubtitle.setText(remMs == Prefs.PAUSE_INDEFINITE
                    ? getString(R.string.pause_active_indefinite)
                    : getString(R.string.pause_active_remaining,
                        getString(R.string.snooze_remaining, (remMs + 59_999L) / 60_000L)));
        } else {
            binding.pauseSubtitle.setText(R.string.pause_subtitle);
        }
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
        binding.diagCard.diagAdbState.setText(valueOrDash(Prefs.lastAdbSummary(this)));

        List<String> history = Prefs.decisionHistory(this);
        binding.diagCard.diagHistory.setText(history.isEmpty()
                ? getString(R.string.diag_history_empty)
                : String.join("\n", history));
    }

    // ---------- Helpers ----------

    private boolean hasAllRuntimePermissions() {
        // Background location is intentionally NOT required here — it is optional
        // (it only adds auto-recovery after a reboot). Setup completes without it.
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
