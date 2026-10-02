package com.aveharrisan.tgwsproxy.core

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Прямой путь и фронтинг наперегонки: Telegram не ждёт таймаута отвалившегося пути (02.10.2026). */
class PathRaceTest {
    private class Conn(val name: String) : AutoCloseable {
        @Volatile var closed = false
        override fun close() { closed = true }
    }

    private fun ms(t: Long) = System.currentTimeMillis() - t

    @Test
    fun silentFirstDoesNotHoldConnection() {
        val t = System.currentTimeMillis()
        val r = PathRace.race(
            { Thread.sleep(5000); throw SocketTimeoutException("timed out") },
            { Thread.sleep(100); Conn("front") })
        assertEquals("front", r.value?.name)
        assertEquals(1, r.winner)
        assertTrue(ms(t) < 1500, "ждали таймаута первого пути: ${ms(t)} мс")
    }

    @Test
    fun quickFailureStartsSecondAtOnce() {
        val t = System.currentTimeMillis()
        val r = PathRace.race({ Thread.sleep(50); throw IOException("reset") }, { Conn("front") })
        assertEquals("front", r.value?.name)
        assertTrue(ms(t) < PathRace.STAGGER_MS, "второй путь ждал шага: ${ms(t)} мс")
        assertEquals("reset", r.failures[0]?.error?.message)
    }

    @Test
    fun fastFirstWinsAlone() {
        var secondCalled = false
        val r = PathRace.race({ Conn("direct") }, { secondCalled = true; Conn("front") })
        assertEquals(0, r.winner)
        Thread.sleep(PathRace.STAGGER_MS + 200)
        assertTrue(!secondCalled, "второй путь не нужен, если первый ответил сразу")
    }

    @Test
    fun lateLoserIsClosed() {
        val loser = Conn("direct")
        val r = PathRace.race({ Thread.sleep(1200); loser }, { Thread.sleep(50); Conn("front") }, staggerMs = 100)
        assertEquals("front", r.value?.name)
        Thread.sleep(1500)
        assertTrue(loser.closed, "опоздавшее соединение осталось открытым")
    }

    @Test
    fun bothFailReportsBoth() {
        val r = PathRace.race<Conn>({ throw IOException("a") }, { throw IOException("b") })
        assertNull(r.value)
        assertEquals("a", r.failures[0]?.error?.message)
        assertEquals("b", r.failures[1]?.error?.message)
    }

    @Test
    fun finalFailureSkipsSecond() {
        var secondCalled = false
        val r = PathRace.race<Conn>({ throw IOException("302") }, { secondCalled = true; Conn("front") },
            isFinal = { it.message == "302" })
        assertNull(r.value)
        Thread.sleep(200)
        assertTrue(!secondCalled)
    }
}
