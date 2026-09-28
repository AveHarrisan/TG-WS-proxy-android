package com.aveharrisan.tgwsproxy.core

import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IssueReportTest {
    private val s = "0123456789abcdef0123456789abcdef"

    @Test
    fun masksSecrets() {
        val text = "tg://proxy?server=127.0.0.1&port=1443&secret=dd$s\nСекрет: $s\nee${s}676f6f676c652e636f6d\nport=1443"
        val m = IssueReport.mask(text)
        assertFalse(m.contains(s), m)
        assertTrue(m.contains("port=1443"))
        assertTrue(m.contains("secret=••••"))
    }

    @Test
    fun keepsIpsAndShortHex() {
        assertEquals("DC2 149.154.167.220 ws err=deadbeef", IssueReport.mask("DC2 149.154.167.220 ws err=deadbeef"))
    }

    @Test
    fun urlFitsAndKeepsNewestLines() {
        val log = (1..2000).map { "12:00:00  INFO   строка номер $it с кириллицей" }
        val link = IssueReport.issueUrl("AveHarrisan/TG-WS-proxy-android", "Проблема", "Версия 1.1.0", log)
        val url = link.url
        assertTrue(link.trimmed)
        assertTrue(link.logLines in 20 until 2000, "строк ${link.logLines}")
        assertTrue(url.length <= 7000, "длина ${url.length}")
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue(body.contains("строка номер 2000"))
        assertFalse(body.contains("строка номер 1 с"))
        assertTrue(body.startsWith("Версия 1.1.0"))
    }

    @Test
    fun shortLogNotTrimmed() {
        val link = IssueReport.issueUrl("a/b", "t", "head", listOf("одна строка"))
        assertFalse(link.trimmed)
        assertEquals(1, link.logLines)
    }

    @Test
    fun probeSummaryIsCompact() {
        val rs = listOf(
            Diagnostics.Result("WebSocket", "DC2 149.154.167.220", true, 226, "101"),
            Diagnostics.Result("WebSocket", "DC4 149.154.167.220", true, 215, "101"),
            Diagnostics.Result("TCP напрямую", "DC1 149.154.175.50", false, 5000, "failed to connect ... after 5000ms"),
            Diagnostics.Result("TCP напрямую", "DC2 149.154.167.51", false, 5000, "failed to connect ... after 5000ms"),
        )
        assertEquals(listOf("WebSocket: 2 из 2 (DC2 226 мс, DC4 215 мс)", "TCP напрямую: 0 из 2 (DC1 ✗, DC2 ✗)"), IssueReport.probeSummary(rs))
    }

    @Test
    fun realisticHeadLeavesRoomForLog() {
        // Сжатая шапка настоящего отчёта (28.09.2026, задача #1) — журнал должен влезать заметной частью.
        val head = """**Что случилось:** <!-- опишите своими словами: что делали и что пошло не так -->

### Устройство
- Приложение: 1.1.3 (5)
- Android 14 (API 34), Xiaomi 2306EPN60G
- Сеть: Wi-Fi
- Уведомления: да, экономия батареи отключена: да, кнопка в шторке: да

### Прокси
- Состояние: RUNNING
- Статистика: total=31 active=8 ws=29 tcp_fb=0 cf=2 front=21 bad=0 masked=0 err=0 pool=28/29 cf_pool=n/a up=165.4KB down=6.4MB
- Порт 1443, DC→IP: 2:149.154.167.220, 4:149.154.167.220
- CF-прокси: да, свои домены: нет, Worker: нет; пул 4, без TLS: нет, из сети: нет, Fake TLS: нет, автозапуск: да
- Эксперимент: пул засыпает: нет, тихое уведомление: нет, Wi-Fi с энергосбережением: нет, не засыпать: да

### Проверка связи
- WebSocket: 2 из 2 (DC2 226 мс, DC4 215 мс)
- Фронтинг: 1 из 2 (DC2 ✗, DC4 158 мс)
- CF-прокси: 3 из 3 (nothin******.c*.uk 234 мс, havegr******.c*.uk 272 мс, fixt*****.c*.uk 226 мс)
- TCP напрямую: 0 из 6 (DC1 ✗, DC2 ✗, DC3 ✗, DC4 ✗, DC5 ✗, DC203 ✗)

_Полный отчёт с журналом — файлом: «Сообщить о проблеме» → «Отправить файлом»._"""
        val log = (1..500).map { "14:31:0${it % 10}  INFO   [#$it] DC2 WS не работает, пауза 30s" }
        val link = IssueReport.issueUrl("AveHarrisan/TG-WS-proxy-android", "Проблема: 1.1.3, Xiaomi 2306EPN60G, Android 14", head, log)
        assertTrue(link.logLines >= 15, "в задачу вошло строк журнала: ${link.logLines}")
    }
}
