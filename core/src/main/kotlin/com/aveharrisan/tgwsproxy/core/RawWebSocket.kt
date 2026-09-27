package com.aveharrisan.tgwsproxy.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException

class WsHandshakeError(val statusCode: Int, val statusLine: String, val location: String? = null) :
    IOException("HTTP $statusCode: $statusLine") {
    val isRedirect: Boolean get() = statusCode in setOf(301, 302, 303, 307, 308)
}

/** Клиентский WebSocket поверх сырого сокета: бинарные кадры, маскирование, пинг-понг. */
class RawWebSocket private constructor(private val socket: Socket, private val input: InputStream) {
    private val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
    private val writeLock = Any()
    private val frag = java.io.ByteArrayOutputStream()
    @Volatile var closed = false
        private set

    val isAlive: Boolean get() = !closed && !socket.isClosed && !socket.isInputShutdown

    fun send(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        if (closed) throw IOException("WebSocket закрыт")
        synchronized(writeLock) {
            output.write(buildFrame(OP_BINARY, data, off, len, mask = true))
            output.flush()
        }
    }

    fun sendBatch(parts: List<ByteArray>) {
        if (closed) throw IOException("WebSocket закрыт")
        synchronized(writeLock) {
            for (p in parts) output.write(buildFrame(OP_BINARY, p, 0, p.size, mask = true))
            output.flush()
        }
    }

    /** Следующее бинарное сообщение или null, если сервер закрыл соединение. */
    fun recv(): ByteArray? {
        while (!closed) {
            val (opcode, payload, fin) = readFrame()
            when (opcode) {
                OP_CLOSE -> {
                    closed = true
                    Log.d("WS OP_CLOSE от сервера: ${parseClose(payload)}")
                    runCatching {
                        synchronized(writeLock) {
                            output.write(buildFrame(OP_CLOSE, payload, 0, minOf(2, payload.size), mask = true))
                            output.flush()
                        }
                    }
                    return null
                }
                OP_PING -> {
                    runCatching {
                        synchronized(writeLock) {
                            output.write(buildFrame(OP_PONG, payload, 0, payload.size, mask = true))
                            output.flush()
                        }
                    }
                }
                OP_PONG -> {}
                OP_CONT, OP_TEXT, OP_BINARY -> {
                    if (fin && frag.size() == 0) return payload
                    frag.write(payload)
                    if (frag.size() > MAX_MESSAGE_LEN) throw IOException("Слишком большое WS-сообщение: ${frag.size()} байт")
                    if (!fin) continue
                    val msg = frag.toByteArray()
                    frag.reset()
                    return msg
                }
            }
        }
        return null
    }

    fun close() {
        if (closed) { runCatching { socket.close() }; return }
        closed = true
        runCatching {
            synchronized(writeLock) {
                output.write(buildFrame(OP_CLOSE, ByteArray(0), 0, 0, mask = true))
                output.flush()
            }
        }
        runCatching { socket.close() }
    }

