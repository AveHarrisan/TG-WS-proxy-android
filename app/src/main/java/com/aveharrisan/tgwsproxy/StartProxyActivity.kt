package com.aveharrisan.tgwsproxy

import android.app.Activity
import android.os.Bundle

/**
 * Прозрачный экран на долю секунды: запускает прокси и сразу закрывается.
 * Нужен плитке в шторке — на Android 14+ службу можно поднять, только когда приложение на экране.
 */
class StartProxyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProxyService.start(this)
        finish()
        overridePendingTransition(0, 0)
    }
}
