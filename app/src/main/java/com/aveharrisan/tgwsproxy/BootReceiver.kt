package com.aveharrisan.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Автозапуск после перезагрузки телефона и после обновления приложения, если включён в настройках. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Settings.current.autostart) runCatching { ProxyService.start(context) }
    }
}
