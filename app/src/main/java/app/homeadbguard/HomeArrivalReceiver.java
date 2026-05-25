package app.homeadbguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fired by the {@link android.net.ConnectivityManager} {@link android.app.PendingIntent}
 * armed via {@link NetworkWatch} when any Wi-Fi network becomes available.
 * If the user is still monitoring and the arrived network matches the saved
 * home Wi-Fi, this brings {@link MonitorService} back up. Otherwise the watch
 * stays armed for the next event.
 */
public final class HomeArrivalReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (!Prefs.monitoring(context)) {
                NetworkWatch.disarm(context);
                return;
            }
            WifiState wifi = WifiState.current(context);
            HomeMatcher.MatchResult match = HomeMatcher.evaluate(context, wifi);
            if (match.atHome) {
                NetworkWatch.disarm(context);
                MonitorService.requestStart(context);
            } else {
                // While the service is not running, nothing else holds these
                // settings closed — write them defensively on every wake.
                SecureSettings.setSafeState(context, false);
            }
        } catch (RuntimeException e) {
            Prefs.setLastEvaluation(context,
                    "HomeArrivalReceiver error: " + e.getClass().getSimpleName());
        }
    }
}
