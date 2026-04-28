package app.homeadbguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Exported receiver that lets automation apps (Tasker, Macrodroid, scripts using
 * {@code adb shell am broadcast}) trigger the same actions the in-app buttons do.
 *
 * Security: ENABLE and SNOOZE both go through {@link SecureSettings#enableNowIfAtHome}
 * / {@link SnoozeArmer#arm}, which refuse unless the device is currently on a
 * verified-trusted Wi-Fi. DISABLE and RECHECK are non-weakening, so they run
 * unconditionally.
 */
public final class AutomationReceiver extends BroadcastReceiver {
    public static final String ACTION_ENABLE   = "app.homeadbguard.automation.ENABLE";
    public static final String ACTION_DISABLE  = "app.homeadbguard.automation.DISABLE";
    public static final String ACTION_RECHECK  = "app.homeadbguard.automation.RECHECK";
    public static final String ACTION_SNOOZE_15 = "app.homeadbguard.automation.SNOOZE_15";
    public static final String ACTION_SNOOZE_30 = "app.homeadbguard.automation.SNOOZE_30";
    public static final String ACTION_SNOOZE_60 = "app.homeadbguard.automation.SNOOZE_60";
    public static final String ACTION_SNOOZE_CANCEL = "app.homeadbguard.automation.SNOOZE_CANCEL";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        switch (intent.getAction()) {
            case ACTION_ENABLE:
                SecureSettings.enableNowIfAtHome(context);
                break;
            case ACTION_DISABLE:
                SecureSettings.disableNow(context);
                break;
            case ACTION_RECHECK:
                MonitorService.applyCurrentState(context);
                break;
            case ACTION_SNOOZE_15:
                SnoozeArmer.arm(context, 15);
                break;
            case ACTION_SNOOZE_30:
                SnoozeArmer.arm(context, 30);
                break;
            case ACTION_SNOOZE_60:
                SnoozeArmer.arm(context, 60);
                break;
            case ACTION_SNOOZE_CANCEL:
                SnoozeArmer.cancel(context);
                break;
            default:
                // Unknown action; ignore.
        }
    }
}
