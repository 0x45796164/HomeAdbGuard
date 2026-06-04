package app.homeadbguard;

import android.content.Context;

/**
 * Resolves the guard's current mode from the primary Armed/Off switch, the
 * network verdict, and the two mutually-exclusive temporary overrides
 * (pause = temporary off; snooze = temporary on). {@link #adbShouldBeOn} is the
 * single desired-state output the enforcer acts on.
 */
final class GuardState {

    enum Mode {
        /** Disarmed: not managing ADB. */
        OFF,
        /** Armed, not on home Wi-Fi: ADB forced off. */
        AWAY,
        /** Armed, on home Wi-Fi, no override: ADB on. */
        ON,
        /** Armed, user paused: ADB temporarily off. */
        PAUSED,
        /** Armed, user snoozed: ADB stays on through a maintenance window. */
        SNOOZED
    }

    final Mode mode;
    final boolean adbShouldBeOn;
    final String reason;

    private GuardState(Mode mode, boolean adbShouldBeOn, String reason) {
        this.mode = mode;
        this.adbShouldBeOn = adbShouldBeOn;
        this.reason = reason == null ? "" : reason;
    }

    static GuardState resolve(Context ctx, HomeMatcher.MatchResult match) {
        if (!Prefs.monitoring(ctx)) {
            return new GuardState(Mode.OFF, false, "Guard off");
        }
        if (Prefs.isSnoozeActive(ctx)) {
            long rem = (Prefs.snoozeRemainingMs(ctx) + 59_999L) / 60_000L;
            return new GuardState(Mode.SNOOZED, true, "Snoozed — ADB on for " + rem + " more min");
        }
        if (Prefs.isPauseActive(ctx)) {
            long remMs = Prefs.pauseRemainingMs(ctx);
            String r = remMs == Prefs.PAUSE_INDEFINITE
                    ? "Paused — ADB off until you resume"
                    : "Paused — ADB off for " + ((remMs + 59_999L) / 60_000L) + " more min";
            return new GuardState(Mode.PAUSED, false, r);
        }
        if (match.atHome) {
            return new GuardState(Mode.ON, true, match.reason);
        }
        return new GuardState(Mode.AWAY, false, match.reason);
    }
}
