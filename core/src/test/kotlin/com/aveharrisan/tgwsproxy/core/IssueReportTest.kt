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
}
