package app.homeadbguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Best-effort, non-privileged discovery of the wireless-debugging TLS
 * <em>connect</em> port via mDNS/NSD.
 *
 * <p>AOSP's adbd advertises the active wireless endpoint as
 * {@code _adb-tls-connect._tcp} on a random port, so we browse for it, resolve
 * the candidates, and keep the one whose advertised host is one of <em>our</em>
 * local addresses (another device on the LAN may be advertising its own
 * wireless debugging). The port is randomised on every adbd (re)bind — reboot,
 * a forced re-establishment — so we re-discover after each such event and alert
 * the user when it changes, since the old {@code adb connect host:port} stops
 * working.
 *
 * <p>The direct privileged API ({@code IAdbManager.getAdbWirelessPort()}) is
 * gated behind {@code signature|privileged} MANAGE_DEBUGGING and is unavailable
 * to a sideloaded app, and the port is not exposed as a {@code Settings.Global}
 * value, so mDNS is the correct approach here.
 */
final class AdbPortFinder {
    static final String SERVICE_TYPE = "_adb-tls-connect._tcp.";
    static final int PORT_CHANGED_NOTIFICATION_ID = 43;
    static final String CHANNEL_ID = "adb_port";

    /** Let a just-toggled adbd finish (re)binding and (re)advertising first. */
    private static final long START_DELAY_MS = 1_200L;
    /** How long to listen for an advertisement before giving up this pass. */
    private static final long DISCOVERY_WINDOW_MS = 12_000L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private AdbPortFinder() {
    }

    /** Kick a single bounded discovery pass. No-op if one is already running. */
    static void refresh(Context context) {
        Context app = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) return;
        try {
            new Session(app).start();
        } catch (RuntimeException e) {
            RUNNING.set(false);
        }
    }

    /** Forget the tracked port and dismiss any change alert (discovery off / ADB off). */
    static void clear(Context context) {
        if (Prefs.adbPort(context) != 0) {
            Prefs.setAdbPort(context, 0);
            MonitorService.updateNotification(context);
        }
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(PORT_CHANGED_NOTIFICATION_ID);
    }

    private static final class Session {
        private final Context app;
        private final NsdManager nsd;
        private final Handler handler = new Handler(Looper.getMainLooper());
        // core=0 + keep-alive so the worker thread self-reaps when idle (no leak
        // per pass); DiscardPolicy so any NSD callback that races a shutdown() is
        // dropped silently instead of throwing on the framework's delivery thread.
        private final ExecutorService executor = new ThreadPoolExecutor(
                0, 1, 5L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), new ThreadPoolExecutor.DiscardPolicy());
        private final Set<String> registeredNames = new HashSet<>();
        private final Set<NsdManager.ServiceInfoCallback> callbacks = new HashSet<>();
        private final Set<Integer> portsSeen = new HashSet<>();
        private WifiManager.MulticastLock lock;
        private NsdManager.DiscoveryListener discovery;
        private int fallbackPort;
        private boolean stopped;
        private boolean reported;

        Session(Context app) {
            this.app = app;
            this.nsd = app.getSystemService(NsdManager.class);
        }

        void start() {
            if (nsd == null) {
                finish();
                return;
            }
            WifiManager wifi = app.getSystemService(WifiManager.class);
            if (wifi != null) {
                try {
                    lock = wifi.createMulticastLock("home-adb-guard-mdns");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                } catch (RuntimeException ignored) {
                }
            }
            handler.postDelayed(this::beginDiscovery, START_DELAY_MS);
        }

        private void beginDiscovery() {
            synchronized (this) {
                if (stopped) return;
            }
            discovery = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String serviceType) { }
                @Override public void onDiscoveryStopped(String serviceType) { }
                @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) { finish(); }
                @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) { }
                @Override public void onServiceLost(NsdServiceInfo serviceInfo) { }
                @Override public void onServiceFound(NsdServiceInfo serviceInfo) { onFound(serviceInfo); }
            };
            try {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
            } catch (RuntimeException e) {
                finish();
                return;
            }
            handler.postDelayed(this::onTimeout, DISCOVERY_WINDOW_MS);
        }

        private synchronized void onFound(NsdServiceInfo info) {
            if (stopped) return;
            String name = info.getServiceName();
            if (name == null || !registeredNames.add(name)) return;
            NsdManager.ServiceInfoCallback cb = new NsdManager.ServiceInfoCallback() {
                @Override public void onServiceInfoCallbackRegistrationFailed(int errorCode) { }
                @Override public void onServiceInfoCallbackUnregistered() { }
                @Override public void onServiceLost() { }
                @Override public void onServiceUpdated(NsdServiceInfo resolved) { onResolved(resolved); }
            };
            try {
                nsd.registerServiceInfoCallback(info, executor, cb);
                callbacks.add(cb);
            } catch (RuntimeException ignored) {
            }
        }

        private synchronized void onResolved(NsdServiceInfo info) {
            if (stopped) return;
            int port = info.getPort();
            if (port <= 0) return;
            portsSeen.add(port);
            fallbackPort = port;
            Set<InetAddress> mine = LocalIp.localAddresses(app);
            for (InetAddress addr : info.getHostAddresses()) {
                if (mine.contains(addr)) {
                    report(port);
                    finish();
                    return;
                }
            }
        }

        private void onTimeout() {
            int port;
            synchronized (this) {
                if (stopped) return;
                // Single-candidate fallback: if exactly one wireless-debugging
                // advertiser was seen on the LAN it is almost certainly this
                // device, even when address matching did not line up.
                port = portsSeen.size() == 1 ? fallbackPort : 0;
            }
            if (port > 0) report(port);
            finish();
        }

        private synchronized void report(int port) {
            if (reported) return;
            reported = true;
            int previous = Prefs.adbPort(app);
            Prefs.setAdbPort(app, port);
            MonitorService.updateNotification(app);
            if (previous != 0 && previous != port) {
                notifyPortChanged(app, port);
            }
        }

        private synchronized void finish() {
            if (stopped) return;
            stopped = true;
            handler.removeCallbacksAndMessages(null);
            for (NsdManager.ServiceInfoCallback cb : callbacks) {
                try {
                    nsd.unregisterServiceInfoCallback(cb);
                } catch (RuntimeException ignored) {
                }
            }
            callbacks.clear();
            if (discovery != null) {
                try {
                    nsd.stopServiceDiscovery(discovery);
                } catch (RuntimeException ignored) {
                }
                discovery = null;
            }
            if (lock != null) {
                try {
                    if (lock.isHeld()) lock.release();
                } catch (RuntimeException ignored) {
                }
                lock = null;
            }
            executor.shutdown();
            RUNNING.set(false);
        }
    }

    private static void notifyPortChanged(Context ctx, int port) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Wireless ADB port changes",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Alerts when the wireless debugging port changes so you can reconnect.");
        nm.createNotificationChannel(channel);

        String ip = LocalIp.firstUsableIpv4(ctx);
        String target = (ip == null ? "" : ip + ":") + port;
        PendingIntent open = PendingIntent.getActivity(
                ctx,
                0,
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_adb_guard)
                .setContentTitle("Wireless ADB port changed")
                .setContentText("Now " + target + " — tap to view / copy the connect command")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        nm.notify(PORT_CHANGED_NOTIFICATION_ID, n);
    }
}
