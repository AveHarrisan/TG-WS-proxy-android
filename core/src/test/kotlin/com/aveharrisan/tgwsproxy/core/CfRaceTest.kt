package com.aveharrisan.tgwsproxy.core

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CfRaceTest {
    private class Conn(val name: String) : AutoCloseable {
        @Volatile var closed = false
        override fun close() { closed = true }
    }

    @AfterTest fun clean() = Balancer.resetHealth()

    @Test
    fun slowFirstDoesNotBlockFastSecond() {
        val t = System.currentTimeMillis()
        val won = CfRace.connect(listOf("slow", "fast")) { d ->
            if (d == "slow") { Thread.sleep(5000); Conn(d) } else { Thread.sleep(100); Conn(d) }
        }
        assertEquals("fast", won?.first)
        assertTrue(System.currentTimeMillis() - t < 2500, "ждали дольше, чем нужно")
    }

    @Test
    fun failuresAreSkippedQuickly() {
        val tries = AtomicInteger()
        val t = System.currentTimeMillis()
        val won = CfRace.connect(listOf("a503", "b503", "ok")) { d ->
            tries.incrementAndGet()
            if (d.endsWith("503")) { Thread.sleep(150); throw IOException("HTTP 503") }
            Conn(d)
        }
        assertEquals("ok", won?.first)
        assertTrue(System.currentTimeMillis() - t < 1500)
        // Отказавшие домены — на паузе и в конце очереди.
        assertTrue(Balancer.isCooling("a503") && Balancer.isCooling("b503"))
    }

    @Test
    fun loserConnectionsAreClosed() {
        val made = java.util.Collections.synchronizedList(ArrayList<Conn>())
        val won = CfRace.connect(listOf("x", "y", "z")) { d ->
            Thread.sleep(if (d == "x") 1000 else 900); Conn(d).also { made += it }
        }!!
        Thread.sleep(1500)
        assertTrue(made.filter { it !== won.second }.all { it.closed }, "лишние соединения не закрыты")
        assertTrue(!won.second.closed)
    }

    @Test
    fun allFailReturnsNull() {
        assertNull(CfRace.connect(listOf("a", "b")) { throw IOException("down") })
    }

    @Test
    fun coolingDomainsGoLast() {
        Balancer.updateDomains(listOf("d1.example", "d2.example", "d3.example"))
        Balancer.reportFailure("d1.example")
        val order = Balancer.domainsForDc(2)
        assertEquals("d1.example", order.last())
    }
}

class DomainCensorTest {
    @Test
    fun classNamesAreNotCensored() {
        val s = DomainCensor.apply("failed: com.aveharrisan.tgwsproxy.core.WsHandshakeError: HTTP 503 via cakeisalie.co.uk")
        assertTrue(s.contains("com.aveharrisan.tgwsproxy.core.WsHandshakeError"), s)
        assertTrue(!s.contains("cakeisalie.co.uk"), s)
    }
}

class WorkerDomainCensorTest {
    @Test
    fun workerAccountIsHidden() {
        val s = DomainCensor.apply("CF worker myworker-1234.harrisan.workers.dev failed")
        assertTrue(!s.contains("harrisan") && !s.contains("myworker") && !s.contains("harr"), s)
        assertTrue(s.contains("my***********.ha******.wo*****.dev"), s)
        // Telegram не прячем — по нему понятно, что происходит.
        assertEquals("wss://kws2.web.telegram.org", DomainCensor.apply("wss://kws2.web.telegram.org"))
    }
}
