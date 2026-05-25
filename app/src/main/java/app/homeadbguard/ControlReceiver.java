package app.homeadbguard;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ControlReceiver extends BroadcastReceiver {
    static final String ACTION_DISABLE_NOW = "app.homeadbguard.action.DISABLE_NOW";
    static final String ACTION_APPLY_NOW = "app.homeadbguard.action.APPLY_NOW";
    static final String ACTION_STOP_MONITORING = "app.homeadbguard.action.STOP_MONITORING";

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
        if (ACTION_DISABLE_NOW.equals(action)) {
            SecureSettings.disableNow(context);
            Prefs.setLastEvaluation(context, "Manual disable from notification");
        } else if (ACTION_APPLY_NOW.equals(action)) {
            MonitorService.applyCurrentState(context);
        } else if (ACTION_STOP_MONITORING.equals(action)) {
            Prefs.setMonitoring(context, false);
            SecureSettings.disableNow(context);
            context.stopService(new Intent(context, MonitorService.class));
            NetworkWatch.disarm(context);
            Prefs.setLastEvaluation(context, "Monitoring stopped from notification");
        }
    }
}
