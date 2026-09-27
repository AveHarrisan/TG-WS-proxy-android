package com.aveharrisan.tgwsproxy.core

object Proto {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val KEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    const val ABRIDGED = 0xEFEFEFEF.toInt()
    const val INTERMEDIATE = 0xEEEEEEEE.toInt()
    const val PADDED_INTERMEDIATE = 0xDDDDDDDD.toInt()

    val RESERVED_STARTS = setOf(0x44414548, 0x54534F50, 0x20544547, 0xEEEEEEEE.toInt(), 0xDDDDDDDD.toInt(), 0x02010316)

    val DC_DEFAULT_IPS: Map<Int, String> = linkedMapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100",
    )

    val DC_TEST_IPS: Map<Int, String> = linkedMapOf(
        1 to "149.154.175.10",
        2 to "149.154.167.40",
        3 to "149.154.175.117",
    )

    const val WS_PATH = "/apiws"
    const val WS_PATH_TEST = "/apiws_test"

    fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val d = if (dc == 203) 2 else dc
        return if (!isMedia) listOf("kws$d.web.telegram.org", "kws$d-1.web.telegram.org")
        else listOf("kws$d-1.web.telegram.org", "kws$d.web.telegram.org")
    }
}

fun humanBytes(n: Long): String {
    var v = n.toDouble()
    for (unit in arrayOf("B", "KB", "MB", "GB")) {
        if (kotlin.math.abs(v) < 1024) return String.format(java.util.Locale.US, "%.1f%s", v, unit)
        v /= 1024
    }
    return String.format(java.util.Locale.US, "%.1fTB", v)
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
