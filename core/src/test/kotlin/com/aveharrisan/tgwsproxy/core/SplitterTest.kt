package com.aveharrisan.tgwsproxy.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitterTest {
    private val relay = Obfuscation.generateRelayInit(Proto.INTERMEDIATE, 2)

    private fun encryptor() = AesCtr(relay.copyOfRange(8, 40), relay.copyOfRange(40, 56)).also { it.update(ByteArray(64)) }

    private fun intermediate(vararg sizes: Int): Pair<ByteArray, List<Int>> {
        val out = java.io.ByteArrayOutputStream()
        for (s in sizes) {
            val h = ByteArray(4); Obfuscation.putLeInt(h, 0, s)
            out.write(h); out.write(Rnd.bytes(s))
        }
        return encryptor().update(out.toByteArray()) to sizes.map { it + 4 }
    }

    @Test
    fun splitsIntermediateIntoPackets() {
        val (data, lens) = intermediate(16, 40, 8)
        assertEquals(lens, MsgSplitter(relay, Proto.INTERMEDIATE).split(data).map { it.size })
    }

    @Test
    fun preservesStreamAcrossArbitraryChunking() {
        val (data, lens) = intermediate(100, 4, 2000, 12)
        val s = MsgSplitter(relay, Proto.INTERMEDIATE)
        val parts = ArrayList<ByteArray>()
        var i = 0
        while (i < data.size) {
            val n = minOf(1 + Rnd.secure.nextInt(300), data.size - i)
            parts += s.split(data.copyOfRange(i, i + n)); i += n
        }
        assertEquals(lens, parts.map { it.size })
        assertContentEquals(data, parts.reduce { a, b -> a + b })
    }

    @Test
    fun abridgedShortAndLongHeaders() {
        val plain = java.io.ByteArrayOutputStream()
        plain.write(3); plain.write(ByteArray(12))
        plain.write(byteArrayOf(0x7F, 0x80.toByte(), 0, 0)); plain.write(ByteArray(0x80 * 4))
        val parts = MsgSplitter(relay, Proto.ABRIDGED).split(encryptor().update(plain.toByteArray()))
        assertEquals(listOf(13, 4 + 512), parts.map { it.size })
    }

    @Test
    fun zeroLengthDisablesSplitting() {
        val (data, _) = intermediate(0)
        val s = MsgSplitter(relay, Proto.INTERMEDIATE)
        assertEquals(1, s.split(data).size)
        assertEquals(1, s.split(byteArrayOf(1, 2, 3)).size)
    }

    @Test
    fun flushReturnsTailOnce() {
        val (data, _) = intermediate(50)
        val s = MsgSplitter(relay, Proto.INTERMEDIATE)
        assertTrue(s.split(data.copyOf(20)).isEmpty())
        assertEquals(20, s.flush().single().size)
        assertTrue(s.flush().isEmpty())
    }
}
