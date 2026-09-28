package com.aveharrisan.tgwsproxy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // Настройки в SharedPreferences у каждого теста свои — подтягиваем их и сбрасываем «отложено».
        Updater.loadPrefs(ctx)
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

    @Test
    fun oneTapUpdateGoesToPackageInstaller() = runBlocking {
        val apk = File("build/outputs/apk/release").listFiles { f -> f.name.endsWith(".apk") }!!.first()
        apkBytes = apk.readBytes()
        latest = 200 to release("v9.9.9", apkBytes.size.toLong())
        Updater.check(ctx, manual = true)
        val r = (Updater.status.value as UpdateStatus.Available).release
        Updater.update(ctx, r)
        val s = Updater.status.value
        assertTrue("$s", s is UpdateStatus.Installing)
        val sessions = ctx.packageManager.packageInstaller.allSessions
        assertTrue("сессия установки не создана", sessions.isNotEmpty())
    }

    @Test
    fun cancelledInstallKeepsDownloadedFile() {
        val r = Release("9.9.9", emptyList(), "$base/download/app.apk", 1, "$base/page")
        val f = File(ctx.cacheDir, "updates").apply { mkdirs() }.let { File(it, "TG-WS-Proxy-9.9.9.apk") }.apply { writeText("x") }
        Updater.setStatus(UpdateStatus.Installing(r))
        Updater.onInstallResult(ctx, android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertEquals(UpdateStatus.Ready(r, f), Updater.status.value)
    }

    @Test
    fun appStartAfterUpdateShowsUpdatedCard() {
        ctx.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putString("lastRunVersion", "0.9.0").commit()
        Updater.justUpdated.value = null
        Updater.onAppStart(ctx)
        assertEquals(BuildConfig.VERSION_NAME, Updater.justUpdated.value?.version)
        Updater.justUpdated.value = null
        Updater.onAppStart(ctx)
        assertEquals("второй запуск той же версии — без плашки", null, Updater.justUpdated.value)
    }

    @Test
    fun backgroundCheckNotifiesOncePerVersion() = runBlocking {
        org.robolectric.Shadows.shadowOf(ctx as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        latest = 200 to release("v9.9.9")
        val nm = org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.app.NotificationManager::class.java))
        val before = nm.allNotifications.size
        Updater.backgroundCheck(ctx)
        Updater.setStatus(UpdateStatus.Idle)
        Updater.backgroundCheck(ctx)
        assertEquals(before + 1, nm.allNotifications.size)
    }

    @Test
    fun snoozeHidesThisVersionOnlyForAWhile() {
        Updater.savePrefs(ctx, Updater.Prefs(remindAfterHours = 24))
        val r = Release("9.9.9", emptyList(), "", 1, "")
        Updater.snooze(ctx, r)
        assertTrue(Updater.isSnoozed(r))
        assertFalse("через сутки напоминаем снова", Updater.isSnoozed(r, System.currentTimeMillis() + 25 * 3_600_000L))
        assertFalse("более новая версия — сразу", Updater.isSnoozed(r.copy(version = "9.9.10")))
        Updater.loadPrefs(ctx)
        assertTrue("отложено переживает перезапуск", Updater.isSnoozed(r))
    }

    @Test
    fun manualCheckClearsSnooze() = runBlocking {
        latest = 200 to release("v9.9.9")
        Updater.snooze(ctx, Release("9.9.9", emptyList(), "", 1, ""))
        Updater.check(ctx, manual = true)
        assertNull(Updater.snoozed.value)
    }

    @Test
    fun autoOffSkipsAutomaticChecks() = runBlocking {
        Updater.savePrefs(ctx, Updater.Prefs(auto = false))
        latest = 200 to release("v9.9.9")
        Updater.autoCheck(ctx)
        Updater.backgroundCheck(ctx)
        assertEquals(UpdateStatus.Idle, Updater.status.value)
        Updater.savePrefs(ctx, Updater.Prefs())
    }
}
