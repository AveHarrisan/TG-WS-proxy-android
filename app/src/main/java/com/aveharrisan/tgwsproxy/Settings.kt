package com.aveharrisan.tgwsproxy

import android.content.Context
import android.content.SharedPreferences
import com.aveharrisan.tgwsproxy.core.DcIpParser
import com.aveharrisan.tgwsproxy.core.Domains
import com.aveharrisan.tgwsproxy.core.ProxyConfig
import com.aveharrisan.tgwsproxy.core.Rnd
import com.aveharrisan.tgwsproxy.core.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Настройки приложения в том виде, в каком их видит пользователь (текстом). */
data class AppSettings(
    val port: Int = 1443,
    val secret: String = "",
    val dcIps: String = "2:149.154.167.220\n4:149.154.167.220",
    val cfProxy: Boolean = true,
    val cfDomains: String = "",
    val cfWorkerDomains: String = "",
    val poolSize: Int = 4,
    val noSecure: Boolean = false,
    val allowLan: Boolean = false,
    val fakeTlsDomain: String = "",
    val verbose: Boolean = false,
    val autostart: Boolean = false,
    val wakeLock: Boolean = true,
) {
    fun toConfig(): ProxyConfig = ProxyConfig(
        host = if (allowLan) "0.0.0.0" else "127.0.0.1",
        port = port,
        secret = secret,
        dcRedirects = DcIpParser.parse(dcIps.lines()),
        poolSize = poolSize,
        fallbackCfProxy = cfProxy,
        cfProxyUserDomains = Domains.coerceList(cfDomains),
        cfProxyWorkerDomains = Domains.coerceList(cfWorkerDomains),
        disableSecure = noSecure,
        fakeTlsDomain = fakeTlsDomain.trim(),
    ).also { it.validate() }

    /** Ссылка для Telegram на этом же устройстве. */
    fun localLink(): String = toConfigOrNull()?.copy(host = "127.0.0.1")?.link() ?: ""

    fun toConfigOrNull(): ProxyConfig? = runCatching { toConfig() }.getOrNull()
}

object Settings {
    private lateinit var prefs: SharedPreferences
    private val _flow = MutableStateFlow(AppSettings())
    val flow: StateFlow<AppSettings> = _flow
    val current: AppSettings get() = _flow.value

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val d = AppSettings()
        var secret = prefs.getString("secret", null)
        if (secret.isNullOrBlank()) {
            secret = Rnd.bytes(16).toHex()
            prefs.edit().putString("secret", secret).apply()
        }
        _flow.value = AppSettings(
            port = prefs.getInt("port", d.port),
            secret = secret,
            dcIps = prefs.getString("dcIps", d.dcIps)!!,
            cfProxy = prefs.getBoolean("cfProxy", d.cfProxy),
            cfDomains = prefs.getString("cfDomains", d.cfDomains)!!,
            cfWorkerDomains = prefs.getString("cfWorkerDomains", d.cfWorkerDomains)!!,
            poolSize = prefs.getInt("poolSize", d.poolSize),
            noSecure = prefs.getBoolean("noSecure", d.noSecure),
            allowLan = prefs.getBoolean("allowLan", d.allowLan),
            fakeTlsDomain = prefs.getString("fakeTlsDomain", d.fakeTlsDomain)!!,
            verbose = prefs.getBoolean("verbose", d.verbose),
            autostart = prefs.getBoolean("autostart", d.autostart),
            wakeLock = prefs.getBoolean("wakeLock", d.wakeLock),
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putInt("port", s.port)
            .putString("secret", s.secret)
            .putString("dcIps", s.dcIps)
            .putBoolean("cfProxy", s.cfProxy)
            .putString("cfDomains", s.cfDomains)
            .putString("cfWorkerDomains", s.cfWorkerDomains)
            .putInt("poolSize", s.poolSize)
            .putBoolean("noSecure", s.noSecure)
            .putBoolean("allowLan", s.allowLan)
            .putString("fakeTlsDomain", s.fakeTlsDomain)
            .putBoolean("verbose", s.verbose)
            .putBoolean("autostart", s.autostart)
            .putBoolean("wakeLock", s.wakeLock)
            .apply()
        _flow.value = s
    }

    fun newSecret(): String = Rnd.bytes(16).toHex()
}
