package com.aveharrisan.tgwsproxy

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Плитка в шторке: включить или выключить прокси одним касанием. */
class ProxyTileService : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        if (ProxyService.isActive) ProxyService.stop(this) else ProxyService.start(this)
        qsTile?.apply { state = if (ProxyService.isActive) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; updateTile() }
    }

    private fun update() {
        val tile = qsTile ?: return
        tile.state = if (ProxyState.status.value == Status.RUNNING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (tile.state == Tile.STATE_ACTIVE) ":${Settings.current.port}" else null
        tile.updateTile()
    }

    companion object {
        fun refresh(ctx: Context) {
            runCatching { requestListeningState(ctx, ComponentName(ctx, ProxyTileService::class.java)) }
        }
    }
}
