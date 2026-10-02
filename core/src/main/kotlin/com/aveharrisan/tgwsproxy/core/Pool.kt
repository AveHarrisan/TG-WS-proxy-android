package com.aveharrisan.tgwsproxy.core

import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private fun now(): Double = System.nanoTime() / 1e9

private data class PoolKey(val dc: Int, val isMedia: Boolean) {
    override fun toString() = "DC$dc${if (isMedia) "m" else ""}"
}

/** Заранее открытые WS-соединения к датацентрам: первое подключение Telegram не ждёт TLS-рукопожатия. */
class WsPool(private val cfg: () -> ProxyConfig, private val io: ExecutorService, private val sched: ScheduledExecutorService) {
    private class Entry(val ws: RawWebSocket, val created: Double)

    private val idle = ConcurrentHashMap<PoolKey, ArrayDeque<Entry>>()
    private val refilling = ConcurrentHashMap.newKeySet<PoolKey>()
    private val rotating = ConcurrentHashMap<PoolKey, Future<*>>()
    private val refillFailures = ConcurrentHashMap<PoolKey, Int>()
    private val refillAfter = ConcurrentHashMap<PoolKey, Double>()
    @Volatile private var tryFrontingFirst = false
    /** После reset() прокси останавливается: новых задач не ставим, свежие соединения закрываем. */
    @Volatile private var closed = false
    private val idleGate = PoolIdle()

    private fun bucket(key: PoolKey) = idle.getOrPut(key) { ArrayDeque() }

    fun get(dc: Int, isMedia: Boolean, targetIp: String, domains: List<String>): RawWebSocket? {
        val key = PoolKey(dc, isMedia)
        if (idleGate.markDemand(now())) Log.i("WS pool проснулся: Telegram открыл новое соединение")
        val b = bucket(key)
        while (true) {
            val e = synchronized(b) { b.pollFirst() } ?: break
            val age = now() - e.created
            if (age > MAX_AGE || !e.ws.isAlive) { quietClose(e.ws); continue }
            Stats.poolHits.incrementAndGet()
            Log.d("WS pool hit $key (age=${"%.1f".format(age)}s, left=${synchronized(b) { b.size }})")
            reportSuccess(dc, isMedia)
            scheduleRefill(key, targetIp, domains)
            return e.ws
        }
        Stats.poolMisses.incrementAndGet()
        scheduleRefill(key, targetIp, domains)
        return null
    }

    fun reportSuccess(dc: Int, isMedia: Boolean) {
        val key = PoolKey(dc, isMedia)
        refillFailures.remove(key)
        refillAfter.remove(key)
    }

    private fun scheduleRefill(key: PoolKey, targetIp: String, domains: List<String>) {
        if (now() < (refillAfter[key] ?: 0.0)) return
        if (closed || !refilling.add(key)) return
        try {
            io.execute { refill(key, targetIp, domains) }
        } catch (e: RejectedExecutionException) {
            refilling.remove(key)
        }
    }

    private fun refill(key: PoolKey, targetIp: String, domains: List<String>) {
        try {
            val b = bucket(key)
            val needed = cfg().poolSize - synchronized(b) { b.size }
            if (needed <= 0) return
            val futures = try {
                (0 until needed).map { io.submit<RawWebSocket?> { connectOne(targetIp, domains) } }
            } catch (e: RejectedExecutionException) {
                return
            }
            var connected = 0
            for (f in futures) {
                val ws = runCatching { f.get() }.getOrNull() ?: continue
                // Прокси остановили, пока соединение открывалось, — не копим его в пуле.
                if (closed) { runCatching { ws.close() }; continue }
                synchronized(b) { b.addLast(Entry(ws, now())) }
                connected++
                scheduleRotation(key, targetIp, domains)
            }
            if (connected > 0) reportSuccess(key.dc, key.isMedia)
            else {
                val failures = (refillFailures[key] ?: 0) + 1
                refillFailures[key] = failures
                val delay = minOf(1.0 * (1 shl minOf(failures - 1, 12)), 3600.0)
                refillAfter[key] = now() + delay
                Log.i("WS pool refill failed for $key, retry in ${delay.toInt()}s")
            }
            Log.d("WS pool refilled $key: ${synchronized(b) { b.size }} ready")
        } finally {
            refilling.remove(key)
        }
    }

