package com.aveharrisan.tgwsproxy

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Журнал на диске: переживает перезапуск и падение приложения, чтобы в отчёт
 * попало то, что было до проблемы. Два файла по 512 КБ — текущий и предыдущий.
 */
object LogFile {
    private const val MAX_BYTES = 512 * 1024L
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "log-file").apply { isDaemon = true } }
    private lateinit var dir: File

    private val current get() = File(dir, "current.log")
    private val previous get() = File(dir, "previous.log")
    private val crash get() = File(dir, "crash.txt")

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date())
        append("---- запуск $stamp, версия ${BuildConfig.VERSION_NAME} ----")
        installCrashHandler()
    }

    fun append(line: String) {
        if (!::dir.isInitialized) return
        writer.execute {
            runCatching {
                if (current.length() > MAX_BYTES) { previous.delete(); current.renameTo(previous) }
                current.appendText(line + "\n")
            }
        }
    }

    /** Последние строки обоих файлов, старые сверху. */
    fun tail(maxLines: Int): List<String> {
        if (!::dir.isInitialized) return emptyList()
        // Дожидаемся записи того, что уже в очереди, чтобы отчёт не терял последние строки.
        runCatching { writer.submit {}.get() }
        val lines = ArrayList<String>()
        for (f in listOf(previous, current)) if (f.exists()) runCatching { lines += f.readLines() }
        return if (lines.size > maxLines) lines.subList(lines.size - maxLines, lines.size) else lines
    }

    /** Текст последнего падения, если оно было. */
    fun lastCrash(): String? = if (::dir.isInitialized && crash.exists()) runCatching { crash.readText() }.getOrNull() else null

    /** После обновления старое падение к новой версии не относится. */
    fun clearCrash() {
        if (::dir.isInitialized) crash.delete()
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date())
                // Пишем сразу, без очереди: процесс вот-вот завершится.
                crash.writeText("$stamp, версия ${BuildConfig.VERSION_NAME}, поток ${thread.name}\n$sw")
                current.appendText("$stamp  CRASH  $e\n")
            }
            previousHandler?.uncaughtException(thread, e)
        }
    }
}
