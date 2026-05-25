package app.homeadbguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!Prefs.monitoring(context)) return;
        // requestStart now gates on at-home; if we are off-home it arms
        // the passive Wi-Fi watch instead of starting the FGS, and writes
        // safe state defensively. Either path leaves the device safe.
        MonitorService.requestStart(context);
    }
}
