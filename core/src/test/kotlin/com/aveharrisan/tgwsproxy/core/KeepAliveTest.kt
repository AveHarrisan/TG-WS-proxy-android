package com.aveharrisan.tgwsproxy.core

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KeepAliveTest {
    @Suppress("UNCHECKED_CAST")
    private fun opt(name: String) =
        Class.forName("jdk.net.ExtendedSocketOptions").getField(name).get(null) as SocketOption<Int>

    @AfterTest
    fun reset() { Net.keepIdleSec = 0 }

    @Test
    fun onlyWhenEnabled() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { srv ->
            Net.keepIdleSec = 0
            val def = Net.connect("127.0.0.1", srv.localPort, 2000, tls = false).use { it.getOption(opt("TCP_KEEPIDLE")) }
            Net.keepIdleSec = Net.KEEP_IDLE_SEC
            Net.connect("127.0.0.1", srv.localPort, 2000, tls = false).use { s ->
                assertEquals(Net.KEEP_IDLE_SEC, s.getOption(opt("TCP_KEEPIDLE")))
                assertEquals(Net.KEEP_INTERVAL_SEC, s.getOption(opt("TCP_KEEPINTERVAL")))
                assertEquals(Net.KEEP_COUNT, s.getOption(opt("TCP_KEEPCOUNT")))
            }
            assert(def != Net.KEEP_IDLE_SEC) { "без режима системное значение, а не $def" }
        }
    }
}
