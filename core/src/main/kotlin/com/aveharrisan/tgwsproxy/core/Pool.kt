package com.aveharrisan.tgwsproxy.core

import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
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
        if (!refilling.add(key)) return
        io.execute { refill(key, targetIp, domains) }
    }

    private fun refill(key: PoolKey, targetIp: String, domains: List<String>) {
        try {
            val b = bucket(key)
            val needed = cfg().poolSize - synchronized(b) { b.size }
            if (needed <= 0) return
            val futures = (0 until needed).map { io.submit<RawWebSocket?> { connectOne(targetIp, domains) } }
            var connected = 0
            for (f in futures) {
                val ws = runCatching { f.get() }.getOrNull() ?: continue
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
        if (rotating.containsKey(key)) return
        rotating[key] = sched.scheduleWithFixedDelay({ rotate(key, targetIp, domains) },
            CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun rotate(key: PoolKey, targetIp: String, domains: List<String>) {
        val b = idle[key] ?: return
        val t = now()
        // Экспериментально: Telegram давно не открывал новых соединений — пул засыпает,
        // чтобы не будить сеть пересозданием соединений. Первый же get() разбудит его.
        if (idleGate.shouldSleep(t, idleSleepSec)) {
            rotating.remove(key)?.cancel(false)
            val closed = synchronized(b) { ArrayList(b).also { b.clear() } }
            closed.forEach { quietClose(it.ws) }
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
            if (tryFrontingFirst) connectFronted(targetIp, domain)?.let { return it }
            try {
                val ws = RawWebSocket.connect(targetIp, domain, timeoutMs = 8000)
                tryFrontingFirst = false
                return ws
            } catch (e: WsHandshakeError) {
                if (e.isRedirect) continue
                return null
            } catch (e: Exception) {
                if (RawWebSocket.isTimeout(e) || (e is java.net.SocketException && e !is java.net.ConnectException)) {
                    if (tryFrontingFirst) return null
                    return connectFronted(targetIp, domain)
                }
                return null
            }
        }
        return null
    }

    private fun connectFronted(targetIp: String, domain: String): RawWebSocket? {
        val ws = try {
            RawWebSocket.connect(targetIp, domain, timeoutMs = 7000, sni = FRONTING_SNI)
        } catch (e: Exception) {
            return null
        }
        Stats.connectionsFronting.incrementAndGet()
        tryFrontingFirst = true
        return ws
    }

    fun warmup() {
        val c = cfg()
        for ((dc, ip) in c.dcRedirects) for (isMedia in listOf(false, true))
            scheduleRefill(PoolKey(dc, isMedia), ip, Proto.wsDomains(dc, isMedia))
        Log.i("WS pool warmup started for ${c.dcRedirects.size} DC(s)")
    }

    fun reset() {
        rotating.values.forEach { it.cancel(false) }
        rotating.clear()
        idle.values.forEach { b -> synchronized(b) { b.forEach { quietClose(it.ws) }; b.clear() } }
        idle.clear()
        refilling.clear()
        refillFailures.clear()
        refillAfter.clear()
        tryFrontingFirst = false
    }

    private fun quietClose(ws: RawWebSocket) { io.execute { runCatching { ws.close() } } }

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

    fun get(dc: Int, fallbackDst: String, workerDomains: List<String>): Pair<RawWebSocket, String>? {
        val b = idle.getOrPut(dc) { ArrayDeque() }
        while (true) {
            val e = synchronized(b) { b.pollFirst() } ?: break
            if (now() - e.created > MAX_AGE || !e.ws.isAlive) { io.execute { runCatching { e.ws.close() } }; continue }
            Stats.cfPoolHits.incrementAndGet()
            scheduleRefill(dc, fallbackDst, workerDomains)
            return e.ws to e.domain
        }
        Stats.cfPoolMisses.incrementAndGet()
        return null
    }

    private fun scheduleRefill(dc: Int, dst: String, domains: List<String>) {
        if (!refilling.add(dc)) return
        io.execute {
            try {
                val b = idle.getOrPut(dc) { ArrayDeque() }
                val needed = minOf(cfg().poolSize, PER_DC_LIMIT) - synchronized(b) { b.size }
                repeat(maxOf(needed, 0)) {
                    val (ws, domain) = connectOne(domains, dst, dc) ?: return@execute
                    synchronized(b) { b.addLast(Entry(ws, now(), domain)) }
                }
            } finally {
                refilling.remove(dc)
            }
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
        val c = cfg()
        if (c.cfProxyWorkerDomains.isEmpty()) return
        val targets = Proto.DC_DEFAULT_IPS.filterKeys { it !in c.dcRedirects }
        if (targets.isEmpty()) return
        for ((dc, ip) in targets) scheduleRefill(dc, ip, c.cfProxyWorkerDomains)
        Log.i("CF worker pool warmup started for ${targets.size} DC(s)")
    }

    fun reset() {
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
