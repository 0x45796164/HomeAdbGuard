package app.homeadbguard;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!Prefs.monitoring(MonitorService.this)) {
                stopSelf();
                return;
            }
            applyCurrentState(MonitorService.this);
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    static boolean requestStart(Context context) {
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
        WifiState wifi = WifiState.current(this);
        HomeMatcher.MatchResult match = HomeMatcher.evaluate(this, wifi);
        startForeground(
                NOTIFICATION_ID,
                buildNotification(this, wifi, match),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        );
        cm = getSystemService(ConnectivityManager.class);
        registerNetworkCallback();
        applyCurrentState(this);
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_MS);
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
                applyCurrentState(MonitorService.this);
            }

            @Override
            public void onUnavailable() {
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
