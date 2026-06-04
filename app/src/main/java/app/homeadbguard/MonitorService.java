package app.homeadbguard;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MonitorService extends Service {
    static final String CHANNEL_ID = "monitor";
    static final int NOTIFICATION_ID = 42;
    private static final long WATCHDOG_MS = 30_000L;
    private static final long GRACE_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private BroadcastReceiver userPresentReceiver;
    private long offHomeSinceMs = 0L;
    private long lastWatchdogTickMs = 0L;

    private static final ExecutorService DETECT_EXEC = Executors.newSingleThreadExecutor();
    private static volatile long lastHealMs = 0L;

    /**
     * Last Wi-Fi {@link Network} we positively confirmed as the user's home network.
     * Used to keep ADB allowed when the screen turns off and Samsung's privacy
     * stack starts returning {@code <unknown ssid>} for background reads — we
     * trust the cached state only while we are still on the *exact same*
     * Network handle (a roam or disconnect produces a new handle).
     */
    private static volatile Network lastAtHomeNetwork = null;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!Prefs.monitoring(MonitorService.this)) {
                stopSelf();
                return;
            }
            // A tick arriving much later than scheduled means the main looper
            // was frozen (Doze / device asleep). adbd commonly drops its
            // wireless listener across such a sleep while the setting stays 1,
            // so force a re-bind on the first tick after the gap; otherwise a
            // plain steady-state apply that never disrupts a live session.
            long now = System.currentTimeMillis();
            boolean dozeGap = lastWatchdogTickMs != 0L
                    && (now - lastWatchdogTickMs) > WATCHDOG_MS * 2;
            lastWatchdogTickMs = now;
            applyCurrentState(MonitorService.this, dozeGap);
            if (offHomeGraceExpired()) return;
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    private boolean offHomeGraceExpired() {
        if (Prefs.isSnoozeActive(this)) {
            offHomeSinceMs = 0L;
            return false;
        }
        WifiState wifi = WifiState.current(this);
        if (HomeMatcher.evaluate(this, wifi).atHome) {
            offHomeSinceMs = 0L;
            return false;
        }
        // Redacted Wi-Fi (transport present but identity unreadable — e.g. a
        // background read right after boot, before ACTION_USER_PRESENT) is
        // "unknown", NOT "away". Tearing the guard down here is what stranded
        // ADB off after reboot: the FGS died and nothing re-triggered once the
        // identity became readable. Keep watching instead of failing open the
        // teardown; the unlock receiver and watchdog re-evaluate.
        if (wifi.isRedacted()) {
            offHomeSinceMs = 0L;
            return false;
        }
        long now = System.currentTimeMillis();
        if (offHomeSinceMs == 0L) {
            offHomeSinceMs = now;
            return false;
        }
        if (now - offHomeSinceMs < GRACE_MS) return false;

        SecureSettings.setSafeState(this, false);
        NetworkWatch.arm(this);
        Prefs.appendDecision(this,
                Instant.now() + " — FGS stopped after off-home grace; passive Wi-Fi watch armed");
        stopSelf();
        return true;
    }

    static boolean requestStart(Context context) {
        if (!Prefs.monitoring(context)) return false;

        // Snooze keeps the service alive regardless of network because the
        // user explicitly opted in while at home. Skip the at-home gate.
        if (!Prefs.isSnoozeActive(context)) {
            WifiState wifi = WifiState.current(context);
            // Android 13+ redacts Wi-Fi identity for non-foreground callers
            // (QS tile, broadcast receivers). When the read comes back
            // redacted we cannot trust the off-home verdict — start the FGS
            // optimistically and let it re-evaluate from its own foreground
            // context. The 60s watchdog grace will clean up if it really is
            // off-home.
            if (!wifi.isRedacted()) {
                HomeMatcher.MatchResult match = HomeMatcher.evaluate(context, wifi);
                if (!match.atHome) {
                    SecureSettings.setSafeState(context, false);
                    NetworkWatch.arm(context);
                    Prefs.appendDecision(context,
                            Instant.now()
                                    + " — Off-home; FGS not started, passive Wi-Fi watch armed ("
                                    + match.reason + ")");
                    return false;
                }
            }
        }

        try {
            context.startForegroundService(new Intent(context, MonitorService.class));
            return true;
        } catch (ForegroundServiceStartNotAllowedException e) {
            Prefs.setLastEvaluation(context, "Foreground service start denied: " + e.getClass().getSimpleName());
            SecureSettings.disableNow(context);
            return false;
        } catch (RuntimeException e) {
            Prefs.setLastEvaluation(context, "Foreground service start failed: " + e.getClass().getSimpleName());
            SecureSettings.disableNow(context);
            return false;
        }
    }

    static void applyCurrentState(Context context) {
        applyCurrentState(context, false);
    }

    /**
     * @param forceAdbRebind when true and the verdict is at-home, re-bind adbd
     *     via a {@code 0 -> 1} toggle instead of a plain write. Pass true on
     *     re-establishment events (service (re)start, Wi-Fi arrival, Doze exit,
     *     explicit "Apply now"); false on steady-state re-evaluation so a live
     *     wireless session is never interrupted.
     */
    static void applyCurrentState(Context context, boolean forceAdbRebind) {
        WifiState wifi = WifiState.current(context);
        HomeMatcher.MatchResult match = HomeMatcher.evaluate(context, wifi);
        Network active = activeWifiNetwork(context.getSystemService(ConnectivityManager.class));

        // Same-Network carry-over: if we were already at home on this exact
        // Network handle, and the OS just stopped exposing Wi-Fi identity to us
        // (typical on Samsung after screen-off privacy clamps), keep trusting it.
        if (!match.atHome && wifi.wifiTransportSeen && !wifi.isUsable()) {
            Network cached = lastAtHomeNetwork;
            if (active != null && cached != null && active.equals(cached)) {
                match = new HomeMatcher.MatchResult(true,
                        "Wi-Fi identity restricted; trusting last-confirmed home (same Network handle)");
            }
        } else if (match.atHome && wifi.isUsable() && active != null) {
            // Update the cache only on a fully-verified at-home decision.
            lastAtHomeNetwork = active;
        }

        // Resolve the four-mode state (OFF / AWAY / ON / PAUSED / SNOOZED) from
        // the Armed switch, the network verdict, and the temporary overrides.
        GuardState state = GuardState.resolve(context, match);

        SecureSettings.ApplyResult apply =
                SecureSettings.setSafeState(context, state.adbShouldBeOn, forceAdbRebind);
        Instant now = Instant.now();
        Prefs.setLastEvaluation(context, now + ": mode=" + state.mode
                + ", reason=" + state.reason
                + ", wifi=" + wifiSummary(wifi)
                + ", apply=" + apply);
        Prefs.setLastDecision(context, state.adbShouldBeOn, state.reason);
        Prefs.setLastMode(context, state.mode.name());
        Prefs.appendDecision(context, now + " " + state.mode
                + " — " + state.reason
                + (wifi.ssid == null || wifi.ssid.isEmpty() ? "" : " (on " + wifi.ssid + ")"));
        if (Prefs.monitoring(context)) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(context, wifi, state));
            }
        }

        if (state.adbShouldBeOn && Prefs.monitoring(context)) {
            scheduleDetectAndRecover(context.getApplicationContext(), forceAdbRebind);
        }
    }

    /**
     * Off-main detection + bounded recovery. We cannot read the live TLS port on
     * hardened/SELinux-locked devices, so recovery is driven by the
     * <em>read-back</em> of {@code adb_wifi_enabled}: if it does not read {@code 1}
     * after we asked for ON (it was reverted, or never took), re-assert it with a
     * {@code 0 -> 1} toggle. The port/socket probe still runs (best effort) and can
     * upgrade the verdict to SETTING_ON_UNVERIFIED where readable, which also
     * triggers recovery. Bounded to once per watchdog interval, and skipped right
     * after a forced toggle so a fresh enable (which needs time to take) cannot
     * start a re-toggle storm.
     */
    private static void scheduleDetectAndRecover(Context appContext, boolean wasForced) {
        DETECT_EXEC.execute(() -> {
            AdbState.Snapshot snap = AdbState.detect(appContext);
            Prefs.setLastAdbState(appContext, snap.confidence.name(), snap.summary());

            boolean readBackNotOn = snap.adbWifiSetting != 1; // asked ON but reads off/unreadable ⇒ re-assert
            if (!wasForced && readBackNotOn) {
                long now = System.currentTimeMillis();
                if (now - lastHealMs >= WATCHDOG_MS) {
                    lastHealMs = now;
                    SecureSettings.forceAdbWifiRebind(appContext);
                    Prefs.appendDecision(appContext, Instant.now()
                            + " — recover: ADB not confirmed on (" + snap.summary() + "), re-asserting");
                }
            }

            if (Prefs.monitoring(appContext)) {
                NotificationManager nm = appContext.getSystemService(NotificationManager.class);
                if (nm != null) {
                    WifiState wifi = WifiState.current(appContext);
                    HomeMatcher.MatchResult match = HomeMatcher.evaluate(appContext, wifi);
                    nm.notify(NOTIFICATION_ID, buildNotification(appContext, wifi, GuardState.resolve(appContext, match)));
                }
            }
        });
    }

    private static Network activeWifiNetwork(ConnectivityManager localCm) {
        if (localCm == null) return null;
        try {
            Network active = localCm.getActiveNetwork();
            if (active == null) return null;
            NetworkCapabilities caps = localCm.getNetworkCapabilities(active);
            if (caps == null) return null;
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? active : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String verifiedLine(Context ctx) {
        String confidence = Prefs.lastAdbConfidence(ctx);
        switch (confidence) {
            case "VERIFIED_ON":
                return "ADB verified listening";
            case "OFF":
            case "UNKNOWN":
                return "Re-asserting ADB…"; // read-back not confirmed on → recovering
            default:
                return "ADB on";
        }
    }

    static String wifiSummary(WifiState wifi) {
        return "SSID=" + valueOrDash(wifi.ssid)
                + ", BSSID=" + valueOrDash(wifi.bssid)
                + ", source=" + valueOrDash(wifi.source)
                + ", wifiTransportSeen=" + wifi.wifiTransportSeen
                + ", locationEnabled=" + wifi.locationEnabled
                + ", fineLocation=" + wifi.hasFineLocation
                + ", nearbyWifiDevices=" + wifi.hasNearbyWifiDevices;
    }

    private static String valueOrDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        // The FGS is the active monitor while it is running; the passive
        // Wi-Fi watch (if armed) is redundant. Stop it.
        NetworkWatch.disarm(this);
        WifiState wifi = WifiState.current(this);
        HomeMatcher.MatchResult match = HomeMatcher.evaluate(this, wifi);
        try {
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification(this, wifi, GuardState.resolve(this, match)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            );
        } catch (RuntimeException startDenied) {
            // A location-type FGS cannot be started from the background without
            // ACCESS_BACKGROUND_LOCATION (optional). Degrade gracefully: arm the
            // passive Wi-Fi watch and stop; the next time the app is opened (or a
            // Wi-Fi event fires) it starts from a foreground context instead.
            Prefs.setLastEvaluation(this, "FGS start denied (background location off?): "
                    + startDenied.getClass().getSimpleName());
            NetworkWatch.arm(this);
            stopSelf();
            return;
        }
        cm = getSystemService(ConnectivityManager.class);
        registerNetworkCallback();
        registerUserPresentReceiver();
        // Service is (re)starting — adbd may be fresh (boot) or have dropped its
        // listener; force a re-bind so ADB actually comes up, not just the setting.
        applyCurrentState(this, true);
        lastWatchdogTickMs = System.currentTimeMillis();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.monitoring(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Explicit (re)start command (boot, app update, sticky redelivery,
        // requestStart) — treat as a re-establishment event.
        applyCurrentState(this, true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(watchdog);
        if (cm != null && callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (RuntimeException ignored) {
            }
        }
        if (userPresentReceiver != null) {
            try {
                unregisterReceiver(userPresentReceiver);
            } catch (RuntimeException ignored) {
            }
            userPresentReceiver = null;
        }
        super.onDestroy();
    }

    /**
     * Re-evaluate the moment the device is unlocked (and on screen-on). After a
     * reboot the OS only delivers BOOT_COMPLETED (and only lifts Wi-Fi-identity
     * redaction for our reads) once the user has unlocked — so unlock is the
     * correct, reliable point to confirm the network and bring ADB back on.
     */
    private void registerUserPresentReceiver() {
        if (userPresentReceiver != null) return;
        userPresentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Prefs.monitoring(MonitorService.this)) {
                    applyCurrentState(MonitorService.this, true);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(userPresentReceiver, filter);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerNetworkCallback() {
        if (cm == null || callback != null) return;

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // A Wi-Fi network just (re)appeared — any prior adbd wireless
                // session is gone with the old link; force a re-bind.
                applyCurrentState(MonitorService.this, true);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                applyCurrentState(MonitorService.this);
            }

            @Override
            public void onLost(Network network) {
                Network cached = lastAtHomeNetwork;
                if (cached != null && cached.equals(network)) {
                    lastAtHomeNetwork = null;
                }
                applyCurrentState(MonitorService.this);
            }

            @Override
            public void onUnavailable() {
                lastAtHomeNetwork = null;
                SecureSettings.setSafeState(MonitorService.this, false);
            }
        };

        try {
            cm.registerNetworkCallback(request, callback);
        } catch (RuntimeException e) {
            Prefs.setLastEvaluation(this, "Network callback registration failed: " + e.getClass().getSimpleName());
        }
    }

    static Notification buildNotification(Context ctx, WifiState wifi, GuardState state) {
        PendingIntent open = PendingIntent.getActivity(
                ctx,
                0,
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        PendingIntent pause = ControlReceiver.pendingIntent(ctx, ControlReceiver.ACTION_PAUSE, 1);
        PendingIntent resume = ControlReceiver.pendingIntent(ctx, ControlReceiver.ACTION_RESUME, 2);
        PendingIntent stopGuard = ControlReceiver.pendingIntent(ctx, ControlReceiver.ACTION_STOP_GUARD, 3);
        PendingIntent endSnooze = ControlReceiver.pendingIntent(ctx, ControlReceiver.ACTION_END_SNOOZE, 4);

        String network = wifi.ssid == null || wifi.ssid.isEmpty() ? "no Wi-Fi" : wifi.ssid;
        String title;
        String body;

        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_adb_guard)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false);

        switch (state.mode) {
            case ON:
                title = "Protected · Wireless ADB on";
                body = network + " · " + verifiedLine(ctx);
                b.addAction(action(android.R.drawable.ic_media_pause, "Pause", pause));
                b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop guard", stopGuard));
                break;
            case PAUSED:
                title = "Paused · " + state.reason.replaceFirst("^Paused — ", "");
                body = network + " · resumes automatically";
                b.addAction(action(android.R.drawable.ic_media_play, "Resume", resume));
                b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop guard", stopGuard));
                break;
            case SNOOZED:
                title = "Snoozed · " + state.reason.replaceFirst("^Snoozed — ", "");
                body = network + " · staying on for maintenance";
                b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "End snooze", endSnooze));
                b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop guard", stopGuard));
                break;
            case AWAY:
                title = "Protected · ADB off (away)";
                body = network + " · " + state.reason;
                b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop guard", stopGuard));
                break;
            case OFF:
            default:
                title = "Guard off";
                body = "Not managing ADB";
                b.addAction(action(android.R.drawable.ic_media_play, "Resume guard", resume));
                break;
        }

        return b.setContentTitle(title)
                .setContentText(body)
                .build();
    }

    private static Notification.Action action(int icon, String label, PendingIntent pi) {
        return new Notification.Action.Builder(icon, label, pi).build();
    }

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Home ADB Guard monitor",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shown while Home ADB Guard monitors the current Wi-Fi network.");
        nm.createNotificationChannel(channel);
    }
}
