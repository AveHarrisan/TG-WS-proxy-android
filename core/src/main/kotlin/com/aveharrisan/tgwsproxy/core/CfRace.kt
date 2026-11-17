package com.aveharrisan.tgwsproxy.core

import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Подключение к общим доменам CF-прокси «наперегонки»: первый домен сразу, следующий — если первый
 * не ответил за [STAGGER_MS], и так до [PARALLEL] одновременно. Побеждает первый ответивший, остальные
 * закрываются. Раньше домены пробовались строго по очереди, и каждый отказ (503 у перегруженных
 * доменов) задерживал соединение Telegram на секунды — Telegram «тупил» при входе в канал.
 */
object CfRace {
    const val STAGGER_MS = 800L
    const val PARALLEL = 3
    private const val TOTAL_BUDGET_MS = 15_000L

    private val exec = Executors.newCachedThreadPool { r ->
        Thread(r, "cf-race").apply {
            isDaemon = true
            setUncaughtExceptionHandler { t, e -> Log.e("Ошибка в потоке ${t.name}", e) }
        }
    }

    fun <T : AutoCloseable> connect(candidates: List<String>, budgetMs: Long = TOTAL_BUDGET_MS, attempt: (String) -> T): Pair<String, T>? {
        if (candidates.isEmpty()) return null
        val cs = ExecutorCompletionService<Pair<String, T>>(exec)
        val running = ArrayList<Future<Pair<String, T>>>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        val gate = Any()
        var finished = false
        val produced = ArrayList<Pair<String, T>>()
        var next = 0
        var inFlight = 0
        fun launch() {
            val d = candidates[next++]
            running += cs.submit {
                try {
                    val r = attempt(d)
                    Balancer.reportSuccess(d)
                    synchronized(gate) {
                        if (finished) {
                            runCatching { r.close() }
                            throw java.util.concurrent.CancellationException("Гонка завершена")
                        }
                        (d to r).also { produced += it }
                    }
                } catch (e: Exception) {
                    if (e is InterruptedException || e is java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted) throw e
                    Balancer.reportFailure(d)
                    Log.d("CF-прокси ${DomainCensor.apply(d)} не ответил: ${e.javaClass.simpleName}: ${e.message}")
                    throw e
                }
            }
            inFlight++
        }
        launch()
        var winner: Pair<String, T>? = null
        try {
            while (winner == null && inFlight > 0) {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) break
                // Ждём ответа; не дождались за шаг — запускаем следующий домен параллельно.
                val wait = if (next < candidates.size && inFlight < PARALLEL) minOf(STAGGER_MS, left) else left
                val f = cs.poll(wait, TimeUnit.MILLISECONDS)
                if (f == null) {
                    if (next < candidates.size && inFlight < PARALLEL) launch()
                    continue
                }
                inFlight--
                winner = runCatching { f.get() }.getOrNull()
                if (winner == null && next < candidates.size) launch()
            }
        } finally {
            // Попытка может игнорировать interrupt внутри TLS. Она сама закроет поздний результат.
            val losers = synchronized(gate) {
                finished = true
                produced.filter { it !== winner }.toList()
            }
            running.forEach { if (!it.isDone) it.cancel(true) }
            losers.forEach { runCatching { it.second.close() } }
        }
        return winner
    }
}
