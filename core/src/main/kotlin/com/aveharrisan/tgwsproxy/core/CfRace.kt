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

    fun <T : AutoCloseable> connect(candidates: List<String>, attempt: (String) -> T): Pair<String, T>? {
        if (candidates.isEmpty()) return null
        val cs = ExecutorCompletionService<Pair<String, T>>(exec)
        val running = ArrayList<Future<Pair<String, T>>>()
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS
        var next = 0
        var inFlight = 0
        fun launch() {
            val d = candidates[next++]
            running += cs.submit {
                try {
                    val r = attempt(d)
                    Balancer.reportSuccess(d)
                    d to r
                } catch (e: Exception) {
                    Balancer.reportFailure(d)
                    Log.d("CF-прокси ${DomainCensor.apply(d)} не ответил: ${e.javaClass.simpleName}: ${e.message}")
                    throw e
                }
            }
            inFlight++
        }
        launch()
        var winner: Pair<String, T>? = null
        while (winner == null && inFlight > 0) {
            val left = deadline - System.currentTimeMillis()
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
        // Остальные попытки не нужны: доделавшие — закрываем, недоделавшие — отменяем.
        running.forEach { fut ->
            if (winner != null && fut.isDone && runCatching { fut.get() }.getOrNull() === winner) return@forEach
            if (fut.isDone) runCatching { fut.get().second.close() } else {
                fut.cancel(true)
                exec.execute { runCatching { fut.get(10, TimeUnit.SECONDS).second.close() } }
            }
        }
        return winner
    }
}