    private fun readFrame(): Triple<Int, ByteArray, Boolean> {
        val h0 = input.read()
        val h1 = input.read()
        if (h0 < 0 || h1 < 0) throw java.io.EOFException("WS: соединение закрыто")
        val fin = h0 and 0x80 != 0
        val opcode = h0 and 0x0F
        var length = (h1 and 0x7F).toLong()
        if (length == 126L) {
            val b = Net.readExactly(input, 2)
            length = (((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)).toLong()
        } else if (length == 127L) {
            val b = Net.readExactly(input, 8)
            length = 0
            for (x in b) length = (length shl 8) or (x.toLong() and 0xFF)
        }
        if (length > MAX_MESSAGE_LEN || length < 0) throw IOException("Слишком большой WS-кадр: $length байт")
        val maskKey = if (h1 and 0x80 != 0) Net.readExactly(input, 4) else null
        val payload = Net.readExactly(input, length.toInt())
        if (maskKey != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
        return Triple(opcode, payload, fin)
    }

    companion object {
        const val OP_CONT = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
        const val MAX_MESSAGE_LEN = 16 * 1024 * 1024

        private val CLOSE_REASONS = mapOf(
            1000 to "normal", 1001 to "going_away", 1002 to "protocol_error", 1003 to "unsupported_data",
            1006 to "abnormal", 1007 to "bad_data", 1008 to "policy_violation", 1009 to "too_big",
            1010 to "missing_extension", 1011 to "internal_error",
        )

        fun parseClose(p: ByteArray): String {
            if (p.size < 2) return "без кода"
            val code = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
            val text = String(p, 2, p.size - 2, Charsets.UTF_8)
            return "$code ${CLOSE_REASONS[code] ?: ""} $text".trim()
        }

        fun buildFrame(opcode: Int, data: ByteArray, off: Int, len: Int, mask: Boolean): ByteArray {
            val fb = 0x80 or opcode
            val hdrLen = when { len < 126 -> 2; len < 65536 -> 4; else -> 10 } + if (mask) 4 else 0
            val out = ByteArray(hdrLen + len)
            out[0] = fb.toByte()
            val m = if (mask) 0x80 else 0
            var p: Int
            when {
                len < 126 -> { out[1] = (m or len).toByte(); p = 2 }
                len < 65536 -> { out[1] = (m or 126).toByte(); out[2] = (len shr 8).toByte(); out[3] = len.toByte(); p = 4 }
                else -> {
                    out[1] = (m or 127).toByte()
                    for (i in 0 until 8) out[2 + i] = (len.toLong() shr (56 - 8 * i)).toByte()
                    p = 10
                }
            }
            if (!mask) {
                System.arraycopy(data, off, out, p, len)
                return out
            }
            val key = Rnd.bytes(4)
            System.arraycopy(key, 0, out, p, 4)
            p += 4
            for (i in 0 until len) out[p + i] = (data[off + i].toInt() xor key[i and 3].toInt()).toByte()
            return out
        }

        /**
         * Подключение: TCP на [host] (обычно IP датацентра), TLS с SNI [sni] ?: [domain], заголовок Host = [domain].
         * Если [sni] задан отдельно — это фронтинг, имя в сертификате не проверяется.
         */
        fun connect(
            host: String, domain: String, timeoutMs: Int = 10_000, path: String = Proto.WS_PATH,
            sni: String? = null, secure: Boolean = true,
        ): RawWebSocket {
            val fronting = sni != null
            val socket = Net.connect(host, if (secure) 443 else 80, minOf(timeoutMs, 10_000), secure,
                sni = sni ?: domain, verifyHostname = !fronting)
            try {
                socket.soTimeout = timeoutMs
                val key = base64(Rnd.bytes(16))
                val req = "GET $path HTTP/1.1\r\nHost: $domain\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\n\r\n"
                socket.getOutputStream().write(req.toByteArray())
                socket.getOutputStream().flush()
                val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
                val lines = ArrayList<String>()
                while (true) {
                    val line = Net.readLine(input) ?: break
                    if (line.isEmpty()) break
                    lines += line.trim()
                }
                if (lines.isEmpty()) throw WsHandshakeError(0, "empty response")
                val status = lines[0].split(' ', limit = 3).getOrNull(1)?.toIntOrNull() ?: 0
                if (status == 101) {
                    socket.soTimeout = 0
                    return RawWebSocket(socket, input)
                }
                val location = lines.drop(1).firstOrNull { it.lowercase().startsWith("location:") }?.substringAfter(':')?.trim()
                throw WsHandshakeError(status, lines[0], location)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }

        private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        /** java.util.Base64 на Android есть только с API 26, поэтому свой кодировщик. */
        fun base64(b: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i < b.size) {
                val n = minOf(3, b.size - i)
                var v = 0
                for (j in 0 until 3) v = (v shl 8) or (if (j < n) b[i + j].toInt() and 0xFF else 0)
                for (j in 0 until 4) sb.append(if (j <= n) B64[(v shr (18 - 6 * j)) and 63] else '=')
                i += 3
            }
            return sb.toString()
        }

        fun isTimeout(e: Throwable): Boolean = e is SocketTimeoutException ||
            (e.message?.contains("timed out", true) == true)
    }
}