    private fun scheduleRotation(key: PoolKey, targetIp: String, domains: List<String>) {
        if (closed || rotating.containsKey(key)) return
        try {
            rotating[key] = sched.scheduleWithFixedDelay({ rotate(key, targetIp, domains) },
                CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // Планировщик уже выключен: прокси остановлен.
        }
    }

    private fun rotate(key: PoolKey, targetIp: String, domains: List<String>) {
        val b = idle[key] ?: return
        val t = now()
        // Экспериментально: Telegram давно не открывал новых соединений — пул засыпает,
        // чтобы не будить сеть пересозданием соединений. Первый же get() разбудит его.
        if (idleGate.shouldSleep(t, idleSleepSec)) {
            rotating.remove(key)?.cancel(false)
            val dropped = synchronized(b) { ArrayList(b).also { b.clear() } }
            dropped.forEach { quietClose(it.ws) }
            if (idleGate.fallAsleep()) Log.i("WS pool уснул: ${idleSleepSec.toInt()} с без новых соединений")
            return
        }
        val expired = ArrayList<RawWebSocket>()
        val size = synchronized(b) {
            val it = b.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (t - e.created >= MAX_AGE || !e.ws.isAlive) { expired += e.ws; it.remove() }
            }
            b.size
        }
        if (expired.isNotEmpty()) {
            expired.forEach { quietClose(it) }
            Log.d("WS pool rotated $key: ${expired.size} stale, $size ready")
        }
        if (size < cfg().poolSize) scheduleRefill(key, targetIp, domains)
    }

