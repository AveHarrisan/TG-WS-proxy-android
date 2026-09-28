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
    }

    @Test
    fun notes() {
        val body = "## 1.1.0 — 28.09.2026\n\n- Обновления **внутри** приложения:\n  плашка и `Скачать`.\n* Кнопка в шторке\n\nТекст вне списка"
        assertEquals(listOf("Обновления внутри приложения: плашка и Скачать.", "Кнопка в шторке"), ReleaseNotes.parse(body))
    }
}
