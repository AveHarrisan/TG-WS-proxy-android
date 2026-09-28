package com.aveharrisan.tgwsproxy

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Плитка в шторке: включить или выключить прокси одним касанием. */
class ProxyTileService : TileService() {
    override fun onStartListening() {
        if (!TileAdder.added.value) TileAdder.markAdded(this, true)
        update()
    }

    override fun onTileAdded() = TileAdder.markAdded(this, true)

    override fun onTileRemoved() = TileAdder.markAdded(this, false)

    override fun onClick() {
        if (ProxyService.isActive) ProxyService.stop(this)
        else if (!ProxyService.start(this)) {
            // Система не дала запустить службу из шторки: запускаем через прозрачный экран,
            // с ним приложение на виду и запуск разрешён. Шторка при этом свернётся.
            if (isLocked) unlockAndRun { launchTrampoline() } else launchTrampoline()
            return
        }
        qsTile?.apply { state = if (ProxyService.isActive) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; updateTile() }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchTrampoline() {
        val intent = Intent(this, StartProxyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
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