    private fun connectOne(targetIp: String, domains: List<String>): RawWebSocket? {
        for (domain in domains) {
            try {
                return connect(targetIp, domain, 8000)
            } catch (e: WsHandshakeError) {
                if (e.isRedirect) continue
                return null
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }

    /**
     * Подключение к датацентру: напрямую и фронтингом (тот же IP, другое имя сайта в TLS) наперегонки.
     * Провайдер может не только молчать (таймаут), но и обрывать TLS по имени kws*.web.telegram.org,
     * а на некоторых сетях то один, то другой путь отваливается раз в пару минут. Первым стартует путь,
     * сработавший в прошлый раз, второй — через [PathRace.STAGGER_MS]. Переадресация прямого пути —
     * окончательный ответ, фронтингом её не обходим.
     * Бросает исходную ошибку прямого подключения, если не вышло ни так, ни так.
     */
    fun connect(targetIp: String, domain: String, timeoutMs: Int, path: String = Proto.WS_PATH): RawWebSocket {
        val direct = { RawWebSocket.connect(targetIp, domain, timeoutMs, path) }
        val fronted = { RawWebSocket.connect(targetIp, domain, minOf(timeoutMs, 7000), path, sni = FRONTING_SNI) }
        val frontFirst = tryFrontingFirst
        val r = if (frontFirst) PathRace.race(fronted, direct, isFinal = { false })
            else PathRace.race(direct, fronted, isFinal = { it is WsHandshakeError && it.isRedirect })
        val directFail = r.failures[if (frontFirst) 1 else 0]
        val frontFail = r.failures[if (frontFirst) 0 else 1]
        val ws = r.value ?: throw (directFail ?: frontFail)!!.error
        val viaFronting = (r.winner == 0) == frontFirst
        if (viaFronting) {
            Stats.connectionsFronting.incrementAndGet()
            if (!tryFrontingFirst) {
                val why = directFail?.let { describe(it) } ?: "фронтинг ответил быстрее"
                Log.i("Прямое WS-подключение к $targetIp не прошло ($why) — работаю через фронтинг")
            }
        } else if (tryFrontingFirst) {
            val why = frontFail?.let { "фронтинг: ${describe(it)}" } ?: "ответило быстрее фронтинга"
            Log.i("Прямое WS-подключение к $targetIp снова работает ($why)")
        }
        tryFrontingFirst = viaFronting
        return ws
    }

    private fun describe(f: PathRace.Failure): String =
        if (RawWebSocket.isTimeout(f.error)) "нет ответа ${f.ms} мс"
        else "обрыв через ${f.ms} мс: ${f.error.javaClass.simpleName}${f.error.message?.let { ": " + it.take(80) } ?: ""}"

    fun warmup() {
        closed = false
        val c = cfg()
        for ((dc, ip) in c.dcRedirects) for (isMedia in listOf(false, true))
            scheduleRefill(PoolKey(dc, isMedia), ip, Proto.wsDomains(dc, isMedia))
        Log.i("WS pool warmup started for ${c.dcRedirects.size} DC(s)")
    }

    fun reset() {
        closed = true
        rotating.values.forEach { it.cancel(false) }
        rotating.clear()
        idle.values.forEach { b -> synchronized(b) { b.forEach { quietClose(it.ws) }; b.clear() } }
        idle.clear()
        refilling.clear()
        refillFailures.clear()
        refillAfter.clear()
        tryFrontingFirst = false
    }

    private fun quietClose(ws: RawWebSocket) {
        try { io.execute { runCatching { ws.close() } } } catch (e: RejectedExecutionException) { runCatching { ws.close() } }
    }

    companion object {
        const val MAX_AGE = 120.0
        const val CHECK_INTERVAL_MS = 5000L

        /** Через сколько секунд без новых соединений пул засыпает; 0 — никогда (как в оригинале). */
        @Volatile var idleSleepSec: Double = 0.0
        const val FRONTING_SNI = "sprinthost.ru"
    }
}

/** Когда пул засыпает и просыпается. Отдельно от сети, чтобы проверять тестами. */
internal class PoolIdle {
    @Volatile private var lastDemand = System.nanoTime() / 1e9
    @Volatile private var asleep = false

    /** Новое соединение от Telegram. true — пул спал и сейчас проснулся. */
    fun markDemand(t: Double): Boolean {
        lastDemand = t
        val wasAsleep = asleep
        asleep = false
        return wasAsleep
    }

    fun shouldSleep(t: Double, idleSec: Double): Boolean = idleSec > 0 && t - lastDemand >= idleSec

    /** true — только что уснул (чтобы писать в журнал один раз). */
    fun fallAsleep(): Boolean = !asleep.also { asleep = true }
}

/** Пул соединений к Cloudflare Worker (по одному на DC). */
class CfWorkerPool(private val cfg: () -> ProxyConfig, private val io: ExecutorService) {
    private class Entry(val ws: RawWebSocket, val created: Double, val domain: String)

    private val idle = ConcurrentHashMap<Int, ArrayDeque<Entry>>()
    private val refilling = ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var closed = false

    fun get(dc: Int, fallbackDst: String, workerDomains: List<String>): Pair<RawWebSocket, String>? {
        val b = idle.getOrPut(dc) { ArrayDeque() }
        while (true) {
            val e = synchronized(b) { b.pollFirst() } ?: break
            if (now() - e.created > MAX_AGE || !e.ws.isAlive) { runCatching { e.ws.close() }; continue }
            Stats.cfPoolHits.incrementAndGet()
            scheduleRefill(dc, fallbackDst, workerDomains)
            return e.ws to e.domain
        }
        Stats.cfPoolMisses.incrementAndGet()
        return null
    }

    private fun scheduleRefill(dc: Int, dst: String, domains: List<String>) {
        if (closed || !refilling.add(dc)) return
        try { io.execute {
            try {
                val b = idle.getOrPut(dc) { ArrayDeque() }
                val needed = minOf(cfg().poolSize, PER_DC_LIMIT) - synchronized(b) { b.size }
                repeat(maxOf(needed, 0)) {
                    val (ws, domain) = connectOne(domains, dst, dc) ?: return@execute
                    if (closed) { runCatching { ws.close() }; return@execute }
                    synchronized(b) { b.addLast(Entry(ws, now(), domain)) }
                }
            } finally {
                refilling.remove(dc)
            }
        } } catch (e: RejectedExecutionException) {
            refilling.remove(dc)
        }
    }

    fun connectOne(domains: List<String>, dst: String, dc: Int, timeoutMs: Int = 8000): Pair<RawWebSocket, String>? {
        val path = workerPath(dst, dc)
        for (domain in availableDomains(domains)) {
            try {
                return RawWebSocket.connect(domain, domain, timeoutMs, path, secure = !cfg().disableSecure) to domain
            } catch (e: Exception) {
                Log.w("CF worker $domain failed: $e")
            }
        }
        return null
    }

    fun availableDomains(domains: List<String>): List<String> = domains.distinct().shuffled()

    fun warmup() {
        closed = false
        val c = cfg()
        if (c.cfProxyWorkerDomains.isEmpty()) return
        val targets = Proto.DC_DEFAULT_IPS.filterKeys { it !in c.dcRedirects }
        if (targets.isEmpty()) return
        for ((dc, ip) in targets) scheduleRefill(dc, ip, c.cfProxyWorkerDomains)
        Log.i("CF worker pool warmup started for ${targets.size} DC(s)")
    }

    fun reset() {
        closed = true
        idle.values.forEach { b -> synchronized(b) { b.forEach { runCatching { it.ws.close() } }; b.clear() } }
        idle.clear()
        refilling.clear()
    }

    companion object {
        const val MAX_AGE = 100.0
        const val PER_DC_LIMIT = 1

        fun workerPath(dst: String, dc: Int) =
            "/apiws?dst=${URLEncoder.encode(dst, "UTF-8")}&dc=$dc"
    }
}
