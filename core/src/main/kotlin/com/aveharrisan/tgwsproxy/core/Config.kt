package com.aveharrisan.tgwsproxy.core

import java.net.Inet4Address
import java.net.InetAddress

data class ProxyConfig(
    val host: String = "127.0.0.1",
    val port: Int = 1443,
    val secret: String = Rnd.bytes(16).toHex(),
    val dcRedirects: Map<Int, String> = DEFAULT_DC_REDIRECTS,
    val bufferSize: Int = 256 * 1024,
    val poolSize: Int = 4,
    val fallbackCfProxy: Boolean = true,
    val cfProxyUserDomains: List<String> = emptyList(),
    val cfProxyWorkerDomains: List<String> = emptyList(),
    val disableSecure: Boolean = false,
    val fakeTlsDomain: String = "",
    val forceTestDc: Boolean = false,
) {
    val secretBytes: ByteArray get() = secret.hexToBytes()

    fun validate() {
        require(port in 1..65535) { "Порт должен быть от 1 до 65535" }
        require(secret.length == 32 && secret.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Секрет — ровно 32 шестнадцатеричных символа"
        }
    }

    fun link(linkHost: String = host): String =
        if (fakeTlsDomain.isNotEmpty())
            "tg://proxy?server=$linkHost&port=$port&secret=ee$secret${fakeTlsDomain.toByteArray(Charsets.US_ASCII).toHex()}"
        else "tg://proxy?server=$linkHost&port=$port&secret=dd$secret"

    companion object {
        val DEFAULT_DC_REDIRECTS: Map<Int, String> = linkedMapOf(2 to "149.154.167.220", 4 to "149.154.167.220")
    }
}

object DcIpParser {
    /** Разбор строк вида «2:149.154.167.220». Бросает IllegalArgumentException с понятным текстом. */
    fun parse(entries: List<String>): Map<Int, String> {
        val out = linkedMapOf<Int, String>()
        for (raw in entries) {
            val entry = raw.trim()
            if (entry.isEmpty()) continue
            val sep = entry.indexOf(':')
            require(sep > 0) { "Неверная строка «$entry», нужно DC:IP" }
            val dc = entry.substring(0, sep).trim().toIntOrNull()
            val ip = entry.substring(sep + 1).trim()
            require(dc != null && isIpv4(ip)) { "Неверная строка «$entry»" }
            out[dc] = ip
        }
        return out
    }

    fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        if (!parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }) return false
        return runCatching { InetAddress.getByName(s) is Inet4Address }.getOrDefault(false)
    }
}

object Domains {
    fun coerceList(value: String): List<String> = coerceList(listOf(value))

    fun coerceList(values: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (v in values) for (item in v.replace(',', ' ').replace(';', ' ').split(Regex("\\s+"))) {
            val t = item.trim()
            if (t.isEmpty() || !seen.add(t.lowercase())) continue
            out += t
        }
        return out
    }

    fun isValid(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253 || domain.startsWith('.') || domain.endsWith('.')) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false
        for (l in labels) {
            if (l.isEmpty() || l.length > 63 || l.first() == '-' || l.last() == '-') return false
            if (!l.all { it.isLetterOrDigit() || it == '-' }) return false
        }
        val tld = labels.last()
        return tld.length >= 2 && tld.any { it.isLetter() }
    }

    fun normalizePool(domains: List<String>): List<String> {
        val seen = HashSet<String>()
        return domains.map { it.trim().lowercase() }.filter { isValid(it) && seen.add(it) }
    }
}
