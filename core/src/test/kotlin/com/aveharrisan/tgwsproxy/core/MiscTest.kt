package com.aveharrisan.tgwsproxy.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiscTest {
    private val secret = "00112233445566778899aabbccddeeff".hexToBytes()

    private fun clientHello(ts: Long = System.currentTimeMillis() / 1000, sid: ByteArray = Rnd.bytes(32)): ByteArray {
        val body = ByteArray(517)
        body[0] = 0x16; body[1] = 3; body[2] = 1
        body[3] = ((517 - 5) shr 8).toByte(); body[4] = (517 - 5).toByte()
        body[5] = 1; body[43] = 0x20
        System.arraycopy(sid, 0, body, 44, 32)
        val digest = hmacSha256(secret, body)
        val cr = digest.copyOf(32)
        val t = ByteArray(4); Obfuscation.putLeInt(t, 0, ts.toInt())
        for (i in 0 until 4) cr[28 + i] = (digest[28 + i].toInt() xor t[i].toInt()).toByte()
        System.arraycopy(cr, 0, body, 11, 32)
        return body
    }

    @Test
    fun fakeTlsAcceptsValidHello() {
        val sid = Rnd.bytes(32)
        val r = assertNotNull(FakeTls.verifyClientHello(clientHello(sid = sid), secret))
        assertContentEquals(sid, r.sessionId)
    }

    @Test
    fun fakeTlsRejectsBadHello() {
        assertNull(FakeTls.verifyClientHello(clientHello(), "ffeeddccbbaa99887766554433221100".hexToBytes()))
        assertNull(FakeTls.verifyClientHello(clientHello(ts = System.currentTimeMillis() / 1000 - 3600), secret))
        val h = clientHello(); h[300] = (h[300].toInt() xor 0xFF).toByte()
        assertNull(FakeTls.verifyClientHello(h, secret))
    }

    @Test
    fun serverHelloBindsClientRandom() {
        val cr = Rnd.bytes(32)
        val sh = FakeTls.buildServerHello(secret, cr, Rnd.bytes(32))
        val zeroed = sh.copyOf(); java.util.Arrays.fill(zeroed, 11, 43, 0)
        assertContentEquals(hmacSha256(secret, cr, zeroed), sh.copyOfRange(11, 43))
    }

    @Test
    fun fakeTlsStreamRoundTrip() {
        val payload = Rnd.bytes(40000)
        val wire = ByteArrayOutputStream()
        FakeTlsStreams(ByteArrayInputStream(ByteArray(0)), wire).output.write(payload)
        val back = FakeTlsStreams(ByteArrayInputStream(byteArrayOf(0x14, 3, 3, 0, 1, 1) + wire.toByteArray()), ByteArrayOutputStream())
        assertContentEquals(payload, Net.readExactly(back.input, payload.size))
    }

    @Test
    fun wsFrameRoundTripMasked() {
        for (size in listOf(0, 10, 125, 126, 70000)) {
            val data = Rnd.bytes(size)
            val f = RawWebSocket.buildFrame(RawWebSocket.OP_BINARY, data, 0, size, mask = true)
            assertTrue(f[1].toInt() and 0x80 != 0)
            val hdr = when { size < 126 -> 2; size < 65536 -> 4; else -> 10 }
            val key = f.copyOfRange(hdr, hdr + 4)
            assertContentEquals(data, ByteArray(size) { (f[hdr + 4 + it].toInt() xor key[it and 3].toInt()).toByte() })
        }
    }

    @Test
    fun dcIpParsing() {
        assertEquals(mapOf(2 to "149.154.167.220", 4 to "1.2.3.4"), DcIpParser.parse(listOf("2:149.154.167.220", "4:1.2.3.4")))
        assertFailsWith<IllegalArgumentException> { DcIpParser.parse(listOf("2-1.2.3.4")) }
        assertFailsWith<IllegalArgumentException> { DcIpParser.parse(listOf("2:1.2.3")) }
        assertFailsWith<IllegalArgumentException> { DcIpParser.parse(listOf("x:1.2.3.4")) }
    }

    @Test
    fun domainHelpers() {
        assertEquals(listOf("a.com", "b.org"), Domains.coerceList("a.com, b.org;A.COM"))
        assertTrue(Domains.isValid("kws1.example.co.uk"))
        assertFalse(Domains.isValid("-bad.com"))
        assertFalse(Domains.isValid("nodot"))
        val d = CfDomains.DEFAULTS
        assertEquals(d.size, d.toSet().size)
        assertTrue(d.all { Domains.isValid(it) })
    }
}

class Base64Test {
    @Test
    fun matchesJdk() {
        for (n in 0..20) {
            val b = Rnd.bytes(n)
            assertEquals(java.util.Base64.getEncoder().encodeToString(b), RawWebSocket.base64(b))
        }
    }

    @Test
    fun humanBytesRu() {
        assertEquals("512 Б", humanBytesRu(512))
        assertEquals("1,4 МБ", humanBytesRu(1_500_000))
        assertEquals("2,0 КБ", humanBytesRu(2048))
    }
}
