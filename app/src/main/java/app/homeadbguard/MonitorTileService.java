package app.homeadbguard;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile that mirrors monitoring state.
 * - Tap when active   -> stop monitoring + force disable.
 * - Tap when inactive -> start monitoring + apply current state.
 *
 * Uses unlockAndRun to require the device be unlocked before any state change,
 * so the tile cannot weaken the protection from a locked screen.
 */
public final class MonitorTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        renderTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        unlockAndRun(this::performToggle);
    }

    private void performToggle() {
        if (Prefs.monitoring(this)) {
            Prefs.setMonitoring(this, false);
            SecureSettings.disableNow(this);
            stopService(new Intent(this, MonitorService.class));
            Prefs.setLastEvaluation(this, "Monitoring stopped from Quick Settings tile");
        } else {
            Prefs.setMonitoring(this, true);
            MonitorService.requestStart(this);
            MonitorService.applyCurrentState(this, true);
            Prefs.setLastEvaluation(this, "Monitoring started from Quick Settings tile");
        }
        renderTile();
    }

    private void renderTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        int icon;
        String label;
        String subtitle;
        int state;

        if (!Prefs.monitoring(this)) {
            icon = R.drawable.ic_shield_off;
            label = getString(R.string.tile_inactive_label);
            subtitle = getString(R.string.tile_inactive_subtitle);
            state = Tile.STATE_INACTIVE;
        } else if (!Prefs.lastDecisionPresent(this)) {
            // Monitoring just turned on; FGS hasn't written a decision yet.
            // Don't call WifiState.current() — Android 13+ redacts Wi-Fi
            // identity for non-foreground processes like this tile binding.
            icon = R.drawable.ic_shield_check;
            label = getString(R.string.tile_active_home_label);
            subtitle = getString(R.string.tile_evaluating_subtitle);
            state = Tile.STATE_ACTIVE;
        } else {
            icon = R.drawable.ic_shield_check;
            label = getString(R.string.tile_active_home_label);
            state = Tile.STATE_ACTIVE;
            switch (Prefs.lastMode(this)) {
                case "PAUSED":
                    subtitle = getString(R.string.tile_paused_subtitle);
                    break;
                case "SNOOZED":
                    subtitle = getString(R.string.tile_snoozed_subtitle);
                    break;
                case "AWAY":
                    subtitle = getString(R.string.tile_active_away_subtitle);
                    break;
                default: // ON (or legacy)
                    subtitle = getString(R.string.tile_active_home_subtitle);
                    break;
            }
        }

        tile.setIcon(Icon.createWithResource(this, icon));
        tile.setLabel(label);
        tile.setSubtitle(subtitle);
        tile.setContentDescription(label + ", " + subtitle);
        tile.setState(state);
        tile.updateTile();
    }
}
