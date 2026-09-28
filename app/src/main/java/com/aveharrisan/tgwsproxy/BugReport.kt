package com.aveharrisan.tgwsproxy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.aveharrisan.tgwsproxy.core.Diagnostics
import com.aveharrisan.tgwsproxy.core.IssueReport
import com.aveharrisan.tgwsproxy.core.Stats
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Отчёт для разбора проблемы: устройство, настройки без секрета, проверка связи и журнал. */
class BugReport(val head: String, val log: List<String>, val file: File) {
    val fullText: String get() = file.readText()

    fun issueLink(): IssueReport.Link = IssueReport.issueUrl(Updater.REPO, "Проблема: ", head, log)

    companion object {
        private const val LOG_LINES = 3000

        /** Долго: проверка связи ждёт ответа до нескольких секунд. Звать не с главного потока. */
        fun collect(ctx: Context, withProbe: Boolean = true, onStep: (String) -> Unit = {}): BugReport {
            onStep("Проверяем связь…")
            val cfg = Settings.current.toConfigOrNull()
            val probe = if (cfg == null || !withProbe) emptyList() else runCatching { Diagnostics.run(cfg, timeoutMs = 5000) }.getOrDefault(emptyList())

            onStep("Собираем журнал…")
            val s = Settings.current
            val head = IssueReport.mask(buildString {
                appendLine("**Что случилось:** <!-- опишите своими словами: что делали и что пошло не так -->")
                appendLine()
                appendLine("### Устройство")
                appendLine("- Приложение: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) ", отладочная сборка" else ""}")
                appendLine("- Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("- Сеть: ${network(ctx)}")
                appendLine("- Уведомления: ${yesNo(notifOk(ctx))}, экономия батареи отключена: ${yesNo(batteryOk(ctx))}, кнопка в шторке: ${yesNo(TileAdder.added.value)}")
                appendLine()
                appendLine("### Прокси")
                appendLine("- Состояние: ${ProxyState.status.value}${ProxyState.error.value?.let { " — $it" } ?: ""}")
                appendLine("- Статистика: ${Stats.summary()}")
                appendLine("- Порт ${s.port}, DC→IP: ${s.dcIps.lines().filter { it.isNotBlank() }.joinToString(", ")}")
                appendLine("- CF-прокси: ${yesNo(s.cfProxy)}, свои домены: ${if (s.cfDomains.isBlank()) "нет" else "есть"}, Worker: ${if (s.cfWorkerDomains.isBlank()) "нет" else "есть"}")
                appendLine("- Пул ${s.poolSize}, без TLS: ${yesNo(s.noSecure)}, доступ из сети: ${yesNo(s.allowLan)}, Fake TLS: ${yesNo(s.fakeTlsDomain.isNotBlank())}, автозапуск: ${yesNo(s.autostart)}")
                if (probe.isNotEmpty()) {
                    appendLine()
                    appendLine("### Проверка связи")
                    probe.forEach { appendLine("- ${if (it.ok) "✅" else "❌"} ${it.method} · ${it.target} — ${if (it.ok) "${it.ms} мс" else it.detail}") }
                }
                LogFile.lastCrash()?.let {
                    appendLine()
                    appendLine("### Последнее падение")
                    appendLine("```")
                    appendLine(it.lines().take(25).joinToString("\n"))
                    appendLine("```")
                }
            })
            val log = LogFile.tail(LOG_LINES).map(IssueReport::mask)

            val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
            val file = File(dir, "tg-ws-proxy-report_$stamp.txt")
            file.writeText(head + "\n### Журнал\n" + log.joinToString("\n") + "\n")
            return BugReport(head, log, file)
        }

        private fun yesNo(b: Boolean) = if (b) "да" else "нет"

        private fun notifOk(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun batteryOk(ctx: Context) =
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

        private fun network(ctx: Context): String {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return "неизвестно"
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "нет подключения"
            val parts = ArrayList<String>()
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts += "Wi-Fi"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts += "мобильная"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts += "VPN"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts += "Ethernet"
            return parts.ifEmpty { listOf("другая") }.joinToString(" + ")
        }
    }
}
