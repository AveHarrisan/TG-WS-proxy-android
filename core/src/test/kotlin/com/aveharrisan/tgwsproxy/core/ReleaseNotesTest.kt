package com.aveharrisan.tgwsproxy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseNotesTest {
    @Test
    fun versions() {
        assertTrue(ReleaseNotes.isNewer("1.10.0", "1.9.2"))
        assertTrue(ReleaseNotes.isNewer("v1.1.0", "1.0.0"))
        assertTrue(ReleaseNotes.isNewer("1.0.1", "1.0"))
        assertFalse(ReleaseNotes.isNewer("1.1.0", "1.1.0"))
        assertFalse(ReleaseNotes.isNewer("1.0.9", "1.1.0"))
        // Бета: выпуск той же версии новее беты, бета не новее выпуска, 1.1.10 не «обновляет» бету 1.1.11.
        assertTrue(ReleaseNotes.isNewer("1.1.11", "1.1.11-beta1"))
        assertFalse(ReleaseNotes.isNewer("1.1.11-beta1", "1.1.11"))
        assertFalse(ReleaseNotes.isNewer("1.1.10", "1.1.11-beta1"))
        assertTrue(ReleaseNotes.isNewer("1.1.11-beta1", "1.1.10"))
        // Беты между собой — по номеру, а не строкой.
        assertTrue(ReleaseNotes.isNewer("1.1.11-beta2", "1.1.11-beta1"))
        assertTrue(ReleaseNotes.isNewer("1.1.11-beta10", "1.1.11-beta2"))
        assertFalse(ReleaseNotes.isNewer("1.1.11-beta2", "1.1.11-beta2"))
        assertTrue(ReleaseNotes.isBeta("v1.1.11-beta2"))
        assertFalse(ReleaseNotes.isBeta("1.1.10"))
    }

    @Test
    fun versionsFromReleasesFeed() {
        val atom = """<feed><entry><link rel="alternate" type="text/html" href="https://github.com/A/B/releases/tag/v1.1.11-beta1"/></entry>
            <entry><link rel="alternate" type="text/html" href="https://github.com/A/B/releases/tag/v1.1.10"/></entry></feed>"""
        val all = ReleaseNotes.versionsFromAtom(atom)
        assertEquals(listOf("1.1.11-beta1", "1.1.10"), all)
        assertEquals("1.1.11-beta1", all.maxWithOrNull(ReleaseNotes::compare))
    }

    @Test
    fun notes() {
        val body = "## 1.1.0 — 28.09.2026\n\n- Обновления **внутри** приложения:\n  плашка и `Скачать`.\n* Кнопка в шторке\n\nТекст вне списка"
        assertEquals(listOf("Обновления внутри приложения: плашка и Скачать.", "Кнопка в шторке"), ReleaseNotes.parse(body))
    }

    @Test
    fun changelogSectionOfVersion() {
        val cl = "# Что менялось\n\n## 1.1.7 — 28.09.2026\n\n- Первое\n  продолжение\n- Второе\n\n## 1.1.6 — 28.09.2026\n\n- Старое\n"
        assertEquals(listOf("Первое продолжение", "Второе"), ReleaseNotes.parse(ReleaseNotes.changelogSection(cl, "1.1.7")))
        assertEquals(listOf("Старое"), ReleaseNotes.parse(ReleaseNotes.changelogSection(cl, "1.1.6")))
        assertEquals("", ReleaseNotes.changelogSection(cl, "9.9.9"))
    }

    @Test
    fun versionFromReleaseUrl() {
        assertEquals("1.1.7", ReleaseNotes.versionFromTagUrl("https://github.com/AveHarrisan/TG-WS-proxy-android/releases/tag/v1.1.7"))
        assertEquals(null, ReleaseNotes.versionFromTagUrl("https://github.com/AveHarrisan/TG-WS-proxy-android/releases"))
    }
}
