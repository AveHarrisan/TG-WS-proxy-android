package com.aveharrisan.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Автозапуск после перезагрузки телефона и после обновления приложения, если включён в настройках.
 * После обновления из самого приложения прокси поднимается и без автозапуска, если работал до него.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val afterUpdate = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && Updater.takeRestartFlag(context)
        if (Settings.current.autostart || afterUpdate) runCatching { ProxyService.start(context) }
    }
}
