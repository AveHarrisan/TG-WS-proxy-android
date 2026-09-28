package com.aveharrisan.tgwsproxy

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Плитка прокси в быстрых настройках (кружки в шторке). Добавлена она или нет,
 * система сообщает самой плитке — запоминаем это, чтобы не звать добавлять повторно.
 */
object TileAdder {
    private val _added = MutableStateFlow(false)
    val added: StateFlow<Boolean> = _added

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tile", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        _added.value = prefs(ctx).getBoolean("added", false)
    }

    fun markAdded(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("added", value).apply()
        _added.value = value
    }

    /** С Android 13 система сама показывает окно «Добавить плитку». На старых — только вручную. */
    val canRequest: Boolean get() = Build.VERSION.SDK_INT >= 33

    fun request(ctx: Context, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) { onResult(false); return }
        val sbm = ctx.getSystemService(StatusBarManager::class.java)
        sbm.requestAddTileService(
            ComponentName(ctx, ProxyTileService::class.java),
            ctx.getString(R.string.tile_label),
            Icon.createWithResource(ctx, R.drawable.ic_notification),
            ctx.mainExecutor,
        ) { result ->
            val ok = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            if (ok) markAdded(ctx, true)
            onResult(ok)
        }
    }
}
