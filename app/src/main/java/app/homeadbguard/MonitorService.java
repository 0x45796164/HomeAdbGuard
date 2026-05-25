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

public final class MonitorService extends Service {
    static final String CHANNEL_ID = "monitor";
    static final int NOTIFICATION_ID = 42;
    private static final long WATCHDOG_MS = 30_000L;
    private static final long GRACE_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private BroadcastReceiver wakeReceiver;
    private long offHomeSinceMs = 0L;

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
            applyCurrentState(MonitorService.this);
            if (checkOffHomeGrace()) {
                // stopSelf was called; do not re-schedule.
                return;
            }
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    /**
     * Returns true if the service has stopped itself because the device has
     * been off-home longer than the grace window. Caller must then stop
     * re-scheduling the watchdog.
     */
    private boolean checkOffHomeGrace() {
        if (Prefs.isSnoozeActive(this)) {
            offHomeSinceMs = 0L;
            return false;
        }
        WifiState wifi = WifiState.current(this);
        HomeMatcher.MatchResult match = HomeMatcher.evaluate(this, wifi);
        if (match.atHome) {
            offHomeSinceMs = 0L;
            return false;
        }
        long now = System.currentTimeMillis();
        if (offHomeSinceMs == 0L) {
            offHomeSinceMs = now;
            return false;
        }
        if (now - offHomeSinceMs < GRACE_MS) {
            return false;
        }
        stopAndArmWatch();
        return true;
    }

    private void stopAndArmWatch() {
        SecureSettings.setSafeState(this, false);
        NetworkWatch.arm(this);
        Prefs.appendDecision(this,
                java.time.Instant.now()
                        + " — FGS stopped after off-home grace; passive Wi-Fi watch armed");
        stopSelf();
    }

    static boolean requestStart(Context context) {
        if (!Prefs.monitoring(context)) return false;

        // Snooze keeps the service alive regardless of network because the
        // user explicitly opted in while at home. Skip the at-home gate.
        if (!Prefs.isSnoozeActive(context)) {
            WifiState wifi = WifiState.current(context);
            HomeMatcher.MatchResult match = HomeMatcher.evaluate(context, wifi);
            if (!match.atHome) {
                SecureSettings.setSafeState(context, false);
                NetworkWatch.arm(context);
                Prefs.appendDecision(context,
                        java.time.Instant.now()
                                + " — Off-home; FGS not started, passive Wi-Fi watch armed ("
                                + match.reason + ")");
                return false;
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
        WifiState wifi = WifiState.current(context);
        HomeMatcher.MatchResult match = HomeMatcher.evaluate(context, wifi);

        ConnectivityManager localCm = context.getSystemService(ConnectivityManager.class);
        Network active = activeWifiNetwork(localCm);

        // Same-Network carry-over: if we were already at home on this exact
        // Network handle, and the OS just stopped exposing Wi-Fi identity to us
        // (typical on Samsung after screen-off privacy clamps), keep trusting it.
        if (!match.atHome && wifi.wifiTransportSeen && !wifi.isUsable()) {
            Network cached = lastAtHomeNetwork;
            if (active != null && cached != null && active.equals(cached)) {
                match = new HomeMatcher.MatchResult(true,
                        "Wi-Fi identity restricted; trusting last-confirmed home (same Network handle)");
            }
        }

        // Update the cache only on a fully-verified at-home decision.
        if (match.atHome && wifi.isUsable() && active != null) {
            lastAtHomeNetwork = active;
        }

        // Snooze: keep ADB enabled regardless. Snooze can only be armed when
        // the user was already at home (see SnoozeArmer), so this never enables
        // ADB on an unverified network — it only suppresses auto-disable.
        if (Prefs.isSnoozeActive(context)) {
            long remainingMin = (Prefs.snoozeRemainingMs(context) + 59_999L) / 60_000L;
            match = new HomeMatcher.MatchResult(true, "Snoozed for " + remainingMin + " more min");
        }

        SecureSettings.ApplyResult apply = SecureSettings.setSafeState(context, match.atHome);
        Instant now = Instant.now();
        String evaluation = now + ": atHome=" + match.atHome
                + ", reason=" + match.reason
                + ", wifi=" + wifiSummary(wifi)
                + ", apply=" + apply;
        Prefs.setLastEvaluation(context, evaluation);
        Prefs.appendDecision(context, now + " " + (match.atHome ? "ENABLE" : "DISABLE")
                + " — " + match.reason
                + (wifi.ssid == null || wifi.ssid.isEmpty() ? "" : " (on " + wifi.ssid + ")"));
        if (Prefs.monitoring(context)) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(context, wifi, match));
            }
        }
        AdbGuardWidget.refreshAll(context);
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
        startForeground(
                NOTIFICATION_ID,
                buildNotification(this, wifi, match),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        );
        cm = getSystemService(ConnectivityManager.class);
        registerNetworkCallback();
        registerWakeReceiver();
        applyCurrentState(this);
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_MS);
    }

    private void registerWakeReceiver() {
        if (wakeReceiver != null) return;
        wakeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                // Re-evaluate the moment Wi-Fi identity becomes readable again.
                applyCurrentState(MonitorService.this);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.monitoring(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        applyCurrentState(this);
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
        if (wakeReceiver != null) {
            try {
                unregisterReceiver(wakeReceiver);
            } catch (RuntimeException ignored) {
            }
            wakeReceiver = null;
        }
        super.onDestroy();
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
                applyCurrentState(MonitorService.this);
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

    static Notification buildNotification(Context ctx, WifiState wifi, HomeMatcher.MatchResult match) {
        PendingIntent open = PendingIntent.getActivity(
                ctx,
                0,
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        PendingIntent disable = ControlReceiver.pendingIntent(ctx, ControlReceiver.ACTION_DISABLE_NOW, 1);
        PendingIntent apply = ControlReceiver.pendingIntent(ctx, ControlReceiver.ACTION_APPLY_NOW, 2);
        PendingIntent stop = ControlReceiver.pendingIntent(ctx, ControlReceiver.ACTION_STOP_MONITORING, 3);

        String title = match.atHome
                ? "Protected — ADB on at home"
                : "Off-network — ADB disabled";
        String network = wifi.ssid == null || wifi.ssid.isEmpty() ? "no Wi-Fi" : wifi.ssid;
        String body = "Network: " + network + "\n" + match.reason;

        return new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_adb_guard)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_rotate, "Apply now", apply).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Disable now", disable).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build())
                .build();
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
