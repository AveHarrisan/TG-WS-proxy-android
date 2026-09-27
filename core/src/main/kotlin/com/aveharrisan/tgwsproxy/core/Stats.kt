package com.aveharrisan.tgwsproxy.core

import java.util.concurrent.atomic.AtomicLong

object Stats {
    val connectionsTotal = AtomicLong()
    val connectionsActive = AtomicLong()
    val connectionsWs = AtomicLong()
    val connectionsTcpFallback = AtomicLong()
    val connectionsCfProxy = AtomicLong()
    val connectionsFronting = AtomicLong()
    val connectionsBad = AtomicLong()
    val connectionsMasked = AtomicLong()
    val wsErrors = AtomicLong()
    val bytesUp = AtomicLong()
    val bytesDown = AtomicLong()
    val poolHits = AtomicLong()
    val poolMisses = AtomicLong()
    val cfPoolHits = AtomicLong()
    val cfPoolMisses = AtomicLong()

    private val all = listOf(
        connectionsTotal, connectionsActive, connectionsWs, connectionsTcpFallback, connectionsCfProxy,
        connectionsFronting, connectionsBad, connectionsMasked, wsErrors, bytesUp, bytesDown,
        poolHits, poolMisses, cfPoolHits, cfPoolMisses,
    )

    fun reset() = all.forEach { it.set(0) }

    fun summary(): String {
        val pt = poolHits.get() + poolMisses.get()
        val cpt = cfPoolHits.get() + cfPoolMisses.get()
        return "total=${connectionsTotal.get()} active=${connectionsActive.get()} ws=${connectionsWs.get()} " +
            "tcp_fb=${connectionsTcpFallback.get()} cf=${connectionsCfProxy.get()} front=${connectionsFronting.get()} " +
            "bad=${connectionsBad.get()} masked=${connectionsMasked.get()} err=${wsErrors.get()} " +
            "pool=${if (pt > 0) "${poolHits.get()}/$pt" else "n/a"} " +
            "cf_pool=${if (cpt > 0) "${cfPoolHits.get()}/$cpt" else "n/a"} " +
            "up=${humanBytes(bytesUp.get())} down=${humanBytes(bytesDown.get())}"
    }
}
