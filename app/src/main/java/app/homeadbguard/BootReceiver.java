package app.homeadbguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.monitoring(context)) return;
        boolean started = MonitorService.requestStart(context);
        if (!started) {
            SecureSettings.disableNow(context);
        }
    }
}
