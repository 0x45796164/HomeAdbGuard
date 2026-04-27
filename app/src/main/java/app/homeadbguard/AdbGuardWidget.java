package app.homeadbguard;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public final class AdbGuardWidget extends AppWidgetProvider {

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
        boolean monitoring = Prefs.monitoring(context);
        WifiState wifi = WifiState.current(context);
        HomeMatcher.MatchResult match = HomeMatcher.evaluate(context, wifi);

        int icon;
        int titleRes;
        int subtitleRes;
        int containerColor;
        int onContainerColor;

        if (!monitoring) {
            icon = R.drawable.ic_warning;
            titleRes = R.string.status_idle_title;
            subtitleRes = R.string.tile_inactive_subtitle;
            containerColor = context.getColor(R.color.status_setup_container);
            onContainerColor = context.getColor(R.color.status_setup);
        } else if (match.atHome) {
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
