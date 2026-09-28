package com.aveharrisan.tgwsproxy

import com.aveharrisan.tgwsproxy.core.Level
import com.aveharrisan.tgwsproxy.core.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Status { STOPPED, STARTING, RUNNING, ERROR }

data class LogLine(val id: Long, val level: Level, val text: String)

/** Общее состояние между службой, экраном и плиткой в шторке. */
object ProxyState {
    private const val MAX_LINES = 1500

    val status = MutableStateFlow(Status.STOPPED)
    val error = MutableStateFlow<String?>(null)
    val startedAt = MutableStateFlow(0L)

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs
    private var nextId = 0L
    private var attached = false

    fun attachLog() {
        if (attached) return
        attached = true
        Log.addSink { level, line ->
            android.util.Log.println(
                when (level) { Level.DEBUG -> android.util.Log.DEBUG; Level.INFO -> android.util.Log.INFO
                    Level.WARN -> android.util.Log.WARN; Level.ERROR -> android.util.Log.ERROR },
                "TgWsProxy", line,
            )
            LogFile.append(line)
            synchronized(this) {
                val cur = _logs.value
                val next = if (cur.size >= MAX_LINES) cur.subList(cur.size - MAX_LINES + 200, cur.size) else cur
                _logs.value = next + LogLine(nextId++, level, line)
            }
        }
    }

    fun clearLogs() = synchronized(this) { _logs.value = emptyList() }
}
