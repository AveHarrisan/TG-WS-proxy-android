package com.aveharrisan.tgwsproxy.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class H2SetupTest {
    @Test
    fun stalledDomainDoesNotHideWorkingMediaEndpointAndLaneIsReused() {
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        val pool = CfH2Pool { _, _ ->
            if (calls.incrementAndGet() == 1) release.await(5, TimeUnit.SECONDS)
        }
        Balancer.updateDomains(listOf("one.example.invalid", "two.example.invalid"))
        Balancer.resetHealth()
        try {
            val started = System.nanoTime()
            val first = assertNotNull(pool.open(1, "test"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500,
                "медиа ждёт зависший первый домен")
            val second = assertNotNull(pool.open(1, "test"))
            assertTrue(first.lane === second.lane, "готовая HTTP/2-полоса не переиспользована")
            first.close()
            second.close()
        } finally {
            release.countDown()
            pool.close()
            Balancer.updateDomains(CfDomains.DEFAULTS)
            Balancer.resetHealth()
        }
    }
}
