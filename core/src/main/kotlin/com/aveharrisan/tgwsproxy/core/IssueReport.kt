package com.aveharrisan.tgwsproxy.core

import java.net.URLEncoder

/** Отчёт о проблеме: прячем секреты и укладываем текст в ссылку «новая задача» на GitHub. */
object IssueReport {
    /** Секрет прокси (32 hex, с приставкой dd/ee и доменом Fake TLS) в отчёт не попадает. */
    private val secret = Regex("""(?i)(secret=|секрет[:=]?\s*)(?:dd|ee)?[0-9a-f]{32}[0-9a-f]*""")
    private val bareSecret = Regex("""(?i)(?<![0-9a-f])(?:dd|ee)?[0-9a-f]{32}(?:[0-9a-f]{2})*(?![0-9a-f])""")

    fun mask(text: String): String =
        bareSecret.replace(secret.replace(text) { it.groupValues[1] + "••••" }, "••••")

    /**
     * Ссылка на новую задачу с заполненным текстом. Длинную ссылку GitHub не откроет,
     * поэтому журнал в теле режем с начала — свежие строки важнее старых.
     */
    class Link(val url: String, val logLines: Int, val trimmed: Boolean)

    fun issueUrl(repo: String, title: String, head: String, log: List<String>, maxUrl: Int = 7000): Link {
        fun build(lines: List<String>): String {
            val body = buildString {
                append(head.trimEnd()).append("\n\n")
                if (lines.isNotEmpty()) {
                    append("<details><summary>Журнал (последние ${lines.size} строк)</summary>\n\n```\n")
                    lines.forEach { append(it).append('\n') }
                    append("```\n</details>\n")
                }
            }
            return "https://github.com/$repo/issues/new?title=${enc(title)}&body=${enc(body)}"
        }
        var lo = 0
        var hi = log.size
        // Бинарный поиск наибольшего хвоста журнала, который влезает в ссылку.
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (build(log.takeLast(mid)).length <= maxUrl) lo = mid else hi = mid - 1
        }
        return Link(build(log.takeLast(lo)), lo, lo < log.size)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
