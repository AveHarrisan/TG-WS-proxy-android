package com.aveharrisan.tgwsproxy.core

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Проверка, какие пути до Telegram открыты в текущей сети. */
object Diagnostics {
    data class Result(val method: String, val target: String, val ok: Boolean, val ms: Long, val detail: String)

    private fun measure(method: String, target: String, block: () -> String): Result {
        val t = System.nanoTime()
        return try {
            val d = block()
            Result(method, target, true, (System.nanoTime() - t) / 1_000_000, d)
        } catch (e: Exception) {
            // Тип ошибки + текст, доменами под маской: у UnknownHostException в тексте только сам адрес.
            val detail = when (e) {
                is java.net.UnknownHostException -> "адрес не найден (UnknownHost)"
                else -> e.message ?: e.javaClass.simpleName
            }
            Result(method, target, false, (System.nanoTime() - t) / 1_000_000, DomainCensor.apply(detail))
        }
    }

    /** Проверка одного Cloudflare Worker — для кнопки «Проверить» в настройках, до сохранения. */
    fun probeWorker(domain: String, secure: Boolean, timeoutMs: Int = 7000): Result =
        measure("CF Worker", DomainCensor.apply(domain)) {
            RawWebSocket.connect(domain, domain, timeoutMs, CfWorkerPool.workerPath(Proto.DC_DEFAULT_IPS.getValue(2), 2),
                secure = secure).close(); "101"
        }

    fun run(config: ProxyConfig, timeoutMs: Int = 7000, onResult: (Result) -> Unit = {}): List<Result> {
        val jobs = ArrayList<Callable<Result>>()
        for ((dc, ip) in config.dcRedirects) {
            val domain = Proto.wsDomains(dc, false).first()
            jobs += Callable { measure("WebSocket", "DC$dc $ip") { RawWebSocket.connect(ip, domain, timeoutMs).close(); "101" } }
            jobs += Callable {
                measure("Фронтинг", "DC$dc $ip") {
                    RawWebSocket.connect(ip, domain, timeoutMs, sni = WsPool.FRONTING_SNI).close(); "101"
                }
            }
        }
        if (config.fallbackCfProxy) {
            val domains = config.cfProxyUserDomains.ifEmpty { Balancer.domains.ifEmpty { CfDomains.DEFAULTS } }
            for (base in domains.shuffled().take(3)) jobs += Callable {
                measure("CF-прокси", DomainCensor.apply(base)) {
                    val d = "kws2.$base"
                    RawWebSocket.connect(d, d, timeoutMs, secure = !config.disableSecure).close(); "101"
                }
            }
        }
        for (w in config.cfProxyWorkerDomains) jobs += Callable {
            measure("CF Worker", DomainCensor.apply(w)) {
                RawWebSocket.connect(w, w, timeoutMs, CfWorkerPool.workerPath(Proto.DC_DEFAULT_IPS.getValue(2), 2),
                    secure = !config.disableSecure).close(); "101"
            }
        }
        for ((dc, ip) in Proto.DC_DEFAULT_IPS) jobs += Callable {
            measure("TCP напрямую", "DC$dc $ip") {
                Socket().use { it.connect(InetSocketAddress(ip, 443), timeoutMs) }; "порт открыт"
            }
        }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = jobs.map { pool.submit(it) }
            return futures.map { f ->
                val r = runCatching { f.get(timeoutMs * 3L, TimeUnit.MILLISECONDS) }
                    .getOrElse { Result("?", "?", false, 0, it.toString()) }
                onResult(r); r
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
