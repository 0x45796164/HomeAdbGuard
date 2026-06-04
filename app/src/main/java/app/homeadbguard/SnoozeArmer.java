package app.homeadbguard;

import android.content.Context;

/**
 * Single arming/cancelling point for snooze. Refuses to engage unless the
 * caller is currently on a verified-trusted Wi-Fi, so a malicious automation
 * trigger or stray broadcast cannot use snooze to keep ADB on while away
 * from home.
 */
final class SnoozeArmer {
    private SnoozeArmer() {
    }

    enum Result { ARMED, REFUSED_NOT_AT_HOME, ALREADY_ACTIVE }

    static Result arm(Context context, int minutes) {
        if (minutes <= 0) return Result.REFUSED_NOT_AT_HOME;
        WifiState wifi = WifiState.current(context);
        HomeMatcher.MatchResult match = HomeMatcher.evaluate(context, wifi);
        if (!match.atHome) {
            return Result.REFUSED_NOT_AT_HOME;
        }
        long until = System.currentTimeMillis() + (long) minutes * 60_000L;
        Prefs.setSnoozeUntil(context, until);
        Prefs.setLastEvaluation(context, "Snooze armed for " + minutes + " minutes");
        MonitorService.applyCurrentState(context, true);
        return Result.ARMED;
    }

    static void cancel(Context context) {
        Prefs.setSnoozeUntil(context, 0L);
        Prefs.setLastEvaluation(context, "Snooze cancelled");
        MonitorService.applyCurrentState(context);
    }
}
