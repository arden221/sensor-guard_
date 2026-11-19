package com.sensorguard.earbuds.service

import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sensorguard.earbuds.R
import com.sensorguard.earbuds.data.GuardRepository

class BypassPauseTileService : TileService() {

    override fun onCreate() {
        super.onCreate()
        GuardRepository.initialize(applicationContext)
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTileVisuals()
    }

    override fun onClick() {
        super.onClick()
        GuardRepository.triggerManualBypassPause(
            origin = "Quick Settings Tile",
            nowElapsedRealtime = SystemClock.elapsedRealtime()
        )
        refreshTileVisuals()
    }

    private fun refreshTileVisuals() {
        val tile = qsTile ?: return
        val now = SystemClock.elapsedRealtime()
        val inCooldown = now < GuardRepository.manualCooldownUntilElapsed.value
        tile.label = getString(R.string.tile_bypass_label)
        tile.state = if (inCooldown) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
