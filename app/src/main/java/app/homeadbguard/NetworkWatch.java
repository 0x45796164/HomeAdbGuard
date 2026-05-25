package app.homeadbguard;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

/**
 * Passive Wi-Fi arrival watch. When monitoring is on but the device is off
 * the home network, we tear the foreground service down and ask
 * {@link ConnectivityManager} to fire a {@link PendingIntent} the next time
 * any Wi-Fi network appears. The receiver ({@link HomeArrivalReceiver}) then
 * decides whether the arrived network is trusted and, if so, brings the
 * foreground service back up.
 */
final class NetworkWatch {
    static final String ACTION_HOME_ARRIVED = "app.homeadbguard.action.HOME_ARRIVED";
    private static final int REQUEST_CODE = 0;

    private NetworkWatch() {
    }

    static void arm(Context context) {
        if (Prefs.networkWatchArmed(context)) return;
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return;

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        try {
            cm.registerNetworkCallback(request, pendingIntent(context));
            Prefs.setNetworkWatchArmed(context, true);
        } catch (RuntimeException e) {
            Prefs.setLastEvaluation(context,
                    "NetworkWatch arm failed: " + e.getClass().getSimpleName());
        }
    }

    static void disarm(Context context) {
        if (!Prefs.networkWatchArmed(context)) return;
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            Prefs.setNetworkWatchArmed(context, false);
            return;
        }
        try {
            cm.unregisterNetworkCallback(pendingIntent(context));
        } catch (RuntimeException ignored) {
            // Not registered, or already unregistered. The flag clear below
            // restores the invariant either way.
        }
        Prefs.setNetworkWatchArmed(context, false);
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, HomeArrivalReceiver.class)
                .setAction(ACTION_HOME_ARRIVED);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }
}
