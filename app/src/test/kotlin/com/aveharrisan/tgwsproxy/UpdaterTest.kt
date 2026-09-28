package com.aveharrisan.tgwsproxy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/** Обновление против своего сервера вместо GitHub: ответы API, редирект на файл, ошибки. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdaterTest {
    private lateinit var server: MockWebServer
    private lateinit var ctx: Context
    private val base get() = server.url("").toString().trimEnd('/')
    private var latest: Pair<Int, String> = 200 to ""
    private var apkBytes = ByteArray(0)

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/latest" -> MockResponse().setResponseCode(latest.first).setBody(latest.second)
                // Как у GitHub: ссылка на файл отвечает редиректом на хранилище.
                "/download/app.apk" -> MockResponse().setResponseCode(302).addHeader("Location", "$base/storage/app.apk")
                "/storage/app.apk" -> MockResponse().setBody(Buffer().write(apkBytes))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        Updater.api = "$base/latest"
        Updater.setStatus(UpdateStatus.Idle)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun release(tag: String, size: Long = 1000) = """
        {"tag_name":"$tag","html_url":"$base/page","body":"## $tag\n\n- Первое изменение\n  с переносом строки\n- Второе",
         "assets":[{"name":"notes.txt","browser_download_url":"$base/x","size":1},
                   {"name":"TG-WS-Proxy-$tag.apk","browser_download_url":"$base/download/app.apk","size":$size}]}
    """.trimIndent()

    @Test
    fun newerReleaseIsOffered() = runBlocking {
        latest = 200 to release("v9.9.9")
        Updater.check(ctx, manual = true)
        val s = Updater.status.value
        assertTrue("$s", s is UpdateStatus.Available)
        s as UpdateStatus.Available
        assertEquals("9.9.9", s.release.version)
        assertEquals(listOf("Первое изменение с переносом строки", "Второе"), s.release.notes)
        assertEquals("$base/download/app.apk", s.release.apkUrl)
    }

    @Test
    fun sameVersionIsUpToDate() = runBlocking {
        latest = 200 to release("v${BuildConfig.VERSION_NAME}")
        Updater.check(ctx, manual = true)
        assertEquals(UpdateStatus.UpToDate, Updater.status.value)
    }

    @Test
    fun noReleasesManualShowsReason() = runBlocking {
        latest = 404 to "{}"
        Updater.check(ctx, manual = true)
        val s = Updater.status.value
        assertTrue("$s", s is UpdateStatus.Failed && s.message.contains("выпусков"))
    }

    @Test
    fun autoCheckFailsSilently() = runBlocking {
        latest = 500 to "oops"
        Updater.check(ctx, manual = false)
        assertEquals(UpdateStatus.Idle, Updater.status.value)
    }

    @Test
    fun releaseWithoutApkIsError() = runBlocking {
        latest = 200 to """{"tag_name":"v9.9.9","body":"","assets":[]}"""
        Updater.check(ctx, manual = true)
        val s = Updater.status.value
        assertTrue("$s", s is UpdateStatus.Failed && s.message.contains("APK"))
    }

    @Test
    fun brokenDownloadIsRejected() = runBlocking {
        apkBytes = "это не apk".toByteArray()
        latest = 200 to release("v9.9.9", apkBytes.size.toLong())
        Updater.check(ctx, manual = true)
        val r = (Updater.status.value as UpdateStatus.Available).release
        Updater.download(ctx, r)
        val s = Updater.status.value
        assertTrue("$s", s is UpdateStatus.Failed && s.message.contains("повреждён"))
    }

    @Test
    fun realApkDownloadsThroughRedirect() = runBlocking {
        val apk = File("build/outputs/apk/release").listFiles { f -> f.name.endsWith(".apk") }?.firstOrNull()
            ?: error("сначала соберите :app:assembleRelease")
        apkBytes = apk.readBytes()
        latest = 200 to release("v9.9.9", apkBytes.size.toLong())
        Updater.check(ctx, manual = true)
        val r = (Updater.status.value as UpdateStatus.Available).release
        Updater.download(ctx, r)
        val s = Updater.status.value
        assertTrue("$s", s is UpdateStatus.Ready)
        s as UpdateStatus.Ready
        assertEquals(apkBytes.size.toLong(), s.file.length())
        assertTrue(s.file.name.endsWith("9.9.9.apk"))
    }

    @Test
    fun missingFileFallsBackToDownload() {
        val r = Release("9.9.9", emptyList(), "$base/download/app.apk", 1, "$base/page")
        val gone = File(ctx.cacheDir, "updates/gone.apk")
        Updater.setStatus(UpdateStatus.Ready(r, gone))
        Updater.install(ctx, gone)
        assertEquals(UpdateStatus.Available(r), Updater.status.value)
    }

    @Test
    fun restartFlagIsOneShot() {
        ctx.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putBoolean("restartProxy", true).commit()
        assertTrue(Updater.takeRestartFlag(ctx))
        assertFalse(Updater.takeRestartFlag(ctx))
    }
}
