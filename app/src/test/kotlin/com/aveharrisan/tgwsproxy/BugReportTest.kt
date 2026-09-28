package com.aveharrisan.tgwsproxy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aveharrisan.tgwsproxy.core.Log
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLDecoder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BugReportTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun reportHasContextAndNoSecret() {
        val secret = Settings.current.secret
        Log.i("Ссылка для Telegram: ${Settings.current.localLink()}")
        Log.w("проверочная строка журнала")
        val r = BugReport.collect(ctx, withProbe = false)
        val text = r.fullText
        assertTrue(text.contains("Android"))
        assertTrue(text.contains("Порт ${Settings.current.port}"))
        assertTrue("журнал с диска попал в отчёт", text.contains("проверочная строка журнала"))
        assertFalse("секрет попал в отчёт", text.contains(secret))

        val link = r.issueLink()
        assertTrue(link.url.startsWith("https://github.com/${Updater.REPO}/issues/new?"))
        val body = URLDecoder.decode(link.url.substringAfter("&body="), "UTF-8")
        assertTrue(body.contains("проверочная строка журнала"))
        assertFalse(body.contains(secret))
        assertTrue("подсказка, куда писать", body.startsWith("### Что случилось\n" + BugReport.PROBLEM_PLACEHOLDER))
        assertTrue("заголовок не пустой", java.net.URLDecoder.decode(link.url.substringAfter("title=").substringBefore("&body="), "UTF-8").length > "Проблема: ".length)
    }
}
