package com.aveharrisan.tgwsproxy.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aveharrisan.tgwsproxy.BuildConfig
import com.aveharrisan.tgwsproxy.ProxyState
import com.aveharrisan.tgwsproxy.R
import com.aveharrisan.tgwsproxy.core.Level
import com.aveharrisan.tgwsproxy.core.Stats

@Composable
fun LogsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val logs by ProxyState.logs.collectAsState()
    val state = rememberLazyListState()
    var reportOpen by remember { mutableStateOf(false) }
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) state.scrollToItem(logs.lastIndex) }

    fun report(): String = buildString {
        appendLine("TG WS Proxy ${BuildConfig.VERSION_NAME}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("stats: ${Stats.summary()}")
        appendLine()
        logs.forEach { appendLine(it.text) }
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tab_logs), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { reportOpen = true }) { Icon(Icons.Outlined.BugReport, stringResource(R.string.rep_btn)) }
            IconButton(onClick = { copy(ctx, report()) }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy)) }
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report())
                ctx.startActivity(Intent.createChooser(send, null))
            }) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) }
            IconButton(onClick = { ProxyState.clearLogs() }) { Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.clear)) }
        }
        Card(Modifier.fillMaxWidth().weight(1f)) {
            if (logs.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.logs_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().padding(8.dp), state = state) {
                    items(logs, key = { it.id }) { l ->
                        Text(
                            l.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                            color = when (l.level) {
                                Level.ERROR -> MaterialTheme.colorScheme.error
                                Level.WARN -> Color(0xFFD08700)
                                Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
    if (reportOpen) ReportDialog { reportOpen = false }
}
