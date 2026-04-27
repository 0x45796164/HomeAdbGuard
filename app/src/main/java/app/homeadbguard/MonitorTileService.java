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
            MonitorService.applyCurrentState(this);
            Prefs.setLastEvaluation(this, "Monitoring started from Quick Settings tile");
        }
        renderTile();
    }

    private void renderTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean monitoring = Prefs.monitoring(this);
        WifiState wifi = WifiState.current(this);
        HomeMatcher.MatchResult match = HomeMatcher.evaluate(this, wifi);

        int icon;
        String label;
        String subtitle;
        int state;

        if (!monitoring) {
            icon = R.drawable.ic_shield_off;
            label = getString(R.string.tile_inactive_label);
            subtitle = getString(R.string.tile_inactive_subtitle);
            state = Tile.STATE_INACTIVE;
        } else if (match.atHome) {
            icon = R.drawable.ic_shield_check;
            label = getString(R.string.tile_active_home_label);
            subtitle = getString(R.string.tile_active_home_subtitle);
            state = Tile.STATE_ACTIVE;
        } else {
            icon = R.drawable.ic_shield_check;
            label = getString(R.string.tile_active_away_label);
            subtitle = getString(R.string.tile_active_away_subtitle);
            state = Tile.STATE_ACTIVE;
        }

        tile.setIcon(Icon.createWithResource(this, icon));
        tile.setLabel(label);
        tile.setSubtitle(subtitle);
        tile.setContentDescription(label + ", " + subtitle);
        tile.setState(state);
        tile.updateTile();
    }
}
