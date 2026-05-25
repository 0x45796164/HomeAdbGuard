package app.homeadbguard;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public final class AdbGuardWidget extends AppWidgetProvider {

    static final int STATUS_INACTIVE = 0;
    static final int STATUS_AT_HOME = 1;
    static final int STATUS_AWAY = 2;

    static int currentStatus(Context context) {
        if (!Prefs.monitoring(context)) return STATUS_INACTIVE;
        WifiState wifi = WifiState.current(context);
        HomeMatcher.MatchResult match = HomeMatcher.evaluate(context, wifi);
        return match.atHome ? STATUS_AT_HOME : STATUS_AWAY;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            manager.updateAppWidget(id, buildRemoteViews(context));
        }
    }

    static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) return;
        ComponentName component = new ComponentName(context, AdbGuardWidget.class);
        int[] ids = manager.getAppWidgetIds(component);
        if (ids == null || ids.length == 0) return;
        RemoteViews views = buildRemoteViews(context);
        for (int id : ids) manager.updateAppWidget(id, views);
    }

    private static RemoteViews buildRemoteViews(Context context) {
        int status = currentStatus(context);

        int icon;
        int titleRes;
        int subtitleRes;
        int containerColor;
        int onContainerColor;

        if (status == STATUS_INACTIVE) {
            icon = R.drawable.ic_warning;
            titleRes = R.string.status_idle_title;
            subtitleRes = R.string.tile_inactive_subtitle;
            containerColor = context.getColor(R.color.status_setup_container);
            onContainerColor = context.getColor(R.color.status_setup);
        } else if (status == STATUS_AT_HOME) {
            icon = R.drawable.ic_shield_check;
            titleRes = R.string.status_protected_title;
            subtitleRes = R.string.tile_active_home_subtitle;
            containerColor = context.getColor(R.color.status_protected_container);
            onContainerColor = context.getColor(R.color.status_protected);
        } else {
            icon = R.drawable.ic_shield_off;
            titleRes = R.string.status_blocked_title;
            subtitleRes = R.string.tile_active_away_subtitle;
            containerColor = context.getColor(R.color.status_blocked_container);
            onContainerColor = context.getColor(R.color.status_blocked);
        }

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_home_adb_guard);
        views.setInt(R.id.widget_root, "setBackgroundColor", containerColor);
        views.setImageViewResource(R.id.widget_icon, icon);
        views.setInt(R.id.widget_icon, "setColorFilter", onContainerColor);
        views.setTextViewText(R.id.widget_title, context.getString(titleRes));
        views.setTextColor(R.id.widget_title, onContainerColor);
        views.setTextViewText(R.id.widget_subtitle, context.getString(subtitleRes));
        views.setTextColor(R.id.widget_subtitle, onContainerColor);

        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                context,
                0,
                open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        views.setOnClickPendingIntent(R.id.widget_root, pi);

        return views;
    }
}
