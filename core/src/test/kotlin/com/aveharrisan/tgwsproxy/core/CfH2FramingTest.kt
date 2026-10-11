package com.aveharrisan.tgwsproxy.core

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CfH2FramingTest {
    private val key = ByteArray(32) { it.toByte() }
    private val iv = ByteArray(16) { (it * 3).toByte() }

    /** Кадр, как его шлёт клиент: рамка транспорта, затем шифрование потока. */
    private fun roundTrip(body: ByteArray, tag: Int): ByteArray {
        val wire = AesCtr(key, iv).update(CfH2.encodeReply(body, tag))
        return CfH2.readPacket(ByteArrayInputStream(wire), AesCtr(key, iv), tag)
    }

    private fun encrypted(n: Int) = ByteArray(n) { (it + 1).toByte() }

    private fun unencrypted(payload: Int) = ByteArray(20 + payload).also {
        val n = payload
        it[16] = n.toByte(); it[17] = (n shr 8).toByte()
        for (i in 20 until it.size) it[i] = 7
    }

    @Test fun abridgedShortAndLong() {
        for (n in listOf(24, 400, 0x7F * 4, 70_000)) assertContentEquals(encrypted(n), roundTrip(encrypted(n), Proto.ABRIDGED))
    }

    @Test fun intermediate() {
        assertContentEquals(encrypted(120), roundTrip(encrypted(120), Proto.INTERMEDIATE))
    }

    @Test fun paddedStripsRandomPadding() {
        repeat(20) {
            assertContentEquals(encrypted(24 + 16 * 5), roundTrip(encrypted(24 + 16 * 5), Proto.PADDED_INTERMEDIATE))
            assertContentEquals(unencrypted(40), roundTrip(unencrypted(40), Proto.PADDED_INTERMEDIATE))
        }
    }

    @Test fun rejectsTooShort() {
        val wire = AesCtr(key, iv).update(byteArrayOf(4) + ByteArray(16))
        assertFailsWith<IOException> { CfH2.readPacket(ByteArrayInputStream(wire), AesCtr(key, iv), Proto.ABRIDGED) }
    }

    @Test fun abridgedLongPrefix() {
        val words = 0x7F
        val out = CfH2.encodeReply(ByteArray(words * 4) { 1 }, Proto.ABRIDGED)
        assertEquals(0x7F, out[0].toInt())
        assertEquals(words, out[1].toInt() and 0xFF)
        assertEquals(4 + words * 4, out.size)
    }
}
