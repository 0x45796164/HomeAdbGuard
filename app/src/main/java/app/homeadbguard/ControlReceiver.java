package app.homeadbguard;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ControlReceiver extends BroadcastReceiver {
    static final String ACTION_PAUSE = "app.homeadbguard.action.PAUSE";
    static final String ACTION_RESUME = "app.homeadbguard.action.RESUME";
    static final String ACTION_END_SNOOZE = "app.homeadbguard.action.END_SNOOZE";
    static final String ACTION_STOP_GUARD = "app.homeadbguard.action.STOP_GUARD";

    /** Default duration when pausing from the notification (in-app offers a choice). */
    private static final int NOTIFICATION_PAUSE_MINUTES = 60;

    static PendingIntent pendingIntent(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, ControlReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            Prefs.setPauseUntil(context,
                    System.currentTimeMillis() + (long) NOTIFICATION_PAUSE_MINUTES * 60_000L);
            Prefs.setLastEvaluation(context, "Paused from notification for " + NOTIFICATION_PAUSE_MINUTES + " min");
            MonitorService.applyCurrentState(context);
        } else if (ACTION_RESUME.equals(action)) {
            Prefs.setPauseUntil(context, 0L);
            if (!Prefs.monitoring(context)) Prefs.setMonitoring(context, true);
            Prefs.setLastEvaluation(context, "Resumed from notification");
            MonitorService.requestStart(context);
            MonitorService.applyCurrentState(context, true);
        } else if (ACTION_END_SNOOZE.equals(action)) {
            Prefs.setSnoozeUntil(context, 0L);
            Prefs.setLastEvaluation(context, "Snooze ended from notification");
            MonitorService.applyCurrentState(context, true);
        } else if (ACTION_STOP_GUARD.equals(action)) {
            Prefs.setMonitoring(context, false);
            SecureSettings.disableNow(context);
            context.stopService(new Intent(context, MonitorService.class));
            NetworkWatch.disarm(context);
            Prefs.setLastEvaluation(context, "Guard stopped from notification");
        }
    }
}
