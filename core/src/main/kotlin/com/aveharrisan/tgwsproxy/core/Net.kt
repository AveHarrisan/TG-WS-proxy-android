package com.aveharrisan.tgwsproxy.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SNIHostName

object Net {
    @Volatile var bufferSize: Int = 256 * 1024

    /** Дополнительная проверка имени сертификата (на Android — системный верификатор). */
    @Volatile var hostnameVerifier: ((String, javax.net.ssl.SSLSession) -> Boolean)? = null

    fun tune(s: Socket) {
        runCatching { s.tcpNoDelay = true }
        runCatching { s.receiveBufferSize = bufferSize; s.sendBufferSize = bufferSize }
        runCatching { s.keepAlive = true }
    }

    /**
     * Через сколько секунд простоя слать TCP keepalive; 0 — системное значение (около 2 часов, как в оригинале).
     * Дальше, пока тишина, пакет уходит раз в [KEEP_INTERVAL_SEC]; три без ответа — система закрывает соединение.
     * Часть сетей обрывает соединение, по которому ~90 с ничего не шло. Keepalive — пустые служебные пакеты TCP:
     * на них отвечает ядро системы сервера, Telegram и прокси их не видят, поэтому поток MTProto не меняется.
     */
    @Volatile var keepIdleSec: Int = 0
    const val KEEP_IDLE_SEC = 60
    const val KEEP_INTERVAL_SEC = 30
    const val KEEP_COUNT = 3

    /** Как выставить интервалы keepalive на этой платформе. На Android подменяется в приложении. */
    @Volatile var keepAliveTuner: (Socket, Int, Int, Int) -> Unit = ::jvmKeepAlive

    /** Вызывать после connect: на Android до него у сокета ещё нет дескриптора. */
    fun keepAlive(s: Socket) {
        val idle = keepIdleSec
        if (idle <= 0) return
        runCatching { keepAliveTuner(s, idle, KEEP_INTERVAL_SEC, KEEP_COUNT) }
            .onFailure { Log.d("TCP keepalive не выставлен: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** jdk.net.ExtendedSocketOptions есть в JDK на Linux/macOS, на Android его нет — поэтому через отражение. */
    @Suppress("UNCHECKED_CAST")
    private fun jvmKeepAlive(s: Socket, idle: Int, interval: Int, count: Int) {
        val c = Class.forName("jdk.net.ExtendedSocketOptions")
        fun opt(name: String) = c.getField(name).get(null) as java.net.SocketOption<Int>
        s.setOption(opt("TCP_KEEPIDLE"), idle)
        s.setOption(opt("TCP_KEEPINTERVAL"), interval)
        s.setOption(opt("TCP_KEEPCOUNT"), count)
    }

    /**
     * TCP (+TLS) соединение на конкретный адрес с заданным SNI.
     * [verifyHostname]=false — для доменного фронтинга: цепочка сертификатов проверяется,
     * а совпадение имени нет (как check_hostname=False в исходном проекте).
     */
    fun connect(host: String, port: Int, timeoutMs: Int, tls: Boolean, sni: String? = null, verifyHostname: Boolean = true): Socket {
        val raw = Socket()
        try {
            tune(raw)
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            keepAlive(raw)
            if (!tls) return raw
            val serverName = sni ?: host
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, serverName, port, true) as SSLSocket
            val p = ssl.sslParameters
            runCatching { p.serverNames = listOf(SNIHostName(serverName)) }
            // Имя в сертификате проверяет сам TLS-стек; при фронтинге проверяем только цепочку.
            if (verifyHostname) p.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = p
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            val extra = hostnameVerifier
            if (verifyHostname && extra != null && !extra(serverName, ssl.session)) {
                ssl.close()
                throw IOException("Сертификат не подходит к $serverName")
            }
            return ssl
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    fun readLine(input: InputStream, limit: Int = 8192): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
            buf.write(b)
            if (buf.size() > limit) throw IOException("Слишком длинная строка заголовка")
        }
    }

    fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) throw java.io.EOFException("Соединение закрыто ($got из $n байт)")
            got += r
        }
        return out
    }
}

/** Минимальный HTTPS GET с возможностью подставить IP (обход подменённого DNS для GitHub). */
object Http {
    fun get(host: String, path: String, pinnedIp: String? = null, timeoutMs: Int = 10_000): String {
        val sock = try {
            Net.connect(pinnedIp ?: host, 443, timeoutMs, tls = true, sni = host)
        } catch (e: Exception) {
            if (pinnedIp == null) throw e
            Net.connect(host, 443, timeoutMs, tls = true, sni = host)
        }
        sock.use { s ->
            s.soTimeout = timeoutMs
            val req = "GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: tg-ws-proxy-android\r\nConnection: close\r\n\r\n"
            s.getOutputStream().write(req.toByteArray())
            s.getOutputStream().flush()
            val input = s.getInputStream().buffered()
            val status = Net.readLine(input) ?: throw IOException("Пустой ответ")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            var chunked = false
            var length = -1
            while (true) {
                val line = Net.readLine(input) ?: break
                if (line.isEmpty()) break
                val (k, v) = line.split(':', limit = 2).let { it[0].trim().lowercase() to it.getOrElse(1) { "" }.trim() }
                if (k == "transfer-encoding" && v.contains("chunked", true)) chunked = true
                if (k == "content-length") length = v.toIntOrNull() ?: -1
            }
            if (code != 200) throw IOException("HTTP $code")
            val body = when {
                chunked -> {
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val size = (Net.readLine(input) ?: break).substringBefore(';').trim().toInt(16)
                        if (size == 0) break
                        out.write(Net.readExactly(input, size))
                        Net.readLine(input)
                    }
                    out.toByteArray()
                }
                length >= 0 -> Net.readExactly(input, length)
                else -> input.readBytes()
            }
            return String(body, Charsets.UTF_8)
        }
    }
}
