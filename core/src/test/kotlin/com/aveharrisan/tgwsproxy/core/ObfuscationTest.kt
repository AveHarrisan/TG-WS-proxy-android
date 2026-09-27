package com.aveharrisan.tgwsproxy.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObfuscationTest {
    private val secret = "00112233445566778899aabbccddeeff".hexToBytes()

    /** Рукопожатие, как его строит клиент Telegram с dd-секретом. */
    private fun clientHandshake(tag: Int, dcIdx: Int): ByteArray {
        val init = Rnd.bytes(64)
        init[0] = 0x11
        Obfuscation.putLeInt(init, 56, tag)
        init[60] = dcIdx.toByte(); init[61] = (dcIdx shr 8).toByte()
        // Первые 56 байт идут открытыми, хвост с тегом и номером DC — зашифрованным.
        val enc = AesCtr(sha256(init.copyOfRange(8, 40), secret), init.copyOfRange(40, 56)).update(init)
        return init.copyOfRange(0, 56) + enc.copyOfRange(56, 64)
    }

    @Test
    fun parsesDcAndMediaFlag() {
        for ((tag, dcIdx) in listOf(Proto.ABRIDGED to 2, Proto.INTERMEDIATE to -4, Proto.PADDED_INTERMEDIATE to 203)) {
            val hs = assertNotNull(Obfuscation.tryHandshake(clientHandshake(tag, dcIdx), secret))
            assertEquals(kotlin.math.abs(dcIdx), hs.dc)
            assertEquals(dcIdx < 0, hs.isMedia)
            assertEquals(tag, hs.protoTag)
        }
    }

    @Test
    fun rejectsWrongSecret() {
        val other = "ffeeddccbbaa99887766554433221100".hexToBytes()
        assertNull(Obfuscation.tryHandshake(clientHandshake(Proto.INTERMEDIATE, 2), other))
    }

    @Test
    fun relayInitCarriesTagAndDc() {
        repeat(50) {
            val init = Obfuscation.generateRelayInit(Proto.INTERMEDIATE, -3)
            assertTrue((init[0].toInt() and 0xFF) != 0xEF)
            val dec = AesCtr(init.copyOfRange(8, 40), init.copyOfRange(40, 56)).update(init)
            assertEquals(Proto.INTERMEDIATE, Obfuscation.leInt(dec, 56))
            assertEquals(-3, ((dec[60].toInt() and 0xFF) or (dec[61].toInt() shl 8)).toShort().toInt())
        }
    }

    @Test
    fun reencryptionRoundTrip() {
        val hsBytes = clientHandshake(Proto.INTERMEDIATE, 2)
        val hs = Obfuscation.tryHandshake(hsBytes, secret)!!
        val relay = Obfuscation.generateRelayInit(hs.protoTag, 2)
        val ctx = Obfuscation.buildCryptoCtx(hs.prekeyAndIv, secret, relay)

        // Клиент шифрует своим ключом, продолжая поток после 64 байт рукопожатия.
        val clientEnc = AesCtr(sha256(hsBytes.copyOfRange(8, 40), secret), hsBytes.copyOfRange(40, 56))
        clientEnc.update(ByteArray(64))
        val payload = "hello telegram".toByteArray()
        val toTg = ctx.tgEnc.update(ctx.cltDec.update(clientEnc.update(payload)))

        // Telegram расшифровывает ключом из relay init.
        val tgDec = AesCtr(relay.copyOfRange(8, 40), relay.copyOfRange(40, 56)).also { it.update(ByteArray(64)) }
        assertContentEquals(payload, tgDec.update(toTg))
    }
}
