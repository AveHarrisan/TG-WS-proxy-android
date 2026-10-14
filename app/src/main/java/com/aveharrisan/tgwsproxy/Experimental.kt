package com.aveharrisan.tgwsproxy

import android.content.Context
import android.content.SharedPreferences
import com.aveharrisan.tgwsproxy.core.Net
import com.aveharrisan.tgwsproxy.core.WsPool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Экспериментальный режим: экономия заряда. Всё выключено по умолчанию и применяется сразу. */
data class ExperimentalFlags(
    /** Пул заранее открытых соединений засыпает, если Telegram 5 минут не открывал новых. */
    val poolSleep: Boolean = false,
    /** Уведомление обновляется только при включённом экране и только когда цифры изменились. */
    val quietNotification: Boolean = false,
    /** Wi-Fi без режима «низкая задержка», даже когда включено «Не давать телефону засыпать». */
    val wifiPowerSave: Boolean = false,
    /** TCP keepalive через минуту простоя, чтобы сеть не обрывала соединения, по которым долго ничего не шло. */
    val tcpKeepalive: Boolean = false,
) {
    val any: Boolean get() = poolSleep || quietNotification || wifiPowerSave || tcpKeepalive
}

object Experimental {
    const val POOL_SLEEP_SEC = 300.0

    private lateinit var prefs: SharedPreferences
    private val _flow = MutableStateFlow(ExperimentalFlags())
    val flow: StateFlow<ExperimentalFlags> = _flow
    val current: ExperimentalFlags get() = _flow.value

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("experimental", Context.MODE_PRIVATE)
        _flow.value = ExperimentalFlags(
            poolSleep = prefs.getBoolean("poolSleep", false),
            quietNotification = prefs.getBoolean("quietNotification", false),
            wifiPowerSave = prefs.getBoolean("wifiPowerSave", false),
            tcpKeepalive = prefs.getBoolean("tcpKeepalive", false),
        )
        applyToCore()
    }

    fun set(f: ExperimentalFlags) {
        prefs.edit()
            .putBoolean("poolSleep", f.poolSleep)
            .putBoolean("quietNotification", f.quietNotification)
            .putBoolean("wifiPowerSave", f.wifiPowerSave)
            .putBoolean("tcpKeepalive", f.tcpKeepalive)
            .apply()
        _flow.value = f
        applyToCore()
    }

    private fun applyToCore() {
        WsPool.idleSleepSec = if (current.poolSleep) POOL_SLEEP_SEC else 0.0
        Net.keepIdleSec = if (current.tcpKeepalive) Net.KEEP_IDLE_SEC else 0
    }
}
