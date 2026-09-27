package com.aveharrisan.tgwsproxy.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Потоковый AES-CTR: шифрование и расшифровка — одна и та же операция. */
class AesCtr(key: ByteArray, iv: ByteArray) {
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    fun update(data: ByteArray, off: Int = 0, len: Int = data.size - off): ByteArray {
        if (len == 0) return ByteArray(0)
        return cipher.update(data, off, len) ?: ByteArray(0)
    }

    /** Шифрование на месте, без лишних копий на горячем пути. */
    fun updateInPlace(data: ByteArray, off: Int, len: Int) {
        if (len > 0) cipher.update(data, off, len, data, off)
    }
}

object Rnd {
    val secure = SecureRandom()
    fun bytes(n: Int): ByteArray = ByteArray(n).also { secure.nextBytes(it) }
}

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    parts.forEach { md.update(it) }
    return md.digest()
}

fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    parts.forEach { mac.update(it) }
    return mac.doFinal()
}

class CryptoCtx(
    val cltDec: AesCtr, // расшифровка от клиента
    val cltEnc: AesCtr, // шифрование клиенту
    val tgEnc: AesCtr,  // шифрование в Telegram
    val tgDec: AesCtr,  // расшифровка от Telegram
)

data class ClientHandshake(
    val dc: Int,
    val isMedia: Boolean,
    val protoTag: Int,
    val prekeyAndIv: ByteArray,
)

object Obfuscation {
    private val ZERO_64 = ByteArray(64)

    fun tryHandshake(handshake: ByteArray, secret: ByteArray): ClientHandshake? {
        val prekeyAndIv = handshake.copyOfRange(Proto.SKIP_LEN, Proto.SKIP_LEN + Proto.PREKEY_LEN + Proto.IV_LEN)
        val key = sha256(prekeyAndIv.copyOfRange(0, Proto.PREKEY_LEN), secret)
        val iv = prekeyAndIv.copyOfRange(Proto.PREKEY_LEN, prekeyAndIv.size)
        val dec = AesCtr(key, iv).update(handshake)
        val tag = leInt(dec, Proto.PROTO_TAG_POS)
        if (tag != Proto.ABRIDGED && tag != Proto.INTERMEDIATE && tag != Proto.PADDED_INTERMEDIATE) return null
        val dcIdx = ((dec[Proto.DC_IDX_POS].toInt() and 0xFF) or (dec[Proto.DC_IDX_POS + 1].toInt() shl 8)).toShort().toInt()
        return ClientHandshake(kotlin.math.abs(dcIdx), dcIdx < 0, tag, prekeyAndIv)
    }

    fun generateRelayInit(protoTag: Int, dcIdx: Int): ByteArray {
        var rnd: ByteArray
        while (true) {
            rnd = Rnd.bytes(Proto.HANDSHAKE_LEN)
            if ((rnd[0].toInt() and 0xFF) == 0xEF) continue
            if (leInt(rnd, 0) in Proto.RESERVED_STARTS) continue
            if (leInt(rnd, 4) == 0) continue
            break
        }
        val key = rnd.copyOfRange(Proto.SKIP_LEN, Proto.SKIP_LEN + Proto.PREKEY_LEN)
        val iv = rnd.copyOfRange(Proto.SKIP_LEN + Proto.PREKEY_LEN, Proto.SKIP_LEN + Proto.PREKEY_LEN + Proto.IV_LEN)
        val encrypted = AesCtr(key, iv).update(rnd)
        val tail = ByteArray(8)
        putLeInt(tail, 0, protoTag)
        tail[4] = dcIdx.toByte()
        tail[5] = (dcIdx shr 8).toByte()
        val r2 = Rnd.bytes(2)
        tail[6] = r2[0]; tail[7] = r2[1]
        val result = rnd.copyOf()
        for (i in 0 until 8) {
            val ks = (encrypted[56 + i].toInt() xor rnd[56 + i].toInt())
            result[56 + i] = (tail[i].toInt() xor ks).toByte()
        }
        return result
    }

    fun buildCryptoCtx(prekeyAndIv: ByteArray, secret: ByteArray, relayInit: ByteArray): CryptoCtx {
        val cltDecKey = sha256(prekeyAndIv.copyOfRange(0, Proto.PREKEY_LEN), secret)
        val cltDecIv = prekeyAndIv.copyOfRange(Proto.PREKEY_LEN, prekeyAndIv.size)
        val rev = prekeyAndIv.reversedArray()
        val cltEncKey = sha256(rev.copyOfRange(0, Proto.PREKEY_LEN), secret)
        val cltEncIv = rev.copyOfRange(Proto.PREKEY_LEN, rev.size)

        val cltDec = AesCtr(cltDecKey, cltDecIv)
        val cltEnc = AesCtr(cltEncKey, cltEncIv)
        cltDec.update(ZERO_64)

        val relayKey = relayInit.copyOfRange(Proto.SKIP_LEN, Proto.SKIP_LEN + Proto.PREKEY_LEN)
        val relayIv = relayInit.copyOfRange(Proto.SKIP_LEN + Proto.PREKEY_LEN, Proto.SKIP_LEN + Proto.PREKEY_LEN + Proto.IV_LEN)
        val relayRev = relayInit.copyOfRange(Proto.SKIP_LEN, Proto.SKIP_LEN + Proto.PREKEY_LEN + Proto.IV_LEN).reversedArray()
        val tgEnc = AesCtr(relayKey, relayIv)
        val tgDec = AesCtr(relayRev.copyOfRange(0, Proto.KEY_LEN), relayRev.copyOfRange(Proto.KEY_LEN, relayRev.size))
        tgEnc.update(ZERO_64)
        return CryptoCtx(cltDec, cltEnc, tgEnc, tgDec)
    }

    fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    fun putLeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte()
        b[off + 2] = (v shr 16).toByte(); b[off + 3] = (v shr 24).toByte()
    }
}
