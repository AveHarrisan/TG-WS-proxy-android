package com.aveharrisan.tgwsproxy.core

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Список доменов Cloudflare-прокси. Хранится в закодированном виде (как в исходном проекте),
 * раз в час обновляется из репозитория Flowseal/tg-ws-proxy.
 */
object CfDomains {
    const val DOMAINS_URL_HOST = "raw.githubusercontent.com"
    const val DOMAINS_URL_PATH = "/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"
    private const val MIN_VALID = 3

    private val ENCODED = listOf(
        "virkgj.com", "vmmzovy.com", "mkuosckvso.com", "zaewayzmplad.com", "twdmbzcm.com",
        "awzwsldi.com", "clngqrflngqin.com", "tjacxbqtj.com", "bxaxtxmrw.com", "dmohrsgmohcrwb.com",
        "vwbmtmoi.com", "khgrre.com", "ulihssf.com", "tmhqsdqmfpmk.com", "xwuwoqbm.com",
        "orgcnunpj.com", "zhkuldz.com", "zypoljnslxa.com", "efabnxaowuzs.com", "zaftuzsftqdq.com",
    )
    private val SUFFIX = String(intArrayOf(46, 99, 111, 46, 117, 107).map { it.toChar() }.toCharArray())

    val DEFAULTS: List<String> = ENCODED.map(::decode)

    fun decode(s: String): String {
        if (!s.endsWith(".com")) return s
        val p = s.dropLast(4)
        val n = p.count { it.isLetter() }
        return p.map { c ->
            if (!c.isLetter()) c else {
                val base = if (c > '`') 97 else 65
                (Math.floorMod(c.code - base - n, 26) + base).toChar()
            }
        }.joinToString("") + SUFFIX
    }

    fun fetch(): List<String> = try {
        val text = Http.get(DOMAINS_URL_HOST, "$DOMAINS_URL_PATH?${Rnd.bytes(4).toHex()}", pinnedIp = "185.199.109.133")
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map(::decode).toList()
    } catch (e: Exception) {
        Log.w("Не удалось скачать список CF-доменов: $e")
        emptyList()
    }

    private var refreshTask: ScheduledFuture<*>? = null

    fun startRefresh(exec: ScheduledExecutorService, config: ProxyConfig) {
        stopRefresh()
        if (config.cfProxyUserDomains.isNotEmpty()) {
            Balancer.updateDomains(config.cfProxyUserDomains)
            return
        }
        Balancer.updateDomains(DEFAULTS)
        refreshTask = exec.scheduleWithFixedDelay({
            val pool = Domains.normalizePool(fetch())
            if (pool.size >= MIN_VALID) {
                Balancer.updateDomains(pool)
                Log.i("Список CF-доменов обновлён с GitHub (${pool.size} шт.)")
            } else {
                Log.w("Список CF-доменов не обновлён, остаётся текущий")
            }
        }, 0, 1, TimeUnit.HOURS)
    }

    fun stopRefresh() {
        refreshTask?.cancel(true)
        refreshTask = null
    }
}

object Balancer {
    @Volatile var domains: List<String> = emptyList()
        private set
    private val dcToDomain = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val health = java.util.concurrent.ConcurrentHashMap<String, DomainHealth>()

    /** Состояние домена: после отказа — пауза, растущая при повторах (2, 4, 8… до 30 минут). */
    private class DomainHealth(@Volatile var failures: Int = 0, @Volatile var coolUntil: Long = 0)

    private const val BASE_COOLDOWN_MS = 2 * 60_000L
    private const val MAX_COOLDOWN_MS = 30 * 60_000L

    @Synchronized
    fun updateDomains(list: List<String>) {
        if (list.sorted() == domains.sorted()) return
        domains = list.toList()
        dcToDomain.clear()
        health.keys.retainAll(domains.toSet())
        if (domains.isEmpty()) return
        for (dc in intArrayOf(1, 2, 3, 4, 5, 203)) dcToDomain[dc] = domains.random()
    }

    fun updateDomainForDc(dc: Int, domain: String): Boolean = dcToDomain.put(dc, domain) != domain

    fun reportSuccess(domain: String) { health.remove(domain) }

    fun reportFailure(domain: String, now: Long = System.currentTimeMillis()) {
        val h = health.getOrPut(domain) { DomainHealth() }
        h.failures++
        h.coolUntil = now + minOf(BASE_COOLDOWN_MS shl minOf(h.failures - 1, 4), MAX_COOLDOWN_MS)
    }

    fun isCooling(domain: String, now: Long = System.currentTimeMillis()): Boolean = (health[domain]?.coolUntil ?: 0) > now

    /**
     * Порядок попыток: сначала последний сработавший для этого DC, потом остальные здоровые,
     * в конце — те, что недавно отказывали (вдруг все остальные тоже лежат).
     */
    fun domainsForDc(dc: Int, now: Long = System.currentTimeMillis()): List<String> {
        val current = dcToDomain[dc]
        val (cooling, healthy) = domains.partition { isCooling(it, now) }
        val first = healthy.filter { it == current }
        return first + healthy.filter { it != current }.shuffled() + cooling.sortedBy { health[it]?.coolUntil ?: 0 }
    }

    /** Забыть отказы доменов — после смены сети они уже ничего не значат. */
    fun resetHealth() = health.clear()
}
