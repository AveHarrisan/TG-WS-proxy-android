package com.aveharrisan.tgwsproxy

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.aveharrisan.tgwsproxy.core.Log
import com.aveharrisan.tgwsproxy.core.ReleaseNotes
import com.aveharrisan.tgwsproxy.core.ReleaseNotes.isNewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Выпуск на GitHub: версия, что изменилось и ссылка на APK. */
data class Release(
    val version: String,
    val notes: List<String>,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String,
)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val release: Release) : UpdateStatus
    data class Downloading(val release: Release, val percent: Int) : UpdateStatus
    data class Ready(val release: Release, val file: File) : UpdateStatus
    /** APK отдан системе: либо ставится молча, либо ждёт подтверждения в системном окне. */
    data class Installing(val release: Release) : UpdateStatus
    data class Failed(val message: String, val release: Release?) : UpdateStatus
}

/**
 * Обновление без выхода из приложения: спрашиваем GitHub о свежем выпуске,
 * качаем APK к себе и отдаём системе на установку поверх текущей версии.
 */
object Updater {
    const val REPO = "AveHarrisan/TG-WS-proxy-android"
    const val RELEASES_URL = "https://github.com/$REPO/releases"
    /** Меняется только в тестах — там вместо GitHub свой сервер. */
    internal var api = "https://api.github.com/repos/$REPO/releases/latest"
    private const val AUTO_CHECK_EVERY_MS = 5 * 60 * 1000L
    /** Пока работает прокси, проверяем в фоне раз в час — как KotaMusic, пока открыт клиент. */
    const val BACKGROUND_CHECK_EVERY_SEC = 60 * 60L
    const val ACTION_INSTALL_RESULT = "com.aveharrisan.tgwsproxy.INSTALL_RESULT"

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status

    /** Для тестов: показать плашку в нужном состоянии. */
    internal fun setStatus(s: UpdateStatus) { _status.value = s }

    /** Плашку закрыли кнопкой «Позже» — до следующего запуска не показываем. */
    val dismissed = MutableStateFlow(false)

