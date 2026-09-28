package com.aveharrisan.tgwsproxy.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aveharrisan.tgwsproxy.BuildConfig
import com.aveharrisan.tgwsproxy.R
import com.aveharrisan.tgwsproxy.Release
import com.aveharrisan.tgwsproxy.UpdateStatus
import com.aveharrisan.tgwsproxy.Updater
import com.aveharrisan.tgwsproxy.core.humanBytesRu
import kotlinx.coroutines.launch

/** Плашка «Вышла новая версия» над любой вкладкой: что изменилось, скачать и установить. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val status by Updater.status.collectAsState()
    val dismissed by Updater.dismissed.collectAsState()
    val release = when (val s = status) {
        is UpdateStatus.Available -> s.release
        is UpdateStatus.Downloading -> s.release
        is UpdateStatus.Ready -> s.release
        is UpdateStatus.Failed -> s.release
        else -> null
    }
    AnimatedVisibility(release != null && !dismissed, modifier, enter = expandVertically(), exit = shrinkVertically()) {
        if (release != null) UpdateCard(status, release)
    }
}

@Composable
private fun UpdateCard(status: UpdateStatus, release: Release) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var notesOpen by remember { mutableStateOf(false) }
    var needPermission by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }

    fun installOrAsk(s: UpdateStatus.Ready) {
        if (Updater.canInstall(ctx)) Updater.install(ctx, s.file) else needPermission = true
    }

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.upd_title, release.version), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { Updater.dismissed.value = true }) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.upd_later))
                }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.upd_current, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)

                // Что изменилось: три первых пункта, остальное — по кнопке.
                val shown = release.notes.take(3)
                shown.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                if (release.notes.size > shown.size)
                    Text(stringResource(R.string.upd_more, release.notes.size - shown.size), style = MaterialTheme.typography.bodySmall)

                when (status) {
                    is UpdateStatus.Downloading -> {
                        Spacer(Modifier.width(4.dp))
                        LinearProgressIndicator(progress = { status.percent / 100f }, Modifier.fillMaxWidth().padding(top = 6.dp))
                        Text(stringResource(R.string.upd_downloading, status.percent), style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateStatus.Failed -> Text(stringResource(R.string.upd_failed, status.message),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> {}
                }

                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (status) {
                        is UpdateStatus.Ready -> Button(onClick = { installOrAsk(status) }) { Text(stringResource(R.string.upd_install)) }
                        is UpdateStatus.Downloading -> Button(onClick = {}, enabled = false) { Text(stringResource(R.string.upd_wait)) }
                        else -> Button(onClick = { scope.launch { Updater.download(ctx, release) } }) {
                            Text(if (status is UpdateStatus.Failed) stringResource(R.string.upd_retry)
                            else if (release.apkSize > 0) stringResource(R.string.upd_download_size, humanBytesRu(release.apkSize))
                            else stringResource(R.string.upd_download))
                        }
                    }
                    // Сорвалось — сразу даём собрать логи: по одной строке ошибки причину не найти.
                    if (status is UpdateStatus.Failed) TextButton(onClick = { reportOpen = true }) { Text(stringResource(R.string.rep_btn)) }
                    else TextButton(onClick = { notesOpen = true }) { Text(stringResource(R.string.upd_whats_new)) }
                }
            }
        }
    }

    if (notesOpen) NotesDialog(release) { notesOpen = false }
    if (reportOpen) ReportDialog { reportOpen = false }

    if (needPermission) AlertDialog(
        onDismissRequest = { needPermission = false },
        title = { Text(stringResource(R.string.upd_perm_title)) },
        text = { Text(stringResource(R.string.upd_perm_text)) },
        confirmButton = {
            TextButton(onClick = { needPermission = false; Updater.openInstallPermission(ctx) }) { Text(stringResource(R.string.btn_allow)) }
        },
        dismissButton = { TextButton(onClick = { needPermission = false }) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

@Composable
private fun NotesDialog(release: Release, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.upd_notes_title, release.version)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (release.notes.isEmpty()) Text(stringResource(R.string.upd_no_notes))
                release.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton(onClick = { openUrl(ctx, release.pageUrl) }) { Text(stringResource(R.string.upd_page)) } },
    )
}

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
