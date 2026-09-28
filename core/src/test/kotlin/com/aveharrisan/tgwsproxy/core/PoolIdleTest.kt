package com.aveharrisan.tgwsproxy.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoolIdleTest {
    @Test
    fun offByDefault() {
        val g = PoolIdle()
        g.markDemand(0.0)
        assertFalse(g.shouldSleep(1_000_000.0, 0.0))
    }

    @Test
    fun sleepsAfterIdleAndWakesOnDemand() {
        val g = PoolIdle()
        g.markDemand(100.0)
        assertFalse(g.shouldSleep(399.0, 300.0))
        assertTrue(g.shouldSleep(400.0, 300.0))
        assertTrue(g.fallAsleep())
        assertFalse(g.fallAsleep(), "второй раз в журнал не пишем")
        assertTrue(g.markDemand(500.0), "спал — просыпается")
        assertFalse(g.shouldSleep(600.0, 300.0))
        assertFalse(g.markDemand(610.0), "не спал — не просыпается")
    }
}
