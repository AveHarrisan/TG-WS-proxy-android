package com.aveharrisan.tgwsproxy.core

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertNull

/** Прокси остановлен, исполнители выключены — пул не должен ронять приложение (вылет 28.09.2026). */
class PoolShutdownTest {
    @Test
    fun poolSurvivesStoppedExecutors() {
        val io = Executors.newFixedThreadPool(2)
        val sched = Executors.newScheduledThreadPool(1)
        val pool = WsPool({ ProxyConfig(secret = "0123456789abcdef0123456789abcdef") }, io, sched)
        sched.shutdownNow()
        io.shutdownNow()
        // Раньше здесь летел RejectedExecutionException из scheduleRefill.
        assertNull(pool.get(2, false, "127.0.0.1", listOf("kws2.web.telegram.org")))
        pool.warmup()
        pool.reset()
        assertNull(pool.get(4, true, "127.0.0.1", listOf("kws4-1.web.telegram.org")))
    }

    @Test
    fun cfWorkerPoolSurvivesStoppedExecutor() {
        val io = Executors.newFixedThreadPool(1)
        val cfg = ProxyConfig(secret = "0123456789abcdef0123456789abcdef", cfProxyWorkerDomains = listOf("w.example.invalid"))
        val pool = CfWorkerPool({ cfg }, io)
        io.shutdownNow()
        pool.warmup()
        pool.reset()
    }
}
