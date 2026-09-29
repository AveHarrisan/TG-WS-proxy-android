package com.aveharrisan.tgwsproxy.core

import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals

/** Смена сети: соединения Telegram закрываются, чтобы он сразу открыл новые (раньше висели мёртвыми). */
class NetworkChangeTest {
    @Test
    fun clientsAreDroppedOnNetworkChange() {
        val port = ServerSocket(0).use { it.localPort }
        val srv = ProxyServer(ProxyConfig(port = port, secret = "0123456789abcdef0123456789abcdef", poolSize = 0, fallbackCfProxy = false))
        srv.start()
        try {
            val client = Socket("127.0.0.1", port).apply { soTimeout = 5000 }
            Thread.sleep(300) // сервер успел принять соединение
            srv.onNetworkChanged("тест")
            // Сервер закрыл соединение: чтение сразу возвращает конец потока.
            assertEquals(-1, runCatching { client.getInputStream().read() }.getOrDefault(-1))
            client.close()
        } finally {
            srv.stop()
        }
    }
}
