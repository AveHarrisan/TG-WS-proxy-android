package com.aveharrisan.tgwsproxy

import android.content.Context
import com.aveharrisan.tgwsproxy.core.Log

/**
 * Работал ли прокси, когда процесс завершился. Отметку снимает только остановка пользователем
 * (кнопка, плитка, уведомление). Если она стоит, а прокси не работает — его остановила система
 * (на Xiaomi/HyperOS это частое явление без разрешения на автозапуск).
 */
object RunMarker {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("run", Context.MODE_PRIVATE)

    fun set(ctx: Context, running: Boolean) {
        prefs(ctx).edit().putBoolean("running", running).apply()
    }

    fun wasRunning(ctx: Context): Boolean = prefs(ctx).getBoolean("running", false)

    /** При открытии приложения: прокси остановлен не нами — пишем в журнал и поднимаем обратно. */
    fun restoreIfKilled(ctx: Context) {
        if (!wasRunning(ctx) || ProxyService.isActive) return
        Log.w("Прокси был остановлен системой, а не вами — запускаю снова. На Xiaomi разрешите приложению автозапуск")
        ProxyService.start(ctx)
    }
}
