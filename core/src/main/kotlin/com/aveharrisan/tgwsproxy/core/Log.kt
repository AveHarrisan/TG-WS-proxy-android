package com.aveharrisan.tgwsproxy.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Level { DEBUG, INFO, WARN, ERROR }

/** Простой журнал ядра: приложение подписывается и показывает строки в интерфейсе. */
object Log {
    fun interface Sink { fun write(level: Level, line: String) }

    @Volatile var minLevel: Level = Level.INFO
    @Volatile var censorDomains: Boolean = true
    private val sinks = java.util.concurrent.CopyOnWriteArrayList<Sink>()
    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    fun addSink(s: Sink) { sinks += s }
    fun removeSink(s: Sink) { sinks -= s }

    fun d(msg: String) = log(Level.DEBUG, msg)
    fun i(msg: String) = log(Level.INFO, msg)
    fun w(msg: String) = log(Level.WARN, msg)
    fun e(msg: String, t: Throwable? = null) = log(Level.ERROR, if (t != null) "$msg: $t" else msg)

    private fun log(level: Level, msg: String) {
        if (level < minLevel || sinks.isEmpty()) return
        val text = if (censorDomains) DomainCensor.apply(msg) else msg
        val line = "${fmt.get()!!.format(Date())}  ${level.name.padEnd(5)}  $text"
        sinks.forEach { runCatching { it.write(level, line) } }
    }
}

/** Прячет в логах домены CF-прокси, чтобы их не палили в скриншотах; telegram.org оставляем. */
object DomainCensor {
    private val CODE_PREFIXES = listOf("com.aveharrisan.", "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.", "sun.", "okhttp3.")
    private val pattern = Regex("""(?<![\w-])(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}(?![\w-])""")

    fun apply(s: String): String = pattern.replace(s) { m ->
        val domain = m.value
        val n = domain.lowercase().trimEnd('.')
        if (n == "telegram.org" || n.endsWith(".telegram.org")) return@replace domain
        // Имена классов в текстах ошибок (com.aveharrisan…, java.net…) — не домены.
        if (CODE_PREFIXES.any { n.startsWith(it) }) return@replace domain
        val parts = domain.split('.')
        if (parts.size < 2) return@replace domain
        parts.mapIndexed { i, p ->
            if (i == parts.lastIndex) p else p.substring(0, p.length / 2) + "*".repeat(p.length - p.length / 2)
        }.joinToString(".")
    }
}