    /** Прокси работал до установки обновления — после неё его надо запустить. Флаг одноразовый. */
    fun takeRestartFlag(ctx: Context): Boolean {
        val p = prefs(ctx)
        val v = p.getBoolean("restartProxy", false)
        if (v) p.edit().remove("restartProxy").apply()
        return v
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Только что обновились: версия, до которой обновились, — для плашки «Обновлено до …». */
    val justUpdated = MutableStateFlow<Release?>(null)

    /**
     * При каждом запуске процесса: если версия сменилась с прошлого раза — значит, обновились.
     * Заодно убираем скачанный APK: он больше не нужен.
     */
    fun onAppStart(ctx: Context) {
        val p = prefs(ctx)
        val last = p.getString("lastRunVersion", null)
        if (last != null && last != BuildConfig.VERSION_NAME) {
            val cached = cachedRelease(ctx)?.takeIf { it.version == BuildConfig.VERSION_NAME }
            justUpdated.value = cached ?: Release(BuildConfig.VERSION_NAME, emptyList(), "", 0, RELEASES_URL)
            File(ctx.cacheDir, "updates").listFiles()?.forEach { it.delete() }
            Log.i("Обновлено: $last → ${BuildConfig.VERSION_NAME}")
        }
        if (last != BuildConfig.VERSION_NAME) p.edit().putString("lastRunVersion", BuildConfig.VERSION_NAME).apply()
    }

    fun cachedRelease(ctx: Context): Release? =
        prefs(ctx).getString("lastRelease", null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }

    /**
     * Фоновая проверка из службы прокси. Нашлась новая версия — уведомление с кнопкой «Обновить»,
     * по одному разу на версию, чтобы не надоедать.
     */
    suspend fun backgroundCheck(ctx: Context) {
        check(ctx, manual = false)
        val s = _status.value as? UpdateStatus.Available ?: return
        val p = prefs(ctx)
        if (p.getString("notifiedVersion", null) == s.release.version) return
        UpdateNotifications.showAvailable(ctx, s.release)
        p.edit().putString("notifiedVersion", s.release.version).apply()
    }

    /** Проверка при открытии приложения — не чаще раза в несколько минут (лимит GitHub — 60 запросов в час). */
    suspend fun autoCheck(ctx: Context) {
        val last = prefs(ctx).getLong("lastCheck", 0)
        val cached = cachedRelease(ctx)
        if (System.currentTimeMillis() - last < AUTO_CHECK_EVERY_MS) {
            if (cached != null && isNewer(cached.version, BuildConfig.VERSION_NAME) && _status.value == UpdateStatus.Idle)
                _status.value = UpdateStatus.Available(cached)
            return
        }
        check(ctx, manual = false)
    }

    suspend fun check(ctx: Context, manual: Boolean) {
        val s = _status.value
        if (s is UpdateStatus.Checking || s is UpdateStatus.Downloading || s is UpdateStatus.Installing || s is UpdateStatus.Ready) return
        if (manual) dismissed.value = false
        _status.value = UpdateStatus.Checking
        _status.value = try {
            val json = withContext(Dispatchers.IO) { fetchLatest() }
            prefs(ctx).edit().putLong("lastCheck", System.currentTimeMillis()).putString("lastRelease", json.toString()).apply()
            val release = parse(json)
            if (isNewer(release.version, BuildConfig.VERSION_NAME)) UpdateStatus.Available(release) else UpdateStatus.UpToDate
        } catch (e: Exception) {
            Log.w("Проверка обновлений: ${e.message ?: e.javaClass.simpleName}")
            // Молча, если проверка шла сама: плашка с ошибкой при каждом запуске только раздражает.
            if (manual) UpdateStatus.Failed(explain(e), null) else UpdateStatus.Idle
        }
    }

    suspend fun download(ctx: Context, release: Release) {
        if (_status.value is UpdateStatus.Downloading) return
        _status.value = UpdateStatus.Downloading(release, 0)
        _status.value = try {
            val file = withContext(Dispatchers.IO) { fetchApk(ctx, release) }
            UpdateStatus.Ready(release, file)
        } catch (e: Exception) {
            Log.w("Скачивание обновления: ${e.message ?: e.javaClass.simpleName}")
            UpdateStatus.Failed(explain(e), release)
        }
    }

    /** «Обновить» — как в KotaMusic: скачать и сразу поставить, без второго нажатия. */
    suspend fun update(ctx: Context, release: Release) {
        val s = _status.value
        if (s is UpdateStatus.Downloading || s is UpdateStatus.Installing) return
        if (s is UpdateStatus.Ready && s.release == release && s.file.exists()) { install(ctx, s.file); return }
        download(ctx, release)
        (_status.value as? UpdateStatus.Ready)?.let { install(ctx, it.file) }
    }

    /** Может ли приложение ставить APK. Если нет — сначала ведём в настройки за разрешением. */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Ушли за разрешением на установку: по возвращении ставим сразу. */
    @Volatile var installAfterPermission = false

    fun openInstallPermission(ctx: Context) {
        installAfterPermission = true
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun install(ctx: Context, file: File) {
        val s = _status.value
        if (!file.exists()) {
            if (s is UpdateStatus.Ready) _status.value = UpdateStatus.Available(s.release)
            return
        }
        // Установка поверх останавливает приложение, а с ним и прокси. Запоминаем, что он работал,
        // и после обновления поднимаем его снова (BootReceiver).
        prefs(ctx).edit().putBoolean("restartProxy", ProxyService.isActive).commit()
        val release = (s as? UpdateStatus.Ready)?.release
        try {
            installWithSession(ctx, file)
            if (release != null) _status.value = UpdateStatus.Installing(release)
        } catch (e: Exception) {
            // Установщик пакетов недоступен (редкие прошивки) — старый путь через системный экран установки.
            Log.w("Установка через PackageInstaller не вышла (${e.message}), открываю системный установщик")
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Первое обновление Android всегда подтверждает сам. Дальше, на Android 12+, приложение —
     * «установщик» самого себя и может обновляться без вопросов (USER_ACTION_NOT_REQUIRED).
     */
    private fun installWithSession(ctx: Context, file: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(ctx, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(ctx, id, result, flags).intentSender)
        }
        Log.i("Обновление передано системе на установку")
    }

    /** Итог установки от системы (InstallResultReceiver). */
    internal fun onInstallResult(ctx: Context, status: Int, message: String?) {
        val s = _status.value
        val release = (s as? UpdateStatus.Installing)?.release ?: (s as? UpdateStatus.Ready)?.release
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Log.i("Обновление установлено")
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // Отменили в системном окне — оставляем «Установить», файл уже скачан.
                Log.i("Установку обновления отменили")
                val file = File(ctx.cacheDir, "updates").listFiles()?.firstOrNull { it.name.endsWith(".apk") }
                if (release != null && file != null) _status.value = UpdateStatus.Ready(release, file)
            }
            else -> {
                Log.w("Установка обновления не удалась: $status $message")
                val text = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "эта версия подписана другим ключом и не встанет поверх. Удалите приложение и поставьте новую версию со страницы выпуска"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "не хватает места на телефоне"
                    else -> "система не установила обновление" + (message?.let { " ($it)" } ?: "")
                }
                _status.value = UpdateStatus.Failed(text, release)
            }
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TG-WS-Proxy-Android/${BuildConfig.VERSION_NAME}")
        }

    private fun fetchLatest(): JSONObject {
        val c = open(api)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            when (val code = c.responseCode) {
                200 -> return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                404 -> throw IOException("выпусков на GitHub пока нет")
                403, 429 -> throw IOException("GitHub временно ограничил проверки, попробуйте через час")
                else -> throw IOException("GitHub ответил $code")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(json: JSONObject): Release {
        val assets = json.optJSONArray("assets")
        var apkUrl = ""
        var apkSize = 0L
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.optString("browser_download_url")
                apkSize = a.optLong("size")
                break
            }
        }
        if (apkUrl.isEmpty()) throw IOException("в выпуске нет файла APK")
        return Release(
            version = json.optString("tag_name").removePrefix("v"),
            notes = ReleaseNotes.parse(json.optString("body")),
            apkUrl = apkUrl,
            apkSize = apkSize,
            pageUrl = json.optString("html_url", RELEASES_URL),
        )
    }

    private fun fetchApk(ctx: Context, release: Release): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "update.part")
        val c = open(release.apkUrl)
        try {
            if (c.responseCode != 200) throw IOException("GitHub ответил ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            var shown = -1
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (pct != shown) { shown = pct; _status.value = UpdateStatus.Downloading(release, pct) }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("файл скачался не полностью")
        } finally {
            c.disconnect()
        }
        // Проверяем, что это наше приложение и оно новее: иначе система выдаст невнятную ошибку.
        val info = ctx.packageManager.getPackageArchiveInfo(part.path, 0)
            ?: throw IOException("скачанный файл повреждён")
        if (info.packageName != ctx.packageName) throw IOException("в выпуске чужой APK")
        if (!sameSigner(ctx, part)) throw IOException(
            "эта версия подписана другим ключом и не встанет поверх. Удалите приложение и поставьте новую версию со страницы выпуска"
        )
        val apk = File(dir, "TG-WS-Proxy-${release.version}.apk")
        if (!part.renameTo(apk)) throw IOException("не удалось сохранить файл")
        return apk
    }

    @Suppress("DEPRECATION")
    private fun sameSigner(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        fun certs(info: android.content.pm.PackageInfo?): Set<String> {
            if (info == null) return emptySet()
            val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            } else info.signatures
            return sigs.orEmpty().map { it.toCharsString() }.toSet()
        }
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        // Не смогли прочитать подпись (например, у системы свой разборщик APK) — решает сама система.
        val mine = try { certs(pm.getPackageInfo(ctx.packageName, flag)) } catch (e: Throwable) { return true }
        val theirs = try { certs(pm.getPackageArchiveInfo(apk.path, flag)) } catch (e: Throwable) { return true }
        if (mine.isEmpty() || theirs.isEmpty()) return true
        return mine.intersect(theirs).isNotEmpty()
    }

    private fun explain(e: Exception): String = when (e) {
        is UnknownHostException -> "нет связи с GitHub — проверьте интернет"
        is SocketTimeoutException -> "GitHub не ответил вовремя, попробуйте ещё раз"
        is SSLException -> "не удалось установить защищённое соединение с GitHub"
        else -> e.message ?: e.javaClass.simpleName
    }
}
