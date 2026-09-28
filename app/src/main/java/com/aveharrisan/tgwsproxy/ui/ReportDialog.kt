package com.aveharrisan.tgwsproxy.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aveharrisan.tgwsproxy.BugReport
import com.aveharrisan.tgwsproxy.R
import com.aveharrisan.tgwsproxy.core.humanBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Сбор логов и выбор, куда отправить: задача на GitHub, файлом в мессенджер или сохранить. */
@Composable
fun ReportDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<BugReport?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) { BugReport.collect(ctx.applicationContext) { s -> scope.launch { step = s } } }
        }.onSuccess { report = it }.onFailure { failed = it.message ?: it.javaClass.simpleName }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val r = report ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(r.file.readBytes()) } != null }.getOrDefault(false)
            }
            Toast.makeText(ctx, if (ok) ctx.getString(R.string.rep_saved) else ctx.getString(R.string.rep_failed, "не удалось записать файл"),
                Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Outlined.BugReport, null) },
        title = { Text(stringResource(R.string.rep_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val r = report
                when {
                    failed != null -> Text(stringResource(R.string.rep_failed, failed!!), color = MaterialTheme.colorScheme.error)
                    r == null -> {
                        Text(stringResource(R.string.rep_intro), style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(step, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    else -> {
                        Text(stringResource(R.string.rep_ready, humanBytes(r.file.length())), style = MaterialTheme.typography.bodyMedium)
                        Choice(Icons.Outlined.BugReport, R.string.rep_github, R.string.rep_github_sub) {
                            val link = r.issueLink()
                            if (link.trimmed) Toast.makeText(ctx, R.string.rep_trimmed, Toast.LENGTH_LONG).show()
                            openUrl(ctx, link.url)
                        }
                        Choice(Icons.Outlined.Share, R.string.rep_share, R.string.rep_share_sub) {
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", r.file)
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_SUBJECT, "TG WS Proxy — отчёт о проблеме")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            ctx.startActivity(Intent.createChooser(send, null))
                        }
                        Choice(Icons.Outlined.SaveAlt, R.string.rep_save, R.string.rep_save_sub) { saveLauncher.launch(r.file.name) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun Choice(icon: ImageVector, title: Int, sub: Int, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(stringResource(sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
