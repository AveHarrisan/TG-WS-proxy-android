package com.aveharrisan.tgwsproxy.core

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Режим ee-секрета: клиент прикидывается TLS 1.3, мы отвечаем поддельным ServerHello. */
object FakeTls {
    const val RECORD_HANDSHAKE = 0x16
    const val RECORD_CCS = 0x14
    const val RECORD_APPDATA = 0x17
    const val CLIENT_RANDOM_OFFSET = 11
    const val SESSION_ID_OFFSET = 44
    const val TIMESTAMP_TOLERANCE = 120
    const val APPDATA_MAX = 16384

    private val CCS_FRAME = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)
    private const val SH_RANDOM_OFF = 11
    private const val SH_SESSID_OFF = 44
    private const val SH_PUBKEY_OFF = 89

    private val SERVER_HELLO_TEMPLATE: ByteArray = run {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x7a, 0x02, 0x00, 0x00, 0x76, 0x03, 0x03))
        out.write(ByteArray(32))
        out.write(0x20)
        out.write(ByteArray(32))
        out.write(byteArrayOf(0x13, 0x01, 0x00, 0x00, 0x2e, 0x00, 0x33, 0x00, 0x24, 0x00, 0x1d, 0x00, 0x20))
        out.write(ByteArray(32))
        out.write(byteArrayOf(0x00, 0x2b, 0x00, 0x02, 0x03, 0x04))
        out.toByteArray()
    }

    class Hello(val clientRandom: ByteArray, val sessionId: ByteArray, val timestamp: Long)

    fun verifyClientHello(data: ByteArray, secret: ByteArray, now: Long = System.currentTimeMillis() / 1000): Hello? {
        if (data.size < 43 || (data[0].toInt() and 0xFF) != RECORD_HANDSHAKE || data[5].toInt() != 0x01) return null
        val clientRandom = data.copyOfRange(CLIENT_RANDOM_OFFSET, CLIENT_RANDOM_OFFSET + 32)
        val zeroed = data.copyOf()
        java.util.Arrays.fill(zeroed, CLIENT_RANDOM_OFFSET, CLIENT_RANDOM_OFFSET + 32, 0)
        val expected = hmacSha256(secret, zeroed)
        if (!MessageDigest.isEqual(expected.copyOf(28), clientRandom.copyOf(28))) return null
        val ts = ByteArray(4) { (clientRandom[28 + it].toInt() xor expected[28 + it].toInt()).toByte() }
        val timestamp = Obfuscation.leInt(ts, 0).toLong() and 0xFFFFFFFFL
        if (kotlin.math.abs(now - timestamp) > TIMESTAMP_TOLERANCE) return null
        val sessionId = if (data.size >= SESSION_ID_OFFSET + 32 && data[43].toInt() == 0x20)
            data.copyOfRange(SESSION_ID_OFFSET, SESSION_ID_OFFSET + 32) else ByteArray(32)
        return Hello(clientRandom, sessionId, timestamp)
    }

    fun buildServerHello(secret: ByteArray, clientRandom: ByteArray, sessionId: ByteArray): ByteArray {
        val sh = SERVER_HELLO_TEMPLATE.copyOf()
        System.arraycopy(sessionId, 0, sh, SH_SESSID_OFF, 32)
        System.arraycopy(Rnd.bytes(32), 0, sh, SH_PUBKEY_OFF, 32)
        val size = 1900 + Rnd.secure.nextInt(201)
        val app = ByteArray(5 + size)
        app[0] = 0x17; app[1] = 0x03; app[2] = 0x03
        app[3] = (size shr 8).toByte(); app[4] = size.toByte()
        System.arraycopy(Rnd.bytes(size), 0, app, 5, size)
        val response = sh + CCS_FRAME + app
        val serverRandom = hmacSha256(secret, clientRandom, response)
        System.arraycopy(serverRandom, 0, response, SH_RANDOM_OFF, 32)
        return response
    }

    fun wrapRecords(data: ByteArray, off: Int, len: Int): ByteArray {
        val records = (len + APPDATA_MAX - 1) / APPDATA_MAX
        val out = ByteArray(len + records * 5)
        var src = off
        var dst = 0
        val end = off + len
        while (src < end) {
            val n = minOf(APPDATA_MAX, end - src)
            out[dst] = 0x17; out[dst + 1] = 0x03; out[dst + 2] = 0x03
            out[dst + 3] = (n shr 8).toByte(); out[dst + 4] = n.toByte()
            System.arraycopy(data, src, out, dst + 5, n)
            src += n; dst += 5 + n
        }
        return out
    }
}

/** Поток клиента внутри фейкового TLS: снимает/надевает заголовки application data. */
class FakeTlsStreams(private val rawIn: InputStream, private val rawOut: OutputStream) {
    private var recordLeft = 0

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (recordLeft == 0) {
                val t = rawIn.read()
                if (t < 0) return -1
                val hdr = try { Net.readExactly(rawIn, 4) } catch (e: EOFException) { return -1 }
                val recLen = ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
                when (t) {
                    FakeTls.RECORD_CCS -> { if (recLen > 0) Net.readExactly(rawIn, recLen) }
                    FakeTls.RECORD_APPDATA -> recordLeft = recLen
                    else -> return -1
                }
            }
            val n = rawIn.read(b, off, minOf(len, recordLeft))
            if (n < 0) return -1
            recordLeft -= n
            return n
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > 0) rawOut.write(FakeTls.wrapRecords(b, off, len))
        }
        override fun flush() = rawOut.flush()
        override fun close() = rawOut.close()
    }
}
