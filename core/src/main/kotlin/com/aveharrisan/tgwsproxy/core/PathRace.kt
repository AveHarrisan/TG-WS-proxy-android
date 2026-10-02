package com.aveharrisan.tgwsproxy.core

import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Два пути к одному датацентру наперегонки: сначала тот, что сработал в прошлый раз, второй — если первый
 * не ответил за [STAGGER_MS] или уже отказал. Побеждает первый ответивший, опоздавшее соединение
 * закрывается само. Раньше пути пробовались строго по очереди: на сети, где то прямой путь, то фронтинг
 * отваливаются раз в пару минут, Telegram ждал таймаута первого пути по 5–12 с («долго тупило» 02.10.2026).
 */
internal object PathRace {
    const val STAGGER_MS = 700L

    class Failure(val error: Exception, val ms: Long)

    class Result<T>(val value: T?, val winner: Int, val failures: Array<Failure?>)

    private class Attempt<T>(val index: Int, val value: T?, val failure: Failure?)

    private val exec = Executors.newCachedThreadPool { r ->
        Thread(r, "path-race").apply {
            isDaemon = true
            setUncaughtExceptionHandler { t, e -> Log.e("Ошибка в потоке ${t.name}", e) }
        }
    }

    /**
     * [isFinal] — отказ, после которого второй путь пробовать незачем (переадресация 302):
     * гонка сразу заканчивается этим отказом.
     */
    fun <T : AutoCloseable> race(
        first: () -> T, second: () -> T,
        staggerMs: Long = STAGGER_MS, isFinal: (Exception) -> Boolean = { false },
    ): Result<T> {
        val results = LinkedBlockingQueue<Attempt<T>>()
        val lock = Any()
        var decided = false
        fun launch(i: Int, f: () -> T) = exec.execute {
            val t0 = System.nanoTime()
            val a = try {
                Attempt(i, f(), null)
            } catch (e: Exception) {
                Attempt<T>(i, null, Failure(e, (System.nanoTime() - t0) / 1_000_000))
            }
            // Исход уже решён — опоздавшее соединение никому не нужно.
            val late = synchronized(lock) { if (!decided) results.put(a); decided }
            if (late) a.value?.let { v -> runCatching { v.close() } }
        }
        fun decide(r: Result<T>): Result<T> {
            synchronized(lock) {
                decided = true
                generateSequence { results.poll() }.forEach { a -> a.value?.let { v -> runCatching { v.close() } } }
            }
            return r
        }

        val failures = arrayOfNulls<Failure>(2)
        launch(0, first)
        var secondStarted = false
        var pending = 1
        while (pending > 0) {
            val a = if (secondStarted) results.take() else results.poll(staggerMs, TimeUnit.MILLISECONDS)
            if (a == null) { launch(1, second); secondStarted = true; pending++; continue }
            pending--
            if (a.value != null) return decide(Result(a.value, a.index, failures))
            failures[a.index] = a.failure
            if (isFinal(a.failure!!.error)) return decide(Result(null, -1, failures))
            if (!secondStarted) { launch(1, second); secondStarted = true; pending++ }
        }
        return decide(Result(null, -1, failures))
    }
}
